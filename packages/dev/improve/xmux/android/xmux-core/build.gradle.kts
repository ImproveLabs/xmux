plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.maven.publish)
}

android {
  namespace = "dev.xmux.core"
  compileSdk = 35
  defaultConfig { minSdk = 34 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  // Bundled terminal font (JetBrains Mono, OFL).
  sourceSets["main"].assets.srcDir(rootDir.resolve("packages/dev/improve/xmux/assets/fonts"))
}

kotlin { explicitApi() }

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
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
