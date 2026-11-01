# xmux

[![android](https://github.com/ImproveLabs/xmux/actions/workflows/android.yml/badge.svg)](https://github.com/ImproveLabs/xmux/actions/workflows/android.yml)
[![rust](https://github.com/ImproveLabs/xmux/actions/workflows/rust.yml/badge.svg)](https://github.com/ImproveLabs/xmux/actions/workflows/rust.yml)
[![typescript](https://github.com/ImproveLabs/xmux/actions/workflows/typescript.yml/badge.svg)](https://github.com/ImproveLabs/xmux/actions/workflows/typescript.yml)

**cmux on VR.** xmux shows the screens of a [cmux](https://github.com/manaflow-ai/cmux) session as floating,
macOS-style terminal windows over passthrough on Meta Quest, and sends keyboard and pointer input back.
The cmux-tui daemon on your Mac is the only source of truth: nothing on the headset owns terminal state,
so you can switch between the Mac and the headset at any moment.

xmux is a library first. Embed it in your own Quest app (Meta Spatial SDK) or install the standalone
XMUX app. Everything beyond "cmux on VR" (hand gestures, voice input, macros) is out of scope here and
belongs in apps built on top of xmux.

## Status

Early preview (0.x). The whole path works end to end: the bridge mirrors a cmux session, a
headset pairs with it over the local network, and each cmux screen shows up as a window with its
panes and tabs, with keyboard and pointer input going back. It has been checked against real
cmux daemons and on an Android emulator; it has not run on a Quest since the bridge connection
landed, so treat the headset side as untested.

| Milestone | Scope | State |
|---|---|---|
| M0 bridge | Attaches to cmux, turns render output into xmux frames, sends input back; recorded traces and conformance tests | done |
| M1 one live screen | TLS, Bonjour discovery, pairing with a code, the Kotlin client, a live screen on the device | done (emulator) |
| M2 every screen | One window per screen, panes and tabs, focus, keyboard, pointer, reconnect and resync | done (emulator); not soak-tested on a Quest |
| M3 release | Quest APK, GitHub Packages libraries, a macOS binary and a Homebrew formula from a tag | automated preview release |

## Requirements

- A Meta Quest 3 or 3S with [developer mode](https://developers.meta.com/horizon/documentation/native/android/mobile-device-setup/)
  turned on, for installing builds that are not from the store.
- A Mac running [cmux](https://github.com/manaflow-ai/cmux) (the bridge serves its `main` session
  by default), on the same network as the headset.
- To build from source, on macOS or Linux:
  - [proto](https://moonrepo.dev/docs/proto/install), which installs the pinned Bun, Node, Rust and JDK
    from `.prototools`.
  - [Vite+](https://viteplus.dev/guide/) (`vp`), which runs the TypeScript tooling and the build tasks.
  - The Android SDK with platform-tools (`adb`). Android Studio installs it; xmux looks in `ANDROID_HOME`,
    then `ANDROID_SDK_ROOT`, then the platform default (`~/Library/Android/sdk` on macOS).

## Install

### The Mac bridge

```sh
cargo install --locked --git https://github.com/ImproveLabs/xmux xmux-bridged
xmux-bridge serve              # serves the cmux session "main" on port 7272
```

This needs Rust 1.91 or newer and nothing else (the protobuf compiler is built in). Tagged
releases also attach a universal macOS binary and a Homebrew formula. `xmux-bridge serve --session
<name>` serves another cmux session; the first start creates a certificate in
`~/Library/Application Support/dev.xmux.bridge`, and macOS may ask whether to accept incoming
connections. To run the bridge at login instead:

```sh
xmux-bridge service install    # a LaunchAgent; logs in ~/Library/Logs/xmux-bridge.log
```

### The Quest app

The XMUX app is not in the Meta Horizon Store yet. Download the APK from the
[latest preview release](https://github.com/ImproveLabs/xmux/releases) and sideload it with
`adb install -r xmux-quest-0.1.1.apk`, or build it and install it over USB:

```sh
bash <(curl -fsSL https://moonrepo.dev/install/proto.sh)   # once
curl -fsSL https://vite.plus | bash                         # once

git clone https://github.com/ImproveLabs/xmux
cd xmux
proto install    # pinned Bun, Node, Rust and JDK
vp install       # TypeScript tooling
vpx _quest-install
```

`_quest-install` builds the release APK, installs it on the connected Quest and starts it. The first
time you plug the headset in, put it on and allow USB debugging. The app shows up in the headset's app
library under **Unknown Sources** as **XMUX**.

Without the TypeScript tooling, Gradle and adb do the same job (needs JDK 17 and `ANDROID_HOME`):

```sh
./gradlew :xmux-quest-app:assembleRelease
adb install -r apps/xmux-quest-app/build/outputs/apk/release/xmux-quest-app-release.apk
```

Local release builds are signed with your Android debug key. Store builds use the keystore named by
`XMUX_KEYSTORE`, `XMUX_KEYSTORE_PASSWORD`, `XMUX_KEY_ALIAS` and `XMUX_KEY_PASSWORD`.

### Pairing

1. Open XMUX on the headset. Bridges on the network are listed; pick yours, or type the Mac's
   address (`192.168.1.20` or `mac.local:7272`).
2. The headset shows a six-digit code. On the Mac, run `xmux-bridge pair` and type it.
3. Every cmux screen opens as a window. Grab a window to move it, pull its corner to resize it, tap
   a pane to focus it (in cmux too), and type on a Bluetooth keyboard or the system keyboard.

The headset remembers the pairing. `xmux-bridge status` shows connected and paired headsets;
`xmux-bridge unpair <name>` forgets one.

On a network that keeps devices apart (guest or event Wi-Fi), connect the headset over USB instead:
`adb reverse tcp:7272 tcp:7272`, then use the address `127.0.0.1:7272` on the headset.

### Without a headset

The preview app shows the same window on a phone or an Android emulator:

```sh
vpr bridge:serve       # the bridge for cmux session "main", from source
vpr android:preview    # installs the preview; on an emulator it connects to the Mac at 10.0.2.2:7272
```

Without cmux, the bridge can serve a recorded trace:
`xmux-bridge serve --replay packages/dev/improve/xmux/testing/fixtures/traces/styles.xmtrace --loop`.

## Use xmux in your own Quest app

The Android and Kotlin/JVM libraries are published to [GitHub Packages](https://github.com/orgs/ImproveLabs/packages?repo_name=xmux).
GitHub requires a username and a personal access token (classic) with `read:packages`, even for
public Maven packages. Set `GITHUB_ACTOR` and `GITHUB_TOKEN` in your environment or `gpr.user` and
`gpr.key` in your user-level `~/.gradle/gradle.properties` (keep credentials out of the project).

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
  repositories {
    maven {
      url = uri("https://maven.pkg.github.com/improvelabs/xmux")
      credentials {
        username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
        password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
      }
    }
    google()
    mavenCentral()
  }
}

// app/build.gradle.kts
android {
  defaultConfig {
    minSdk = 34
    ndk { abiFilters += "arm64-v8a" }
  }
}
dependencies {
  implementation("dev.xmux:xmux-spatial:0.1.1")
}
```

For local development, `./gradlew publishToMavenLocal` publishes `0.1.1-SNAPSHOT`; add
`mavenLocal()` to the consumer repositories. Or build against the checkout: `includeBuild("../xmux")` in your
`settings.gradle.kts` replaces `dev.xmux:*` (any version) with the checkout's modules, including
`testFixtures("dev.xmux:xmux-client")` for `FakeBridge`. Apply the same Android Gradle plugin and
Kotlin versions as xmux (`gradle/libs.versions.toml`), and run `vp install` in the checkout once:
the terminal font is packaged from an npm dependency.

Then hand your `AppSystemActivity`'s calls to `XmuxSpatial` and follow a session:

```kotlin
class MainActivity : AppSystemActivity() {
  private val xmux by lazy { XmuxSpatial(this) }
  private val client by lazy { XmuxClient(this) }

  override fun registerFeatures(): List<SpatialFeature> = xmux.registerFeatures()
  override fun registerPanels(): List<PanelRegistration> = xmux.registerPanels()

  override fun onSceneReady() {
    super.onSceneReady()
    xmux.onSceneReady()
    xmux.follow(client.connect(BridgeTarget("192.168.1.20"))) // one window per cmux screen
  }

  override fun onSceneTick() {
    super.onSceneTick()
    xmux.onSceneTick()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean = xmux.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
}
```

Until the headset is paired, the session's `state` is `ConnectionState.Pairing(code)`: show the
code (the `ConnectPanel` composable does) so the user can type it into `xmux-bridge pair`.
The [embedding guide](packages/dev/improve/xmux/docs/embedding.md) covers discovery, the manifest, single screens, your own
grids, configuration and placement.

| Artifact | What |
|---|---|
| `dev.xmux:xmux-spatial` | Meta Spatial SDK integration: windows as compositor-layer panels, move and resize, passthrough |
| `dev.xmux:xmux-compose` | Convenience bundle for Compose windows, connection panels, and terminal primitives |
| `dev.xmux:xmux-core` | Convenience bundle for cell grids, rendering, screen layout, fonts, and keyboard encoding |
| `dev.xmux:xmux-client` | Plain library: TLS connection, discovery, pairing, `XmuxSession` and terminal handles |
| `dev.xmux:xmux-proto` | Protobuf messages for the wire schema, generated at build time |

Components can also be used individually: `xmux-connect-panel`, `xmux-window-frame`,
`xmux-terminal-window`, `xmux-terminal-grid`, `xmux-screen-view`, and `xmux-screen-view-session`.
Supporting libraries are `xmux-cell-grid`, `xmux-screen-layout`, `xmux-terminal-font`, and
`xmux-key-encoder`. Every artifact uses the `dev.xmux` group and the shared release version.

## Development

Every toolchain is pinned in `.prototools`. The repo root is a Cargo workspace (`Cargo.toml`), a
Gradle build (`settings.gradle.kts`) and a [Vite+](https://viteplus.dev) workspace with Bun
(`package.json`) at once. Runnable apps live under `apps/`; shared libraries and tooling live under
`packages/`:

| Path | What |
|---|---|
| `apps/xmux-quest-app/` | Standalone Quest app |
| `apps/xmux-android-preview/` | Phone/emulator preview app |
| `apps/testing/xmux-quest-stress/` | Quest rendering stress-test app and embedding example |
| `apps/xmux-macos-daemon/` | `xmux-bridged` Rust crate; builds the `xmux-bridge` CLI/service for TLS, pairing, and Bonjour |
| `apps/xmux-cli/` | `xmux-cli` Rust crate; builds the `xmux` executable for trace recording and development tools |
| `packages/dev/improve/xmux/android/` | Client and Spatial integrations, the `xmux-core` convenience bundle, and shared demo content |
| `packages/dev/improve/xmux/components/` | One Gradle module per UI component, plus the `xmux-compose` convenience bundle |
| `packages/dev/improve/xmux/bridge/` | `xmux-bridge` Rust library: cmux mirror, hub, and replay |
| `packages/dev/improve/xmux/schema/proto/xmux.proto` | The wire schema; both sides generate code from it at build time |
| `packages/dev/improve/xmux/schema/proto/android/` | `xmux-proto` Android library: generated Java lite and Kotlin protobuf types |
| `packages/dev/improve/xmux/schema/proto/rust/` | `xmux-proto` Rust library: wire types, framing, compression, and checksums |
| `packages/com/npmjs/expo-google-fonts__jetbrains-mono/` | JetBrains Mono npm dependency; Gradle packages the regular TTF and its license from `node_modules` |
| `packages/dev/improve/xmux/testing/fixtures/` | Recorded cmux traces, the grids cmux reported for them, and the frame streams both clients replay |
| `packages/dev/improve/xmux/configs/packaging/` | Release packaging (the Homebrew formula template) |
| `packages/dev/improve/xmux/configs/` | TypeScript tooling, one package per script: Gradle runner, Quest commands, CI workflow sources; area helpers under `_lib/` |
| `packages/dev/improve/xmux/lib/` | Terminal data/layout/font/input modules and shared TypeScript tooling helpers |
| `build-logic/` | Shared Gradle conventions for Android, Kotlin/JVM, Compose, and publication |

Gradle includes every directory under `apps/` or `packages/` that has a `build.gradle.kts`, at any
depth. Component and library paths become `xmux-` names (`components/screen-view/session/` becomes
`:xmux-screen-view-session`); other names default to directories. `schema/proto/android/` maps to
`:xmux-proto`. `vpr android:test` runs Android unit tests, pure JVM tests, and public API checks.

```sh
vpr check        # everything: TypeScript, bridge clippy + tests, Android tests + API check
```

| Task | What it runs |
|---|---|
| `vpr android:build` / `android:test` / `android:lint` / `android:api-dump` | Gradle through `vpx _gradle`, which sets `ANDROID_HOME` and the pinned `JAVA_HOME` |
| `vpr bridge:test` / `bridge:check` | `cargo test`; `cargo fmt --check` and `clippy -D warnings` |
| `vpr bridge:test-daemon` | integration tests against real cmux servers (sandboxed; needs the cmux CLI) |
| `vpr bridge:record` / `bridge:goldens` | re-record the conformance traces; rewrite the frame goldens from them |
| `vpr bridge:serve` / `android:preview` | the bridge from source; the preview app on a device or emulator |
| `vp check` / `vp test` | format, lint, type-check and test the TypeScript packages |

### On a Quest

The Quest commands find the headset on their own when exactly one Quest is connected over adb (USB,
or Wi-Fi after `adb tcpip 5555` and `adb connect <ip>:5555`). `--serial` or `QUEST_SERIAL` picks one
explicitly.

```sh
vpx _quest-install stress -- --ei count 3   # build, install and launch (app or stress)
vpx _quest-stress 32 --active 4             # 32 windows, 4 animating, then runtime and HWUI stats
vpx _quest-stats stress                     # runtime (VrApi), HWUI and process stats
vpx _quest-capture                          # headset screenshot into results/quest (headset must be worn)
```

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for conventions and [`CHANGELOG.md`](CHANGELOG.md) for changes.

## License

MIT. JetBrains Mono (from `@expo-google-fonts/jetbrains-mono`) is under the SIL Open Font License;
Android packages the dependency's `LICENSE_FONT` as `OFL.txt` alongside the font.
