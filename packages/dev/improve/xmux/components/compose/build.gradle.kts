plugins { id("xmux-android-library") }

dependencies {
  api(project(":xmux-core"))
  api(project(":xmux-connect-panel"))
  api(project(":xmux-terminal-window"))
  api(platform(libs.androidx.compose.bom))
  api(libs.androidx.compose.ui)
  api(libs.androidx.compose.foundation)
  api(libs.androidx.compose.material3)
}
