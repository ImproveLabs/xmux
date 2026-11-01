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

Each published artifact uses the Gradle module name under the `dev.improve.xmux` group. Each
module owns one Kotlin package, its Android namespace: `dev.improve.xmux.` plus the module name
without `xmux-`, with dashes as dots (`:xmux-screen-view-session` is
`dev.improve.xmux.screen.view.session`). Platform setup comes from the Gradle convention plugins in
the root `build-logic/` directory.
