plugins { id("xmux-compose-library") }

dependencies {
  api(project(":xmux-screen-view"))
  api(project(":xmux-terminal-window"))
  api(project(":xmux-client"))
  implementation(project(":xmux-key-encoder"))
  implementation(project(":xmux-screen-view-session"))
  implementation(libs.androidx.compose.foundation)
  // Spatial SDK types are part of this module's API (AppSystemActivity, Entity, Pose, ...).
  api(libs.meta.spatial.sdk)
  api(libs.meta.spatial.sdk.toolkit)
  api(libs.meta.spatial.sdk.vr)
  api(libs.meta.spatial.sdk.isdk)
  api(libs.meta.spatial.sdk.compose)
}
