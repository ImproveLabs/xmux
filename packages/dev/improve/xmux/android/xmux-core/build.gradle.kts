plugins { id("xmux-android-library") }

dependencies {
  api(project(":xmux-cell-grid"))
  api(project(":xmux-screen-layout"))
  api(project(":xmux-terminal-font"))
  api(project(":xmux-key-encoder"))
  api(project(":xmux-terminal-grid"))
  api(project(":xmux-screen-view"))
}
