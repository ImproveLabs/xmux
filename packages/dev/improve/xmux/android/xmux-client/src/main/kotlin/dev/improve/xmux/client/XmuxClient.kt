package dev.improve.xmux.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.util.concurrent.Executor

/** A bridge found on the local network. */
public data class BridgeAdvert(
  public val name: String,
  public val host: String,
  public val port: Int,
  /** The certificate fingerprint from the TXT record; pinned on connect. */
  public val fingerprint: String?,
  public val session: String?,
)

/** Where to connect: from discovery, or an address the user typed. */
public data class BridgeTarget(public val host: String, public val port: Int = DEFAULT_PORT, public val fingerprint: String? = null) {
  public val address: String get() = "$host:$port"

  public companion object {
    public const val DEFAULT_PORT: Int = 7272

    public fun of(advert: BridgeAdvert): BridgeTarget = BridgeTarget(advert.host, advert.port, advert.fingerprint)
  }
}

/** Entry point: finds bridges over Bonjour (`_xmux._tcp`) and opens sessions to them. */
public class XmuxClient(
  private val context: Context,
  public val store: PairingStore = SharedPreferencesPairingStore(context),
) {
  /** Bridges as they are found and resolved; collect while a picker is on screen. */
  public fun discover(): Flow<BridgeAdvert> =
    callbackFlow {
      val nsd = context.getSystemService(NsdManager::class.java)
      val executor = Executor { it.run() }
      val callbacks = ArrayList<NsdManager.ServiceInfoCallback>()
      val listener =
        object : NsdManager.DiscoveryListener {
          override fun onServiceFound(info: NsdServiceInfo) {
            val callback =
              object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}

                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                  val host = resolved.hostAddresses.firstOrNull { it is Inet4Address } ?: resolved.hostAddresses.firstOrNull() ?: return
                  val txt = resolved.attributes
                  trySend(
                    BridgeAdvert(
                      name = resolved.serviceName,
                      host = host.hostAddress ?: return,
                      port = resolved.port,
                      fingerprint = txt["fp"]?.let { String(it) },
                      session = txt["session"]?.let { String(it) },
                    ),
                  )
                }

                override fun onServiceLost() {}

                override fun onServiceInfoCallbackUnregistered() {}
              }
            synchronized(callbacks) { callbacks.add(callback) }
            nsd.registerServiceInfoCallback(info, executor, callback)
          }

          override fun onServiceLost(info: NsdServiceInfo) {}

          override fun onDiscoveryStarted(serviceType: String) {}

          override fun onDiscoveryStopped(serviceType: String) {}

          override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            close(IllegalStateException("Bonjour discovery failed ($errorCode)"))
          }

          override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
      nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
      awaitClose {
        runCatching { nsd.stopServiceDiscovery(listener) }
        synchronized(callbacks) { for (c in callbacks) runCatching { nsd.unregisterServiceInfoCallback(c) } }
      }
    }

  /**
   * Opens a session that keeps itself connected until closed. The certificate is pinned to the
   * advertised fingerprint, or the one seen at this address before; a first contact trusts what it
   * sees, and the pairing code shown on the headset authenticates it.
   */
  public fun connect(target: BridgeTarget, scope: CoroutineScope = MainScope(), deviceName: String = Build.MODEL ?: "headset"): XmuxSession =
    BridgeSession(
      scope = scope,
      connect = {
        val conn = TlsBridgeConnection.connect(target.host, target.port, target.fingerprint ?: store.fingerprintAt(target.address))
        store.setFingerprintAt(target.address, conn.peerFingerprint)
        conn
      },
      store = store,
      deviceName = deviceName,
    )

  public companion object {
    public const val SERVICE_TYPE: String = "_xmux._tcp"
  }
}
