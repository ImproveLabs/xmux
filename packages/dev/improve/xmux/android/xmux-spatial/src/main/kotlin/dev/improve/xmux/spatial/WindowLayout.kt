package dev.improve.xmux.spatial

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure placement math. Spatial SDK scene space is +Z forward and a panel's visible face is its
 * local -Z, so `Quaternion.lookRotation(offsetFromHead)` makes a window face the head.
 */
public object WindowLayout {
  /** The head pose is usable once it has a finite, non-origin position and a forward vector. */
  public fun isUsableHeadPose(head: Pose): Boolean {
    val t = head.t
    if (!(t.x.isFinite() && t.y.isFinite() && t.z.isFinite())) return false
    if (t.x == 0f && t.y == 0f && t.z == 0f) return false
    return head.forward().length() >= 0.5f
  }

  public fun inFrontOfHead(head: Pose, distanceM: Float): Pose {
    val offset = head.forward().normalize() * distanceM
    return Pose(head.t + offset, Quaternion.lookRotation(offset))
  }

  /** Columns for [count] windows at a roughly 16:9 arrangement. */
  public fun arcColumns(count: Int): Int = ceil(sqrt(count * 16f / 9f)).toInt().coerceAtLeast(1)

  /**
   * Pose of slot [index] of [count] on a cylinder of [radiusM] around the head, centred on the
   * head's yaw, rows stacked by [heightM] + 5 cm, every window facing the head.
   */
  public fun arc(head: Pose, index: Int, count: Int, widthM: Float, heightM: Float, radiusM: Float, gapDegrees: Float = 2f): Pose {
    require(index in 0 until count) { "index $index out of 0 until $count" }
    val columns = arcColumns(count)
    val rows = ceil(count / columns.toFloat()).toInt()
    val stepYaw = 2f * asin(widthM / 2f / radiusM) + Math.toRadians(gapDegrees.toDouble()).toFloat()
    val stepY = heightM + 0.05f
    val forward = head.forward()
    val centreYaw = atan2(forward.x, forward.z)
    val c = index % columns
    val r = index / columns
    val yaw = centreYaw + (c - (columns - 1) / 2f) * stepYaw
    val y = ((rows - 1) / 2f - r) * stepY
    val offset = Vector3(sin(yaw) * radiusM, y, cos(yaw) * radiusM)
    return Pose(head.t + offset, Quaternion.lookRotation(offset))
  }

  public fun resolve(placement: Placement, head: Pose, config: SpatialConfig, spec: PanelSpec): Pose =
    when (placement) {
      Placement.InFrontOfHead -> inFrontOfHead(head, config.spawnDistanceM)
      is Placement.Arc -> arc(head, placement.index, placement.count, spec.widthM, spec.heightM, config.arcRadiusM)
      is Placement.Fixed -> placement.pose
    }
}
