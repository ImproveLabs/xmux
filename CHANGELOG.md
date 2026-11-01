# Changelog

xmux follows `0.MINOR.PATCH` until 1.0: a MINOR release may break the Kotlin API or the wire
protocol, and says so here. The Android libraries, the bridge and the wire protocol share one
version.

## 0.2.0 — Preview

- Opt-in grid resizing (`SpatialConfig.resizeGrids`): resizing a cmux screen window re-flows its
  terminals instead of scaling the panel. ISDK `Relayout` with a free aspect ratio reveals or crops
  the panel while dragging; on release, `ScreenLayoutEngine.fit` sizes every pane to whole cells,
  `XmuxSpatial.onGridResize` gets the new grids (default: `TerminalHandle.resize`), and the window
  follows them or snaps back. `XmuxSpatial.resizeWindow` resizes a window from code.
- Move runnable apps to the top-level `apps/` directory and rename the internal demo library to
  `xmux-demo-content`. Android Gradle project names now match their app directories.
- Rename the developer CLI crate to `xmux-cli` and its executable to `xmux`.
- Split UI into independent Gradle modules under `components/`, with terminal primitives under
  `lib/`. Cell grids and screen layout are plain Kotlin/JVM libraries. Every published library
  shares its Gradle setup through the conventions in `build-logic/`.
- Breaking: remove the `xmux-core` and `xmux-compose` bundles. Depend on the modules you use;
  `xmux-spatial` plus `xmux-connect-panel` cover a Quest app. Remove the unused
  `ExperimentalXmuxApi` and `InternalXmuxApi` annotations.
- Breaking Kotlin API change: move packages from `dev.xmux.*` to `dev.improve.xmux.*`, including
  generated protobuf types, and give every library module its own package, equal to its Android
  namespace. `dev.improve.xmux.core` splits into `cell.grid`, `screen.layout`, `terminal.font`,
  `key.encoder`, `terminal.grid` and `screen.view`; `dev.improve.xmux.compose` into
  `connect.panel`, `window.frame` and `terminal.window` (all under `dev.improve.xmux`).
  `ScreenModels` moves out of the client into `xmux-screen-view-session`
  (`dev.improve.xmux.screen.view.session`). Embedders must update imports and dependencies.
- Breaking: rename the remaining `dev.xmux` identifiers to `dev.improve.xmux`. Libraries publish as
  `dev.improve.xmux:<module>`. The apps install as `dev.improve.xmux.quest`, `.preview` and
  `.stress`, next to older builds rather than over them, so headsets must pair again. The bridge's
  LaunchAgent label and macOS state directory are `dev.improve.xmux.bridge`: run
  `xmux-bridge service uninstall` before upgrading, and `service install` again after.
- Tooling finds the Android SDK through `ANDROID_HOME` or Android Studio's default location; the
  deprecated `ANDROID_SDK_ROOT` is no longer read.
- `xmux-spatial`: `XmuxSpatial.hitTest(origin, direction)` and `hitTest(HitInfo)` return the window,
  pane, terminal and cell under a ray (`WindowHit`). Windows are intersected at their current pose
  and size; checked on a Quest 3 against the coordinates the Spatial SDK reports.
- `xmux-spatial`: cmux screen windows take a `WindowChrome` (`mount(..., chrome)`,
  `follow(..., chrome = { screen -> ... })`), so header slots work for them as for grid windows.
  Grid windows with a custom header height now get a panel sized for it.
- `xmux-spatial`: `ScreenWindow.focusedPane`, `focusedTerminal`, `chrome` and `panelSize`;
  `XmuxSpatial.focus(window, pane)` and `focusedTerminal()` for apps that move focus by ray or
  gesture. Recompile binary consumers of `mount(session, screen, placement)` and `follow`, which
  gained a parameter.

## 0.1.1 — Preview

- Fix fresh-runner Rust installation and Android library publication after the unpublished 0.1.0 release attempt.
- Publish AARs, source JARs, POMs, and Gradle module metadata; omit generated Javadocs because AGP 8.11's bundled Dokka cannot read Java 17 sealed classes.

### Bridge (`xmux-bridge`)

- Mirrors a cmux session: `session.events` for workspaces, screens, layouts, panes and tabs;
  `terminal.attach` per terminal a headset shows (shared between headsets), read straight from the
  socket and decoded leniently so newer cmux daemons keep working.
- Never resizes the Mac's grid: viewers attach without a size; resizing is an explicit request.
- Sends rows in 16 ms batches with generations, sequences and periodic checksums; reattaches and
  resyncs after a stream gap; applies input, pane and tab focus in order with idempotency keys.
- TLS with a self-signed certificate, pairing with a six-digit code typed into `xmux-bridge pair`,
  Bonjour advertisement (`_xmux._tcp`), `status`, `unpair`, `service install` (LaunchAgent),
  `serve --replay` for recorded traces.

### Wire protocol v1 (`packages/dev/improve/xmux/schema/proto/xmux.proto`)

- 16-byte header, protobuf `Frame` body, optional LZ4. Runs carry explicit cell boundaries
  (`width_hint`), so clients need no Unicode tables.
- `Hello`/`Welcome` with pairing, `Topology`, `Rows`, `Cursor`, `Checksum`, `AttachState`, `Input`,
  `Resize`, `FocusDirection`, `FocusPane`, `FocusTab`, `AttachTerminal`, `DetachTerminal`,
  `ResyncTerminal`, `Ping`/`Pong`, `Stats`, `Bye`.

### Android libraries

- `xmux-client`: `XmuxClient` (Bonjour discovery, TLS with certificate pinning), `XmuxSession`
  (pairing, topology, terminals, input, reconnect with backoff, resync), `RowApplier`, consumer
  R8 rules for lz4.
- `xmux-core`: grapheme clusters and terminal default colors in `CellGrid`, cursor shapes,
  `ScreenView` and `ScreenLayoutEngine` for split/stack/viewport layouts, `KeyEncoder`.
- `xmux-compose`: `WindowFrame` around any view, `ConnectPanel` for picking a bridge and pairing.
- `xmux-spatial`: windows for cmux screens (`mount(session, screen)`, `follow(session)`), sized
  from their panes and re-created when a terminal resizes, keyboard and pointer input,
  `mountPanel` for app UI.

### Apps

- The XMUX app: connect panel, pairing, one window per screen, a demo without a Mac.
- The preview app connects to a bridge (default `10.0.2.2:7272` from an emulator).

### Tests and tooling

- Ten conformance scenarios recorded from a sandboxed cmux server (`xmux record`); the Rust
  grid and the Kotlin `RowApplier` both replay them to the grids cmux reported.
- Hub tests against a scripted cmux, integration tests against real cmux daemons, TLS
  end-to-end tests, session tests against `FakeBridge`.
- Release workflow: universal macOS binary and Homebrew formula, minified Quest APK, and five Android libraries on GitHub Packages.
