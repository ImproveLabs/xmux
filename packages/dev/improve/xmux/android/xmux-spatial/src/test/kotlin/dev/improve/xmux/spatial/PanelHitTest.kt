package dev.improve.xmux.spatial

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelHitTest {
  private val spec = PanelSpec(widthPx = 740, heightPx = 557, widthM = 0.5f)

  @Test
  fun textureCoordinatesMapToPanelPixelsFromTheTopLeft() {
    assertEquals(0f to 0f, PanelHit.pixel(0f, 0f, spec))
    assertEquals(370f to 278.5f, PanelHit.pixel(0.5f, 0.5f, spec))
    assertEquals(740f to 557f, PanelHit.pixel(1f, 1f, spec))
  }

  @Test
  fun coordinatesOutsideThePanelMiss() {
    assertNull(PanelHit.pixel(-0.01f, 0.5f, spec))
    assertNull(PanelHit.pixel(0.5f, 1.01f, spec))
    assertNull(PanelHit.pixel(Float.NaN, 0.5f, spec))
  }

  /** Head, gaze and panel recorded on a Quest 3; the SDK's own raycast reported uv (0.49923, 0.53756). */
  @Test
  fun matchesTheTextureCoordinatesTheSdkReportedOnAQuest() {
    val panel = Pose(Vector3(0f, 1.3f, 1.1f), Quaternion(0f, 0f, 0f))
    val head = Vector3(-1.7289817E-4f, 1.1877449f, 3.8322806E-4f)
    val gaze = Vector3(-2.541254E-4f, 0.08608768f, 0.99628747f)
    val ray = assertNotNull(PanelHit.intersect(head, gaze, panel, 0.59016395f, 0.45901638f))
    assertNear(0.49923176f, ray.u)
    assertNear(0.5375581f, ray.v)
    assertNear(1.2827601f, ray.point.y)
    assertNear(1.1f, ray.point.z)
  }

  @Test
  fun uGrowsAlongLocalXAndVDownwardsOnATurnedPanel() {
    val head = Vector3(0.2f, 1.6f, -0.3f)
    val offset = Vector3(1f, 0f, 1f)
    val panel = Pose(head + offset, Quaternion.lookRotation(offset))
    val right = panel.q.times(Vector3(0.1f, 0f, 0f))
    val up = panel.q.times(Vector3(0f, 0.1f, 0f))
    val centre = assertNotNull(PanelHit.intersect(head, offset, panel, 1f, 0.5f))
    assertNear(0.5f, centre.u)
    assertNear(0.5f, centre.v)
    assertNear(offset.length(), centre.distance)
    assertNear(0.6f, assertNotNull(PanelHit.intersect(head, offset + right, panel, 1f, 0.5f)).u)
    assertNear(0.3f, assertNotNull(PanelHit.intersect(head, offset + up, panel, 1f, 0.5f)).v)
  }

  @Test
  fun raysThatMissThePanelReturnNull() {
    val panel = Pose(Vector3(0f, 1.3f, 1.1f), Quaternion(0f, 0f, 0f))
    val head = Vector3(0f, 1.3f, 0f)
    assertNull(PanelHit.intersect(head, Vector3(0f, 0f, -1f), panel, 1f, 0.5f), "pointing away")
    assertNull(PanelHit.intersect(head, Vector3(1f, 0f, 0f), panel, 1f, 0.5f), "parallel")
    assertNull(PanelHit.intersect(head, Vector3(0.6f, 0f, 1.1f), panel, 1f, 0.5f), "past the right edge")
    assertNull(PanelHit.intersect(head, Vector3(0f, 0f, 0f), panel, 1f, 0.5f), "no direction")
  }

  private fun assertNear(expected: Float, actual: Float, eps: Float = 1e-4f) =
    assertTrue(abs(expected - actual) < eps, "expected $expected got $actual")

  @Test
  fun theHeaderAndItsDividerAreNotBody() {
    assertNull(PanelHit.bodyY(0f, 56))
    assertNull(PanelHit.bodyY(56.5f, 56))
    assertEquals(0f, PanelHit.bodyY(57f, 56))
    assertEquals(100f, PanelHit.bodyY(157f, 56))
  }
}
