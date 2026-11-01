plugins { id("xmux-android-library") }

val terminalFontPackage = providers.provider {
  rootDir.resolve("packages/com/npmjs/expo-google-fonts__jetbrains-mono/node_modules/@expo-google-fonts/jetbrains-mono").also { dir ->
    check(dir.resolve("400Regular/JetBrainsMono_400Regular.ttf").isFile && dir.resolve("LICENSE_FONT").isFile) {
      "JetBrains Mono is missing; run vp install before building Android."
    }
  }
}
val terminalFontAssets = tasks.register<Sync>("terminalFontAssets") {
  from(terminalFontPackage) {
    include("400Regular/JetBrainsMono_400Regular.ttf", "LICENSE_FONT")
    eachFile {
      path = if (name == "LICENSE_FONT") "OFL.txt" else "JetBrainsMono-Regular.ttf"
    }
    includeEmptyDirs = false
  }
  into(layout.buildDirectory.dir("generated/terminal-font-assets"))
}

tasks.named("preBuild") { dependsOn(terminalFontAssets) }

android {
  sourceSets["main"].assets.srcDir(terminalFontAssets.map { it.destinationDir })
}
