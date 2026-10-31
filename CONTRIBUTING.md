# Contributing

- Every behavior change comes with a test at the lowest level that can see it: Rust unit tests in the
  bridge, JVM tests in `xmux-core`/`xmux-client`, Robolectric draw tests for the renderer, device smoke
  (`vpx _quest-stress`, `vpx _quest-stats`) only for what needs a headset.
- Public Kotlin API is explicit (`explicitApi()`) and checked by the binary-compatibility validator:
  run `vpr android:api-dump` and commit the `api/*.api` diff with any public change.
- The wire schema is `packages/dev/improve/xmux/schema/xmux.proto`; code is generated at build time
  on both sides (nothing generated is checked in). New messages get the next `Frame` field number,
  which is also the header's kind byte: raise `KIND_MAX` in `framing.rs` and `FrameCodec.kt`.
- Conformance fixtures live in `packages/dev/improve/xmux/testing/fixtures/`: `traces/` (raw cmux
  items, recorded with `vpr bridge:record` against a sandboxed cmux server), `expected/` (the grids
  cmux reported) and `frames/` (the frame streams both clients replay). When the wire bytes change
  on purpose, run `vpr bridge:goldens` and commit the diff.
- Bridge changes that touch cmux run `vpr bridge:test-daemon` too (needs the cmux CLI; each test
  starts its own sandboxed server).
- Pre-1.0 versioning: `0.MINOR.PATCH`; MINOR may break with a CHANGELOG entry.
- Scripts are TypeScript run by Bun, one package per script under
  `packages/dev/improve/xmux/configs/<area>/<name>/` (bin `_name.ts`, command in `name-command.ts`,
  shared code in small library packages, tests in a sibling `__tests__` package). npm dependencies
  go through a wrapper package in `packages/com/npmjs/<name>` with the version in the root catalog.
- Install with `vp install`, never `bun install`. Run tasks with `vpr <task>` (defined in
  `vite.config.ts`).
- `.github/workflows/*.yml` is generated from `packages/dev/improve/xmux/configs/github/workflows/*/*.wac.ts`
  on every `vp install`; edit the `.wac.ts` source, never the YAML.
- Tool versions live in `.prototools`; `configs/proto/__tests__` fails if `rust-toolchain.toml`
  or `packageManager` drift from it.
- Rust crates live under `packages/dev/improve/xmux/bridge/` and belong to the Cargo workspace at
  the repo root. Tests read shared files (the schema, `packages/dev/improve/xmux/testing/fixtures/`)
  through `env!("XMUX_REPO_ROOT")`, never `CARGO_MANIFEST_DIR/../..`. Build scripts walk up from
  `CARGO_MANIFEST_DIR` instead, because `cargo install --git` does not read `.cargo/config.toml`.
- Android modules live under `packages/dev/improve/xmux/android/` and belong to the Gradle build at the
  repo root. `settings.gradle.kts` includes every directory under `packages/` with a `build.gradle.kts`,
  named after the directory (names must be unique; library names equal their artifact names). Read
  shared files through `rootDir.resolve(...)`, never `../..`.
