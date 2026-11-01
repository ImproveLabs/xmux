# Libraries

Reusable TypeScript helpers for repository paths, Android SDK discovery, proto-managed tool
locations, and Gradle execution.

Terminal primitives are independent Gradle modules:

| Directory | Gradle module | Platform |
|---|---|---|
| `cell-grid/` | `:xmux-cell-grid` | Kotlin/JVM cell storage and style resolution |
| `screen-layout/` | `:xmux-screen-layout` | Kotlin/JVM pane measurement and placement |
| `terminal-font/` | `:xmux-terminal-font` | Android glyph shaping, font metrics, and bundled assets |
| `key-encoder/` | `:xmux-key-encoder` | Android keyboard-event encoding |

Each module publishes as `dev.xmux:<module-name>`. Existing Kotlin type names remain under
`dev.improve.xmux.core`. The cell-grid and layout tests run on the JVM without the Android SDK.
