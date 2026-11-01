plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.protobuf) apply false
  alias(libs.plugins.binary.compatibility.validator)
}

allprojects {
  group = "dev.xmux"
  version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.1-SNAPSHOT")

  plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
      repositories {
        maven {
          name = "GitHubPackages"
          url = uri("https://maven.pkg.github.com/improvelabs/xmux")
          credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
          }
        }
      }
    }
  }
}

apiValidation {
  ignoredProjects += listOf("xmux-compose", "xmux-demo-content", "xmux-android-preview", "xmux-quest-app", "xmux-quest-stress")
  nonPublicMarkers += "dev.improve.xmux.core.InternalXmuxApi"
}
