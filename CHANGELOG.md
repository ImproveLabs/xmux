# Changelog

xmux follows `0.MINOR.PATCH` until 1.0: a MINOR release may break the Kotlin API or the wire
protocol, and says so here. The Android libraries, the bridge and the wire protocol share one
version.

## 0.1.0 — Preview

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

### Wire protocol v1 (`packages/dev/improve/xmux/schema/xmux.proto`)

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

- Ten conformance scenarios recorded from a sandboxed cmux server (`xmux-view record`); the Rust
  grid and the Kotlin `RowApplier` both replay them to the grids cmux reported.
- Hub tests against a scripted cmux, integration tests against real cmux daemons, TLS
  end-to-end tests, session tests against `FakeBridge`.
- Release workflow: universal macOS binary and Homebrew formula, minified Quest APK, and five Android libraries on GitHub Packages.
