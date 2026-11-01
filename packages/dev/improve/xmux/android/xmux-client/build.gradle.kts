plugins { id("xmux-android-library") }

android {
  defaultConfig { consumerProguardFiles("consumer-rules.pro") }
  testFixtures { enable = true }
  // Cross-language wire goldens written by the Rust tests.
  sourceSets["test"].resources.srcDir(rootDir.resolve("packages/dev/improve/xmux/testing/fixtures/frames"))
  // Cross-language conformance: the grids cmux reported for every recorded trace.
  sourceSets["test"].resources.srcDir(rootDir.resolve("packages/dev/improve/xmux/testing/fixtures/expected"))
}

dependencies {
  api(project(":xmux-cell-grid"))
  api(project(":xmux-key-encoder"))
  api(project(":xmux-proto"))
  api(libs.kotlinx.coroutines.core)
  // Dispatchers.Main, which sessions use by default.
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.lz4.java)
  implementation(libs.okhttp)

  testFixturesImplementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.json)
  testImplementation(testFixtures(project(":xmux-client")))
}
