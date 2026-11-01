# Contributing

- Every behavior change comes with a test at the lowest level that can see it: Rust unit tests in the
  bridge, JVM tests beside their component/library, Robolectric draw tests for the renderer, device smoke
  (`vpx _quest-stress`, `vpx _quest-stats`) only for what needs a headset.
- Kotlin packages use `dev.improve.xmux.*`; source paths mirror the package under each
  `src/<sourceSet>/kotlin/dev/improve/xmux/` directory.
- Public Kotlin API is explicit (`explicitApi()`) and checked by the binary-compatibility validator:
  run `vpr android:api-dump` and commit the `api/*.api` diff with any public change.
- The wire schema is `packages/dev/improve/xmux/schema/proto/xmux.proto`; code is generated at build time
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
  `packages/dev/improve/xmux/configs/<area>/<name>/`. Every `_name.ts` launcher has two siblings:
  `name-command.ts` exports the citty command, and `name.ts` contains the actual logic. For example,
  `_quest-install.ts` has `quest-install-command.ts` and `quest-install.ts`. Shared code goes in
  small library packages, with tests in a sibling `__tests__` package. npm dependencies
  go through a wrapper package in `packages/com/npmjs/<name>` with the version in the root catalog.
- Use `import fs from "@-/com.npmjs.fs-extra"` for filesystem operations. The wrapper exposes only
  the default export.
- Install with `vp install`, never `bun install`. Run tasks with `vpr <task>` (defined in
  `vite.config.ts`).
- `.github/workflows/*.yml` is generated from `packages/dev/improve/xmux/configs/github/workflows/*/*.wac.ts`
  on every `vp install`; edit the `.wac.ts` source, never the YAML.
- Tool versions live in `.prototools`; `configs/proto/__tests__` fails if `rust-toolchain.toml`
  or `packageManager` drift from it.
- Rust apps live under `apps/xmux-macos-daemon/` and `apps/xmux-cli/`; Rust libraries live under
  `packages/dev/improve/xmux/bridge/` and `packages/dev/improve/xmux/schema/proto/rust/`. All belong
  to the Cargo workspace at the repo root.
  Tests read shared files (the schema, `packages/dev/improve/xmux/testing/fixtures/`)
  through `env!("XMUX_REPO_ROOT")`, never `CARGO_MANIFEST_DIR/../..`. Build scripts walk up from
  `CARGO_MANIFEST_DIR` instead, because `cargo install --git` does not read `.cargo/config.toml`.
- Android apps live under `apps/`; client/Spatial integrations, the `xmux-core` convenience bundle,
  and internal demo content live under `packages/dev/improve/xmux/android/`. UI components each have a
  Gradle module under `packages/dev/improve/xmux/components/`; supporting libraries live under
  `packages/dev/improve/xmux/lib/`. The generated protocol module lives under
  `packages/dev/improve/xmux/schema/proto/android/`. All belong to the Gradle build at the repo root.
  `settings.gradle.kts` includes every directory under `apps/` or `packages/` with a `build.gradle.kts`.
  Component/library paths derive unique `xmux-` names; the protocol module maps to `xmux-proto`.
  Library names equal their artifact names. Use the conventions in `build-logic/` for new modules;
  keep dependencies in each module's build file. Read shared files through `rootDir.resolve(...)`,
  never `../..`. Run `vpr android:test` for Android/JVM tests and API checks.
