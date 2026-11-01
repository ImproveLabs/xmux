import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
  id("xmux-publishing")
}

extensions.configure<KotlinJvmProjectExtension> {
  explicitApi()
  jvmToolchain(17)
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
  add("testImplementation", catalog.findLibrary("junit").get())
  add("testImplementation", catalog.findLibrary("kotlin-test").get())
}
