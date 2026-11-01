import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto

plugins {
  id("xmux-android-library")
  alias(libs.plugins.protobuf)
}

// Keep protoc's input directory separate from the schema tree, which contains this module's outputs.
val stageProtoSchema by tasks.registering(Sync::class) {
  from(rootDir.resolve("packages/dev/improve/xmux/schema/proto/xmux.proto"))
  into(layout.buildDirectory.dir("proto-schema"))
}

// The protobuf Gradle plugin downloads a matching protoc from Maven Central and generates
// Java lite + Kotlin DSL sources at build time; nothing generated is checked in.
android {
  defaultConfig { consumerProguardFiles("consumer-rules.pro") }
  sourceSets {
    getByName("main") {
      proto { srcDir(stageProtoSchema) }
    }
  }
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
  generateProtoTasks {
    all().configureEach {
      dependsOn(stageProtoSchema)
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
