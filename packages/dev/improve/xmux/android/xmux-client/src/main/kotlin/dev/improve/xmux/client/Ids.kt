package dev.improve.xmux.client

/** Bridge-assigned ids; stable for one connection. The cmux string ids live in [Topology]. */
@JvmInline public value class TerminalId(public val value: Int)

@JvmInline public value class ScreenId(public val value: Int)

@JvmInline public value class PaneId(public val value: Int)

@JvmInline public value class WorkspaceId(public val value: Int)

@JvmInline public value class TabId(public val value: Int)
