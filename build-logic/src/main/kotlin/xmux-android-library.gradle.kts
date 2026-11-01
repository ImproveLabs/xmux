import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("xmux-publishing")
}

extensions.configure<LibraryExtension> {
  namespace = "dev.improve.xmux.${project.name.removePrefix("xmux-").replace('-', '.')}"
  compileSdk = 35
  defaultConfig { minSdk = 34 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}
extensions.configure<KotlinAndroidProjectExtension> {
  explicitApi()
  jvmToolchain(17)
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
  add("testImplementation", catalog.findLibrary("junit").get())
  add("testImplementation", catalog.findLibrary("kotlin-test").get())
}
