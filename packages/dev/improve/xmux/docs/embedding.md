# Embedding xmux in a Meta Spatial SDK app

`dev.xmux:xmux-spatial` puts xmux windows into your own `AppSystemActivity`: one window per cmux
screen of a bridge session, or windows around grids you fill yourself. The README shows how to
add the dependency from GitHub Packages (see the root README for authentication), or use Maven Local / `includeBuild` for local development.

## Requirements

- `minSdk = 34` and `ndk { abiFilters += "arm64-v8a" }`: the Spatial SDK ships arm64 only.
- Meta Spatial SDK 0.14.0 comes with `xmux-spatial` as an `api` dependency; do not pin another
  version next to it.
- The Compose compiler plugin is only needed if you write composables yourself (for example
  `WindowChrome` slots). The Spatial Editor Gradle plugin is not needed.

Your manifest needs the usual Quest entries:

```xml
<uses-feature android:name="android.hardware.vr.headtracking" android:version="1" android:required="true" />
<uses-feature android:name="com.oculus.feature.PASSTHROUGH" android:required="true" />
<application>
  <meta-data android:name="com.oculus.supportedDevices" android:value="quest3|quest3s" />
  <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask">
    <intent-filter>
      <action android:name="android.intent.action.MAIN" />
      <category android:name="android.intent.category.LAUNCHER" />
      <category android:name="com.oculus.intent.category.VR" />
    </intent-filter>
  </activity>
</application>
```

From M1 on, connecting to the bridge also needs `android.permission.INTERNET`.

## The activity

```kotlin
import android.view.KeyEvent
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.PanelRegistration
import dev.xmux.client.BridgeTarget
import dev.xmux.client.XmuxClient
import dev.xmux.spatial.XmuxSpatial

class MainActivity : AppSystemActivity() {
  private val xmux by lazy { XmuxSpatial(this) }
  private val client by lazy { XmuxClient(this) }

  override fun registerFeatures(): List<SpatialFeature> = xmux.registerFeatures()

  override fun registerPanels(): List<PanelRegistration> = xmux.registerPanels()

  override fun onSceneReady() {
    super.onSceneReady()
    xmux.onSceneReady() // reference space, passthrough
    xmux.follow(client.connect(BridgeTarget("192.168.1.20")))
  }

  override fun onSceneTick() {
    super.onSceneTick()
    xmux.onSceneTick() // places windows, follows screens, redraws what changed
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean = xmux.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
}
```

`registerFeatures()` returns `VRFeature` (Interaction SDK input) and `ComposeFeature`. If your app
already registers its own, keep yours and skip xmux's. `registerPanels()` is empty today because
panels are registered as windows are mounted, but forward it so later versions can use it.

## Sessions

`XmuxClient.discover()` is a `Flow<BridgeAdvert>` of bridges found over Bonjour (`_xmux._tcp`);
`XmuxClient.connect(target)` returns an `XmuxSession` that keeps itself connected (reconnecting
with backoff, re-attaching terminals, resyncing from snapshots) until you `close()` it. Pass
`BridgeTarget.of(advert)` to pin the certificate the bridge advertised.

`session.state` is a `StateFlow<ConnectionState>`: `Connecting`, `Pairing(code)` (show the code;
the user types it into `xmux-bridge pair` on the Mac), `Online`, `Stalled`, `Reconnecting`,
`Closed`. The token the bridge issues is kept in the client's `PairingStore` (app-private
SharedPreferences by default). `ConnectPanel` in `xmux-compose` is a ready-made picker and
pairing screen; the XMUX app shows it in a panel with `xmux.mountPanel(...)`.

`session.topology` holds the session's workspaces, screens (layout tree, panes, tabs) and
terminals. Beyond what `follow` does:

| Call | What |
|---|---|
| `xmux.mount(session, screenId, placement)` | one window for one screen |
| `xmux.follow(null)` | stop following (windows stay until unmounted) |
| `session.terminal(id)` | a `TerminalHandle`: `grid`, `cursor`, `attach` state, `write`, `keys`, `mouse`, `resize` (opt-in; the Mac's grid is never resized otherwise) |
| `session.focusPane(id)`, `focusTab(id)`, `focusDirection(screen, direction)` | focus changes in cmux |
| `session.events` | screen added/removed/renamed, focus changes, attach state, latency |

Taps on a window focus the pane under them (in cmux too) and send mouse presses; tabs switch with
a tap on their strip. Keys go to the focused window's focused pane: from a hardware keyboard
through `dispatchKeyEvent`, from the system keyboard through the window's input connection.

## Grid windows

Windows can also show a `CellGrid` you fill yourself (demos, content that is not cmux):

```kotlin
val grid = CellGrid(80, 24)
val state = TerminalWindowState("hello").apply { loading = false }
grid.write(0, 0, "hello from xmux")
xmux.mount(WindowContent(state, grid), Placement.InFrontOfHead)
```

## Updating a grid window

A grid window draws its `CellGrid`. Change the grid with `write`, `put`, `fill`, `setCursor` and
`setCursorVisible`, then call `refreshIfDirty()` on the main thread:

```kotlin
grid.write(1, 0, "build passed", fg = 0xFF5FD75F.toInt())
xmux.windows.forEach { it.refreshIfDirty() }
```

The grid tracks which rows changed, so `refreshIfDirty()` on an unchanged window does no drawing. Keep
the number of windows that redraw at the same time small: on a Quest 3, four windows redrawing at
30 Hz plus idle ones stayed at 90 fps, while sixteen windows all redrawing at 30 Hz starved the frame
loop (see `measurements.md`).

`TerminalWindowState` holds the title and the loading spinner. `loading` starts as `true`; set it to
`false` once the window has content.

## Window chrome

`WindowContent(state, grid, chrome)` takes an optional `WindowChrome`: header height, corner radius,
colors, the traffic-light callbacks (`onClose`, `onMinimize`, `onZoom`) and two composable header slots
(`leadingSlot` after the traffic lights, `trailingSlot` before the spinner).

## Placement and configuration

`mount(content, placement)` returns a `ScreenWindow`; `unmount(window)` removes it. Placements:

| Placement | Where the window goes |
|---|---|
| `Placement.InFrontOfHead` | `spawnDistanceM` in front of the head, facing it (the default) |
| `Placement.Arc(index, count)` | slot `index` of `count` on an arc of radius `arcRadiusM` around the head |
| `Placement.Fixed(pose)` | a fixed scene pose |

Windows can always be grabbed, moved and resized with hands or controllers. `ScreenWindow.setPose`
moves one from code.

`SpatialConfig` fields:

| Field | Default | Meaning |
|---|---|---|
| `fontPx` | 25 | glyph size in panel pixels |
| `cols`, `rows` | 80, 24 | grid size the panel is laid out for |
| `windowWidthM` | 1.0 | window width in metres; height follows the panel's aspect |
| `paddingPx` | 10 | padding around the grid |
| `maxLayers` | 12 | windows after this many render as meshes instead of compositor layers |
| `spawnDistanceM` | 1.1 | distance for `InFrontOfHead` |
| `arcRadiusM` | 1.6 | radius for `Arc` |
| `enablePassthrough` | true | turn passthrough on in `onSceneReady()` |

Compositor layers look sharper than meshes but each one costs GPU time, so only the first
`maxLayers` windows get one.
