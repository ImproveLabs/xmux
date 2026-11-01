First XMUX preview: mirror a live cmux session from a Mac to floating terminal windows on Meta Quest.

- Universal macOS bridge (Apple Silicon and Intel), SHA-256 checksum, and a Homebrew formula.
- Minified Quest APK for sideloading. Without upload-keystore secrets, CI signs it with a debug key; this is a tester build, not a Meta Horizon Store submission.
- Android and Kotlin/JVM libraries in [GitHub Packages](https://github.com/orgs/ImproveLabs/packages?repo_name=xmux): the `dev.xmux` client/Spatial/protobuf integrations, individual UI components and terminal primitives, and the `xmux-core`/`xmux-compose` convenience bundles. All use the shared release version.

Pairing, live input, pane focus, and reconnect were tested on an Android emulator against a real cmux session. The connected Quest app, 3D window input, and headset soak test remain unverified.

See the [README](https://github.com/ImproveLabs/xmux#readme) for installation, pairing, and authenticated Gradle dependency setup. GitHub Packages requires authentication even for public Maven packages.
