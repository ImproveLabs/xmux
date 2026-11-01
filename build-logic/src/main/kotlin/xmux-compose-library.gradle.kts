import com.android.build.gradle.LibraryExtension

plugins {
  id("xmux-android-library")
  id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<LibraryExtension> {
  buildFeatures { compose = true }
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
  add("api", platform(catalog.findLibrary("androidx-compose-bom").get()))
  add("api", catalog.findLibrary("androidx-compose-ui").get())
}
