package dev.xmux.client

import android.content.Context

/**
 * What a headset remembers about bridges, keyed by the bridge's certificate fingerprint: the
 * pairing token, and the fingerprint last seen at an address (so a later connection to the same
 * address can be pinned before discovery is available).
 */
public interface PairingStore {
  public fun token(fingerprint: String): String?
  public fun setToken(fingerprint: String, token: String?)
  public fun fingerprintAt(address: String): String?
  public fun setFingerprintAt(address: String, fingerprint: String)
}

public class InMemoryPairingStore : PairingStore {
  private val tokens = HashMap<String, String>()
  private val addresses = HashMap<String, String>()

  override fun token(fingerprint: String): String? = synchronized(this) { tokens[fingerprint] }

  override fun setToken(fingerprint: String, token: String?) {
    synchronized(this) { if (token == null) tokens.remove(fingerprint) else tokens[fingerprint] = token }
  }

  override fun fingerprintAt(address: String): String? = synchronized(this) { addresses[address] }

  override fun setFingerprintAt(address: String, fingerprint: String) {
    synchronized(this) { addresses[address] = fingerprint }
  }
}

/** [PairingStore] in the app's private SharedPreferences. */
public class SharedPreferencesPairingStore(context: Context, name: String = "dev.xmux.pairing") : PairingStore {
  private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

  override fun token(fingerprint: String): String? = prefs.getString("token:$fingerprint", null)

  override fun setToken(fingerprint: String, token: String?) {
    prefs.edit().apply { if (token == null) remove("token:$fingerprint") else putString("token:$fingerprint", token) }.apply()
  }

  override fun fingerprintAt(address: String): String? = prefs.getString("fp:$address", null)

  override fun setFingerprintAt(address: String, fingerprint: String) {
    prefs.edit().putString("fp:$address", fingerprint).apply()
  }
}
