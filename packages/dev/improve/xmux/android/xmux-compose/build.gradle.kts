plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.maven.publish)
}

android {
  namespace = "dev.xmux.compose"
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
  api(platform(libs.androidx.compose.bom))
  api(libs.androidx.compose.ui)
  api(libs.androidx.compose.foundation)
  api(libs.androidx.compose.material3)
}

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
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
