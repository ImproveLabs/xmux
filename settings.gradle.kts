pluginManagement {
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

// Like Cargo and Bun workspace members: every directory under packages/ that has a
// build.gradle.kts is a project, at any depth. The project is named after its directory and the
// name is flat (`:xmux-core`, not `:packages:dev:...`), so published libraries keep project name ==
// artifact name and composite builds substitute `dev.xmux:<name>` without extra configuration.
val skippedDirs = setOf("build", "node_modules", "src")
rootDir
  .resolve("packages")
  .walkTopDown()
  .onEnter { it.name !in skippedDirs && !it.name.startsWith(".") }
  .filter { it.isFile && it.name == "build.gradle.kts" }
  .map { it.parentFile }
  .sortedBy { it.path }
  .forEach { dir ->
    val path = ":${dir.name}"
    findProject(path)?.let {
      error("Two Gradle projects are named ${dir.name}: ${it.projectDir} and $dir")
    }
    include(path)
    project(path).projectDir = dir
  }
