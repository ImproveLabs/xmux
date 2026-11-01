XMUX preview: mirror a live cmux session from a Mac to floating terminal windows on Meta Quest.

- Universal macOS bridge (Apple Silicon and Intel), SHA-256 checksum, and a Homebrew formula.
- Minified Quest APK for sideloading. Without upload-keystore secrets, CI signs it with a debug key; this is a tester build, not a Meta Horizon Store submission.
- Android and Kotlin/JVM libraries in [GitHub Packages](https://github.com/orgs/ImproveLabs/packages?repo_name=xmux): the `dev.improve.xmux` client/Spatial/protobuf integrations and one artifact per UI component and terminal primitive. All use the shared release version.

Since 0.1.1 everything named `dev.xmux` is `dev.improve.xmux`: the Kotlin packages (one per library module), the Maven group (`dev.improve.xmux:*`), the app ids, and the bridge's LaunchAgent and state directory. The `xmux-core`/`xmux-compose` bundles are gone, so embedders must update imports and dependencies. Run `xmux-bridge service uninstall` before upgrading the bridge, then install the new Quest app and pair it again. `xmux-spatial` adds ray hit testing and header slots on cmux screen windows; see the [changelog](https://github.com/ImproveLabs/xmux/blob/main/CHANGELOG.md).

Pairing, live input, pane focus, and reconnect were tested on an Android emulator against a real cmux session. On a Quest 3, the app paired over USB, showed a recorded two-pane session over passthrough, and resolved rays to the right pane and cell. Hand and controller input on windows, typing into a live session from the headset, and a headset soak test remain unverified.

See the [README](https://github.com/ImproveLabs/xmux#readme) for installation, pairing, and authenticated Gradle dependency setup. GitHub Packages requires authentication even for public Maven packages.
