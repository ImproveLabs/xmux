package dev.xmux.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** SHA-256 fingerprints as the bridge prints and advertises them (lowercase hex of the DER). */
public object Fingerprint {
  public fun sha256(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
}

/**
 * Trusts exactly one certificate: the bridge's self-signed one, by fingerprint. Without a pin
 * (first contact with a typed-in address) it trusts whatever it sees and records it; the
 * pairing code typed on the Mac is what authenticates that first contact.
 */
public class PinningTrustManager(private val pinned: String?) : X509TrustManager {
  @Volatile public var seen: String? = null
    private set

  override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
    val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
    val fp = Fingerprint.sha256(leaf.encoded)
    seen = fp
    if (pinned != null && !pinned.equals(fp, ignoreCase = true)) {
      throw CertificateException("bridge certificate $fp does not match the pinned $pinned")
    }
  }

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Unit = throw CertificateException("no client certificates")

  override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** A [BridgeConnection] over TLS 1.2+/TCP. */
public class TlsBridgeConnection private constructor(private val socket: SSLSocket, override val peerFingerprint: String) : BridgeConnection {
  private val input = BufferedInputStream(socket.inputStream, 64 * 1024)
  private val output = BufferedOutputStream(socket.outputStream, 16 * 1024)

  override val incoming: Flow<DecodedFrame> =
    flow {
      while (true) {
        val frame = FrameCodec.read(input) ?: break
        emit(frame)
      }
    }.flowOn(Dispatchers.IO)

  override suspend fun send(encoded: ByteArray) {
    withContext(Dispatchers.IO) {
      synchronized(output) {
        output.write(encoded)
        output.flush()
      }
    }
  }

  override fun close() {
    runCatching { socket.close() }
  }

  public companion object {
    /** Connects and completes the TLS handshake; [pinned] is the expected certificate fingerprint, if known. */
    public suspend fun connect(host: String, port: Int, pinned: String?, timeoutMs: Int = 5_000): TlsBridgeConnection =
      withContext(Dispatchers.IO) {
        val trust = PinningTrustManager(pinned)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val raw = Socket()
        try {
          raw.connect(InetSocketAddress(host, port), timeoutMs)
          raw.tcpNoDelay = true
          raw.keepAlive = true
          val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
          ssl.soTimeout = 0
          ssl.startHandshake()
          TlsBridgeConnection(ssl, trust.seen ?: error("handshake finished without a certificate"))
        } catch (e: Exception) {
          runCatching { raw.close() }
          throw e
        }
      }
  }
}
