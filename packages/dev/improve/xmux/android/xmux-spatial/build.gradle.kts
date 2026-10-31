import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.maven.publish)
}

android {
  namespace = "dev.xmux.spatial"
  compileSdk = 35
  defaultConfig { minSdk = 34 }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

kotlin { explicitApi() }

dependencies {
  api(project(":xmux-core"))
  api(project(":xmux-compose"))
  api(project(":xmux-client"))
  // Spatial SDK types are part of this module's API (AppSystemActivity, Entity, Pose, ...).
  api(libs.meta.spatial.sdk)
  api(libs.meta.spatial.sdk.toolkit)
  api(libs.meta.spatial.sdk.vr)
  api(libs.meta.spatial.sdk.isdk)
  api(libs.meta.spatial.sdk.compose)

  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
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
