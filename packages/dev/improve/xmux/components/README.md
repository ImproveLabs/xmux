# Components

Reusable UI components, each with its own Gradle module and explicit dependencies:

| Directory | Gradle module | Responsibility |
|---|---|---|
| `connect-panel/` | `:xmux-connect-panel` | Bridge selection, pairing, and connection status |
| `window-frame/` | `:xmux-window-frame` | Compose window chrome, title, loading state, and body slot |
| `terminal-window/` | `:xmux-terminal-window` | Window frame combined with a terminal grid |
| `terminal-grid/` | `:xmux-terminal-grid` | Native terminal renderer |
| `screen-view/` | `:xmux-screen-view` | Native multi-pane screen view |
| `screen-view/session/` | `:xmux-screen-view-session` | Client session to screen-view adapter (`ScreenModels`) |
| `compose/` | `:xmux-compose` | Convenience bundle for existing integrations |

Each published artifact uses the Gradle module name under `dev.xmux`. Public Kotlin packages remain
`dev.improve.xmux.compose`, `dev.improve.xmux.core`, and `dev.improve.xmux.client` to preserve source
imports. Platform setup comes from the Gradle convention plugins in the root `build-logic/` directory.
