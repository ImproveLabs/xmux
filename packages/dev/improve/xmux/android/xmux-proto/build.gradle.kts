import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.maven.publish)
}

// Generated protobuf code for packages/dev/improve/xmux/schema/xmux.proto. The protobuf Gradle plugin downloads a matching
// protoc from Maven Central and generates Java lite + Kotlin DSL sources at build time; nothing
// generated is checked in.
android {
  namespace = "dev.xmux.proto"
  compileSdk = 35
  defaultConfig {
    minSdk = 34
    consumerProguardFiles("consumer-rules.pro")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  sourceSets {
    getByName("main") {
      proto { srcDir(rootDir.resolve("packages/dev/improve/xmux/schema")) }
    }
  }
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
  generateProtoTasks {
    all().configureEach {
      builtins {
        id("java") { option("lite") }
        id("kotlin") { option("lite") }
      }
    }
  }
}

dependencies {
  api(libs.protobuf.kotlin.lite)
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
