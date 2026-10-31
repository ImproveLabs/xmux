package dev.xmux.client

import kotlin.test.Test
import kotlin.test.assertEquals

class ChecksumTest {
  private fun fnv(s: String): Long = Fnv1a().apply { bytes(s.toByteArray()) }.value()

  @Test
  fun matchesTheFnv1aReferenceVectorsTheBridgeUses() {
    assertEquals(0xcbf29ce484222325uL.toLong(), fnv(""))
    assertEquals(0xaf63dc4c8601ec8cuL.toLong(), fnv("a"))
    assertEquals(0x85944171f73967e8uL.toLong(), fnv("foobar"))
  }
}
