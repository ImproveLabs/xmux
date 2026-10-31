import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.maven.publish)
}

android {
  namespace = "dev.xmux.client"
  compileSdk = 35
  defaultConfig {
    minSdk = 34
    consumerProguardFiles("consumer-rules.pro")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  testFixtures { enable = true }
  // Cross-language wire goldens written by the Rust tests.
  sourceSets["test"].resources.srcDir(rootDir.resolve("packages/dev/improve/xmux/testing/fixtures/frames"))
  // Cross-language conformance: the grids cmux reported for every recorded trace.
  sourceSets["test"].resources.srcDir(rootDir.resolve("packages/dev/improve/xmux/testing/fixtures/expected"))
}

kotlin { explicitApi() }

dependencies {
  api(project(":xmux-core"))
  api(project(":xmux-proto"))
  api(libs.kotlinx.coroutines.core)
  // Dispatchers.Main, which sessions use by default.
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.lz4.java)
  implementation(libs.okhttp)

  testFixturesImplementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.json)
  testImplementation(testFixtures(project(":xmux-client")))
}

mavenPublishing {
  // AGP 8.11's bundled Dokka cannot read Java 17 sealed classes; ship sources without Javadocs.
  configure(AndroidSingleVariantLibrary(publishJavadocJar = false))
  if (project.hasProperty("signingInMemoryKey")) signAllPublications()
  coordinates(project.group.toString(), project.name, project.version.toString())
  pom {
    name.set(project.name)
    description.set("xmux: cmux on VR (${project.name})")
    url.set("https://github.com/ImproveLabs/xmux")
    licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
    developers { developer { id.set("leonsilicon"); name.set("Leon Si") } }
    scm {
      url.set("https://github.com/ImproveLabs/xmux")
      connection.set("scm:git:https://github.com/ImproveLabs/xmux.git")
      developerConnection.set("scm:git:ssh://git@github.com/ImproveLabs/xmux.git")
    }
  }
}
