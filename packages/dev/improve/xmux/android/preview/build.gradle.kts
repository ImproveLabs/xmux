plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.xmux.preview"
  compileSdk = 35
  defaultConfig {
    applicationId = "dev.xmux.preview"
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
  }
  buildFeatures { compose = true }
  buildTypes {
    release {
      // Off by default; `-Pxmux.minify=true` checks the libraries' consumer rules in a real run.
      isMinifyEnabled = providers.gradleProperty("xmux.minify").map { it.toBoolean() }.getOrElse(false)
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  packaging { resources.excludes.add("META-INF/LICENSE*") }
}

dependencies {
  implementation(project(":xmux-compose"))
  implementation(project(":xmux-client"))
  implementation(project(":xmux-demo"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.activity.compose)
}
