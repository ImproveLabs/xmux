package dev.improve.xmux.spatial

import com.meta.spatial.core.Pose

/** Tunables for how xmux windows are built and placed. */
public data class SpatialConfig(
  public val fontPx: Float = 25f,
  public val cols: Int = 80,
  public val rows: Int = 24,
  /** Physical width of a window in metres; height follows the panel's pixel aspect. */
  public val windowWidthM: Float = 1.0f,
  public val paddingPx: Float = 10f,
  /** Windows beyond this many are rendered as meshes instead of compositor layers. */
  public val maxLayers: Int = 12,
  public val spawnDistanceM: Float = 1.1f,
  public val arcRadiusM: Float = 1.6f,
  public val enablePassthrough: Boolean = true,
  /**
   * Resizing a cmux screen window re-flows its terminals instead of scaling the panel: the corners
   * reveal or crop the panel while dragging (ISDK `Relayout`, free aspect ratio), and on release
   * [XmuxSpatial.onGridResize] gets the grid each terminal needs to fill the new size. The window
   * then follows the terminals' new grids. Off by default, since it changes the Mac's grids.
   */
  public val resizeGrids: Boolean = false,
)

/** Where a newly mounted window goes. */
public sealed interface Placement {
  /** Along the head's forward vector at [SpatialConfig.spawnDistanceM], facing the head. */
  public data object InFrontOfHead : Placement

  /** Slot [index] of [count] on a cylindrical arc around the head (see [WindowLayout.arc]). */
  public data class Arc(public val index: Int, public val count: Int) : Placement

  /** A fixed scene pose; the window is not moved once the head pose is known. */
  public data class Fixed(public val pose: Pose) : Placement
}
