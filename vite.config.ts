import { defineConfig } from "vite-plus";

/** Gradle and Cargo keep their own incremental caches; Vite+ must not short-circuit them. */
const gradle = (args: string) => ({ cache: false, command: `vpx _gradle ${args}` });
const cargo = (command: string) => ({ cache: false, command });
const quest = (bin: string) => ({ cache: false, command: `vpx ${bin}` });

const nonTypeScript = [
  "packages/dev/improve/xmux/android/**",
  "build.gradle.kts",
  "settings.gradle.kts",
  "gradle.properties",
  "gradle/**",
  "packages/dev/improve/xmux/bridge/**",
  "packages/dev/improve/xmux/packaging/**",
  "target/**",
  "Cargo.toml",
  "rust-toolchain.toml",
  ".cargo/**",
  "packages/dev/improve/xmux/assets/**",
  "packages/dev/improve/xmux/testing/fixtures/**",
  ".github/workflows/**",
];

export default defineConfig({
  fmt: {
    ignorePatterns: [...nonTypeScript, "**/*.md"],
  },
  lint: {
    ignorePatterns: nonTypeScript,
    options: { typeAware: true, typeCheck: true },
  },
  test: {
    include: ["packages/**/__tests__/**/*.test.ts"],
  },
  run: {
    tasks: {
      // Android (Gradle through the wrapper; the SDK is resolved by `_gradle`).
      "android:build": gradle("assembleRelease"),
      "android:test": gradle("testReleaseUnitTest apiCheck"),
      "android:api-dump": gradle("apiDump"),
      "android:lint": gradle("lint"),
      // The phone/emulator preview; it connects to 10.0.2.2:7272 (the Mac, seen from an emulator).
      "android:preview": gradle(":preview:installDebug"),

      // Mac bridge (Rust): the Cargo workspace is the repo root, crates live in packages/dev/improve/xmux/bridge.
      "bridge:check": cargo(
        "cargo fmt --all -- --check && cargo clippy --workspace --all-targets --all-features -- -D warnings",
      ),
      "bridge:test": cargo("cargo test --workspace"),
      // Against real cmux daemons (sandboxed servers); needs the cmux CLI.
      "bridge:test-daemon": cargo("cargo test -p xmux-bridge --features daemon --test daemon"),
      // Rewrites the wire goldens (fixtures/frames/*.bin and *.xmframes); run deliberately when the wire bytes are meant to change.
      "bridge:goldens": cargo(
        "XMUX_UPDATE_GOLDENS=1 cargo test -p xmux-proto golden && XMUX_UPDATE_GOLDENS=1 cargo test -p xmux-bridge --test conformance",
      ),
      // Re-records the conformance traces against a sandboxed cmux server; then run bridge:goldens.
      "bridge:record": cargo("cargo run -q -p xmux-view -- record"),
      // A bridge for the cmux session `main` on port 7272, from source.
      "bridge:serve": cargo("cargo run -q -p xmux-bridged -- serve"),

      // TypeScript tooling in packages/.
      "ts:check": { cache: false, command: "vp check" },
      "ts:test": { cache: false, command: "vp test run" },

      // GitHub Actions YAML is generated from packages/dev/improve/xmux/configs/github/workflows.
      "workflows:generate": { cache: false, command: "vpx _gwf" },

      // Quest helpers (adb over USB or Wi-Fi; QUEST_SERIAL or --serial picks the device).
      "quest:install": quest("_quest-install"),
      "quest:stats": quest("_quest-stats"),
      "quest:stress": quest("_quest-stress"),
      "quest:capture": quest("_quest-capture"),

      check: {
        command: "true",
        dependsOn: ["ts:check", "ts:test", "bridge:check", "bridge:test", "android:test"],
      },
    },
  },
});
