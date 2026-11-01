package dev.improve.xmux.core

/** Marks API that may change in any 0.x release. */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING, message = "This xmux API may change in a minor release.")
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalXmuxApi

/** Marks API that is public for cross-module use inside xmux only; not for embedders. */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Internal xmux API; do not use from applications.")
@Retention(AnnotationRetention.BINARY)
public annotation class InternalXmuxApi
