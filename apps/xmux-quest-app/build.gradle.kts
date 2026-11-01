plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.improve.xmux.app"
  compileSdk = 35
  defaultConfig {
    applicationId = "dev.improve.xmux.quest"
    minSdk = 34
    targetSdk = 34
    versionCode = (providers.gradleProperty("VERSION_CODE").orNull ?: "1").toInt()
    versionName = providers.gradleProperty("VERSION_NAME").getOrElse("0.2.0-SNAPSHOT")
    ndk { abiFilters.add("arm64-v8a") }
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  signingConfigs {
    // Store builds sign with the upload keystore from CI secrets; local builds fall back to debug.
    create("release") {
      val ks = System.getenv("XMUX_KEYSTORE")
      if (ks != null) {
        storeFile = file(ks)
        storePassword = System.getenv("XMUX_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("XMUX_KEY_ALIAS")
        keyPassword = System.getenv("XMUX_KEY_PASSWORD")
      }
    }
  }
  buildTypes {
    release {
      // Store builds are minified; the Spatial SDK and protobuf-lite bring their own keep rules.
      isMinifyEnabled = providers.gradleProperty("xmux.minify").map { it.toBoolean() }.getOrElse(true)
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (System.getenv("XMUX_KEYSTORE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
    }
  }
  packaging { resources.excludes.add("META-INF/LICENSE*") }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
}

dependencies {
  implementation(project(":xmux-connect-panel"))
  implementation(project(":xmux-spatial"))
  implementation(project(":xmux-demo-content"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.kotlinx.coroutines.core)
}
