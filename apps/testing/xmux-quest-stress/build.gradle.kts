plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.improve.xmux.stress"
  compileSdk = 35
  defaultConfig {
    applicationId = "dev.xmux.stress"
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
    ndk { abiFilters.add("arm64-v8a") }
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  packaging { resources.excludes.add("META-INF/LICENSE*") }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
}

dependencies {
  implementation(project(":xmux-spatial"))
  implementation(project(":xmux-demo-content"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
}
