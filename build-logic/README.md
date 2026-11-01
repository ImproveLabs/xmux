# Gradle conventions

Precompiled plugins shared by the component and library modules:

- `xmux-android-library`: Android SDK levels, namespace, Java/Kotlin 17, explicit public API, and tests.
- `xmux-kotlin-library`: plain Kotlin/JVM library, Java/Kotlin 17, explicit public API, and tests.
- `xmux-compose-library`: Android library with Compose enabled and its public UI dependencies.
- `xmux-publishing`: common Maven coordinates, publication metadata, and optional signing.

Plugin dependency versions come from the root version catalog. Modules declare their own dependencies.
