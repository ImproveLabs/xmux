package dev.improve.xmux.spatial

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.HitInfo
import dev.improve.xmux.client.PaneId
import dev.improve.xmux.client.TerminalId
import kotlin.math.abs

/**
 * What a ray hits on an xmux window (see [XmuxSpatial.hitTest]).
 *
 * [x] and [y] are panel pixels from the panel's top-left corner, header included. On a cmux screen
 * window, [pane] is the pane under the ray and [terminal] its active terminal; both are null over
 * the header. [row] and [col] are the cell under the ray, or -1 over the header, padding, a tab
 * strip or a divider. Grid windows report only the cell.
 */
public data class WindowHit(
  public val window: ScreenWindow,
  public val x: Float,
  public val y: Float,
  public val pane: PaneId?,
  public val terminal: TerminalId?,
  public val row: Int,
  public val col: Int,
  /** The ray is on the header bar (title, traffic lights, slots). */
  public val onHeader: Boolean,
  /** World-space point the ray hit (null for a [HitInfo] without one). */
  public val point: Vector3?,
)

/** Where a ray meets a panel: texture coordinates, distance along the ray and the world point. */
internal class PanelRay(val u: Float, val v: Float, val distance: Float, val point: Vector3)

/** Panel texture coordinates to panel pixels, the same mapping the Spatial SDK's panel input uses. */
internal object PanelHit {
  /**
   * Intersects a ray with a [widthM] x [heightM] panel centred on [pose]. Texture u grows along
   * the panel's local +X and v along its local -Y (top-left origin), which matches the
   * coordinates the Spatial SDK reports for panels on a Quest 3. Null when the ray is parallel,
   * points away, or passes outside the panel.
   */
  fun intersect(origin: Vector3, direction: Vector3, pose: Pose, widthM: Float, heightM: Float): PanelRay? {
    val length = direction.length()
    if (!(length > 0f && length.isFinite() && widthM > 0f && heightM > 0f)) return null
    val dir = direction * (1f / length)
    val normal = pose.q.times(Vector3(0f, 0f, 1f))
    val facing = dir.dot(normal)
    if (abs(facing) < 1e-6f) return null
    val distance = (pose.t - origin).dot(normal) / facing
    if (!(distance >= 0f)) return null
    val point = origin + dir * distance
    val local = point - pose.t
    val u = 0.5f + local.dot(pose.q.times(Vector3(1f, 0f, 0f))) / widthM
    val v = 0.5f - local.dot(pose.q.times(Vector3(0f, 1f, 0f))) / heightM
    if (!(u in 0f..1f && v in 0f..1f)) return null
    return PanelRay(u, v, distance, point)
  }

  /** Top-left origin, like Android view coordinates; null outside the panel. */
  fun pixel(u: Float, v: Float, spec: PanelSpec): Pair<Float, Float>? {
    if (!(u in 0f..1f && v in 0f..1f)) return null
    return u * spec.widthPx to v * spec.heightPx
  }

  /** The body's y for a panel [y] below a [headerPx] header and its 1 px divider; null on the header. */
  fun bodyY(y: Float, headerPx: Int): Float? {
    val body = y - headerPx - 1
    return if (body >= 0f) body else null
  }
}
