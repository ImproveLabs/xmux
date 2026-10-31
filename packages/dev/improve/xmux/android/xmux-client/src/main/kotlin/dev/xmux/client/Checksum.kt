package dev.xmux.client

import dev.xmux.proto.Run

/**
 * FNV-1a 64 over rows exactly as they travel on the wire; see `Checksum` in the schema and the
 * bridge's `checksum.rs`.
 */
public class Fnv1a {
  private var hash: Long = OFFSET

  public fun bytes(bytes: ByteArray) {
    for (b in bytes) {
      hash = hash xor (b.toLong() and 0xFF)
      hash *= PRIME
    }
  }

  public fun u32(value: Int) {
    for (shift in 0 until 32 step 8) {
      hash = hash xor ((value ushr shift).toLong() and 0xFF)
      hash *= PRIME
    }
  }

  public fun value(): Long = hash

  public companion object {
    private const val OFFSET: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME: Long = 0x100000001b3L

    /** Checksum over `(row index, runs)` in row order. */
    public fun rows(rows: Iterable<Pair<Int, List<Run>>>): Long {
      val h = Fnv1a()
      val zero = byteArrayOf(0)
      for ((index, runs) in rows) {
        h.u32(index)
        for (run in runs) {
          h.bytes(run.text.toByteArray(Charsets.UTF_8))
          h.bytes(zero)
          h.u32(run.fg)
          h.u32(run.bg)
          h.u32(run.attrs)
          h.u32(run.underlineValue)
          h.u32(run.widthHint)
        }
      }
      return h.value()
    }
  }
}
