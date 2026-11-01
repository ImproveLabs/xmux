package dev.improve.xmux.spatial

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs on the JVM: the Spatial SDK's core math types have no native dependencies. */
class WindowLayoutTest {
  private val head = Pose(Vector3(0.2f, 1.6f, -0.3f), Quaternion(0f, 0f, 0f))
  private val spec = PanelSpec.of(cellWidth = 9f, cellHeight = 20f, cols = 80, rows = 24, paddingPx = 10f, headerPx = 56, widthM = 0.5f)

  private fun assertNear(expected: Float, actual: Float, eps: Float = 1e-3f, what: String = "") =
    assertTrue(abs(expected - actual) < eps, "$what expected $expected got $actual")

  @Test
  fun panelSpecComesFromMetricsNotFromAView() {
    assertEquals(740, spec.widthPx)
    assertEquals(557, spec.heightPx)
    assertNear(0.5f * 557f / 740f, spec.heightM)
  }

  @Test
  fun inFrontOfHeadIsAlongForwardAndFacesTheHead() {
    val pose = WindowLayout.inFrontOfHead(head, 1.1f)
    assertNear(head.t.x, pose.t.x)
    assertNear(head.t.y, pose.t.y)
    assertNear(head.t.z + 1.1f, pose.t.z)
    val facing = pose.q.times(Vector3(0f, 0f, 1f))
    assertNear(1f, facing.z, what = "window +Z points away from the head so its -Z face looks back")
  }

  @Test
  fun arcKeepsEveryWindowOnTheCylinderAndTheMiddleRowAtHeadHeight() {
    val n = 16
    val poses = (0 until n).map { WindowLayout.arc(head, it, n, spec.widthM, spec.heightM, 1.6f) }
    for (p in poses) {
      val dx = p.t.x - head.t.x
      val dz = p.t.z - head.t.z
      assertNear(1.6f, kotlin.math.sqrt(dx * dx + dz * dz), 1e-3f, "radius")
    }
    // 16 windows -> 6 columns x 3 rows; row 1 (indices 6..11) sits at head height.
    assertEquals(6, WindowLayout.arcColumns(n))
    for (i in 6..11) assertNear(head.t.y, poses[i].t.y, what = "middle row height")
    assertTrue(poses[0].t.y > head.t.y && poses[15].t.y < head.t.y)
    // Row spacing is one panel height plus 5 cm, never a bogus fallback.
    assertNear(spec.heightM + 0.05f, poses[0].t.y - poses[6].t.y, what = "row step")
  }

  @Test
  fun arcIsCentredOnTheHeadYaw() {
    val turned = Pose(head.t, Quaternion(0f, 90f, 0f))
    val middle = WindowLayout.arc(turned, 7, 16, spec.widthM, spec.heightM, 1.6f) // column 1 of 6 is left of centre
    val centreColumn = WindowLayout.arc(turned, 8, 16, spec.widthM, spec.heightM, 1.6f)
    // Head looks along +X; the arc's centre direction is +X too.
    assertTrue(centreColumn.t.x > head.t.x + 1.0f)
    assertTrue(middle.t.x > head.t.x + 1.0f)
  }

  @Test
  fun unusableHeadPosesAreRejected() {
    assertFalse(WindowLayout.isUsableHeadPose(Pose(Vector3(0f, 0f, 0f), Quaternion(0f, 0f, 0f))))
    assertFalse(WindowLayout.isUsableHeadPose(Pose(Vector3(Float.NaN, 1f, 1f), Quaternion(0f, 0f, 0f))))
    assertTrue(WindowLayout.isUsableHeadPose(head))
  }

  @Test
  fun resolveHonoursFixedPlacement() {
    val fixed = Pose(Vector3(1f, 2f, 3f), Quaternion(0f, 45f, 0f))
    assertEquals(fixed, WindowLayout.resolve(Placement.Fixed(fixed), head, SpatialConfig(), spec))
  }
}
