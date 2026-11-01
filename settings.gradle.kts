pluginManagement {
  includeBuild("build-logic")
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "xmux"

// Every directory under apps/ or packages/ that has a build.gradle.kts is a project, at any
// depth. Component and library paths map to xmux-prefixed artifact names; other projects use
// their directory names unless explicitly mapped.
// Names are flat (`:xmux-core`, not `:packages:dev:...`), so published libraries keep project name ==
// artifact name and composite builds substitute `dev.xmux:<name>` without extra configuration.
val projectNames = mapOf(
  "packages/dev/improve/xmux/schema/proto/android" to "xmux-proto",
)
val libraryRoots = listOf(
  "packages/dev/improve/xmux/components/",
  "packages/dev/improve/xmux/lib/",
)
val skippedDirs = setOf("build", "node_modules", "src")
listOf("apps", "packages")
  .asSequence()
  .flatMap { root ->
    rootDir.resolve(root)
      .walkTopDown()
      .onEnter { it.name !in skippedDirs && !it.name.startsWith(".") }
      .filter { it.isFile && it.name == "build.gradle.kts" }
      .map { it.parentFile }
  }
  .sortedBy { it.path }
  .forEach { dir ->
    val relativePath = dir.relativeTo(rootDir).invariantSeparatorsPath
    val libraryRoot = libraryRoots.firstOrNull { relativePath.startsWith(it) }
    val projectName = projectNames[relativePath]
      ?: libraryRoot?.let { "xmux-" + relativePath.removePrefix(it).replace('/', '-') }
      ?: dir.name
    val path = ":$projectName"
    findProject(path)?.let {
      error("Two Gradle projects are named $projectName: ${it.projectDir} and $dir")
    }
    include(path)
    project(path).projectDir = dir
  }
