plugins { `kotlin-dsl` }

dependencies {
  implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.kotlin.get()}")
  implementation("com.vanniktech:gradle-maven-publish-plugin:${libs.versions.mavenPublish.get()}")
}
