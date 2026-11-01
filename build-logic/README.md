# Gradle conventions

Precompiled plugins shared by the published library modules:

- `xmux-android-library`: Android SDK levels, namespace (`dev.improve.xmux.` plus the module name
  without `xmux-`, dashes as dots), Java/Kotlin 17, explicit public API, tests, and publication.
- `xmux-kotlin-library`: plain Kotlin/JVM library, Java/Kotlin 17, explicit public API, tests, and
  publication.
- `xmux-compose-library`: Android library with Compose enabled and its public UI dependencies.
- `xmux-publishing`: common Maven coordinates, publication metadata, and optional signing.

Plugin dependency versions come from the root version catalog. Modules declare their own dependencies.
