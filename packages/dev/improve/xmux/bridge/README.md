# bridge

The Mac side of xmux, in Rust. The Cargo workspace is the repository root (`/Cargo.toml`); it
includes the libraries here and the runnable apps under the repository's `apps/` directory.

| Directory | Crate | What |
|---|---|---|
| [`../schema/proto/rust/`](../schema/proto/rust/) | `xmux-proto` | protobuf types for `../schema/proto/xmux.proto` (generated in `build.rs` by protox + prost), 16-byte framing, LZ4, the row checksum |
| `./` | `xmux-bridge` | the cmux mirror and the hub (below) |
| [`apps/xmux-macos-daemon/`](../../../../../apps/xmux-macos-daemon/) (repo root) | `xmux-bridged` | the `xmux-bridge` binary: TLS listener, pairing, Bonjour, control socket, LaunchAgent |
| [`apps/xmux-cli/`](../../../../../apps/xmux-cli/) (repo root) | `xmux-cli` | the `xmux` binary: `record` (conformance traces), `replay`, `frames`, `probe` |

`xmux-bridge` (lib), by module:

| Module | What |
|---|---|
| `raw`, `protocol` | `session.events` and `terminal.attach` read straight from the cmux socket and decoded leniently (a newer daemon's extra fields are ignored) |
| `sdk` | `Cmux` for a live session: streams through `raw`, input and focus through the cmux Rust SDK |
| `replay`, `trace` | `Cmux` from a recorded trace (tests, `serve --replay`); the trace file format |
| `model` | the cmux items the bridge consumes |
| `terminal`, `cells` | one terminal: render items in, `Rows`/`Cursor`/`Checksum` frames out, runs with explicit cells |
| `topology` | the session's resources and their `Topology` frame, cmux ids to small ids |
| `hub` | one per process: shares attach streams between headsets, batches rows every 16 ms, resyncs after gaps, applies input in order |
| `grid`, `conformance` | the client side in Rust, and trace replay for the conformance fixtures |
| `sandbox` | a throwaway cmux server for recordings and integration tests |

Tests that read shared files (the schema, `packages/dev/improve/xmux/testing/fixtures/`) use
`env!("XMUX_REPO_ROOT")`, set by `/.cargo/config.toml`, so they do not depend on their depth in
the tree. `../schema/proto/rust/build.rs` finds the schema by walking up instead, because `cargo install --git`
does not read that config file.

```sh
vpr bridge:test          # cargo test --workspace
vpr bridge:check         # cargo fmt --check, cargo clippy -D warnings
vpr bridge:test-daemon   # integration tests against real cmux (sandboxed servers)
vpr bridge:record        # re-record the conformance traces, then:
vpr bridge:goldens       # rewrite the frame goldens deliberately
vpr bridge:serve         # serve the cmux session "main" from source
```
