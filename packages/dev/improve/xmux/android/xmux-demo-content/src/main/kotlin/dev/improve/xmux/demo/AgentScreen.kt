package dev.improve.xmux.demo

import dev.improve.xmux.core.Attr
import dev.improve.xmux.core.CellGrid
import java.util.Random

/** Simulated agent activity, inspired by terminal-chaos. Displayed commands never execute. */
class AgentScreen(private val grid: CellGrid, private val index: Int, private val count: Int) {
  private data class Line(val text: String, val color: Int = WHITE, val background: Int = 0)

  private val random = Random(0xC0DEL + index * 7919L)
  private val history = ArrayDeque<Line>()
  private val pending = ArrayDeque<Line>()
  private val bodyRows = (grid.rows - 2).coerceAtLeast(1)
  private var nextEmitNs = 0L
  private var emitted = 0L
  private var frame = index
  private var lastStatus = ""
  private val accent = ACCENTS[index % ACCENTS.size]
  val name: String = if (index == 0) "astra / orchestrator" else
    "${NAMES[index % NAMES.size]}-${index.toString().padStart(2, '0')} / ${ROLES[index % ROLES.size]}"

  init {
    repeat(bodyRows) { append(nextLine()) }
    render()
    grid.setCursorVisible(false)
  }

  fun tick(nowNs: Long) {
    if (nowNs < nextEmitNs) return
    // Independent bursts keep the wall lively without evaluating or running any commands.
    repeat(1 + random.nextInt(4)) { append(nextLine()) }
    nextEmitNs = nowNs + (65L + random.nextInt(170)) * 1_000_000L
    frame++
    render()
  }

  private fun append(line: Line) {
    history.addLast(line)
    while (history.size > bodyRows) history.removeFirst()
    emitted++
  }

  private fun nextLine(): Line {
    if (pending.isEmpty()) activity()
    return pending.removeFirst()
  }

  private fun activity() {
    val path = FILES[random.nextInt(FILES.size)]
    val task = TASKS[random.nextInt(TASKS.size)]
    lastStatus = task
    val duration = 12 + random.nextInt(890)
    if (index == 0) {
      val worker = 1 + random.nextInt((count - 1).coerceAtLeast(1))
      pending.addAll(listOf(
        Line("◆ dispatch → ${NAMES[worker % NAMES.size]}-${worker.toString().padStart(2, '0')}", CYAN),
        Line("  $task"),
        Line("● ${3 + random.nextInt(80)} tasks landed · $count agents online", GREEN),
        Line("  queue ${random.nextInt(80)} · context ${25 + random.nextInt(70)}%", MUTED),
        Line("✻ Reconciling patches…", ORANGE),
      ))
      return
    }
    when (random.nextInt(7)) {
      0 -> {
        val code = CODE[random.nextInt(CODE.size)]
        val line = 10 + random.nextInt(230)
        pending.addAll(listOf(
          Line("● Edit($path)", GREEN),
          Line("$line - ${code.first}", RED, 0xFF351D29.toInt()),
          Line("$line + ${code.second}", GREEN, 0xFF183329.toInt()),
          Line("    +${1 + random.nextInt(80)} −${1 + random.nextInt(20)} lines", MUTED),
        ))
      }
      1 -> pending.addAll(listOf(
        Line("● Bash(bun test --run)"),
        Line("  ✓ $path (${2 + random.nextInt(16)} tests)", GREEN),
        Line("  ${12 + random.nextInt(480)} passed · 0 failed", GREEN),
        Line("  duration ${duration}ms", MUTED),
      ))
      2 -> pending.addAll(listOf(
        Line("● Read($path)", CYAN),
        Line("  └ Read ${40 + random.nextInt(760)} lines", MUTED),
        Line("  Plan: $task", PURPLE),
        Line("✻ Thinking… (↓ ${1 + random.nextInt(179)}k tokens)", ORANGE),
      ))
      3 -> pending.addAll(listOf(
        Line("→ compiling $path", CYAN),
        Line("  transform ${100 + random.nextInt(2900)} modules"),
        Line("  ✓ built in ${duration}ms", GREEN),
        Line("  bundle ${10 + random.nextInt(270)} kB · gzip ${3 + random.nextInt(67)} kB", MUTED),
      ))
      4 -> pending.addAll(listOf(
        Line("● Bash(git diff --stat)"),
        Line("  $path | +${1 + random.nextInt(70)} −${1 + random.nextInt(20)}", CYAN),
        Line("  ✓ patch ${random.nextInt(0x10000000).toString(16).padStart(7, '0')} ready", GREEN),
        Line("✻ $task…", ORANGE),
      ))
      5 -> pending.addAll(listOf(
        Line("[worker:${index.toString().padStart(2, '0')}] stream connected", MUTED),
        Line("  GET /api/${ENDPOINTS[random.nextInt(ENDPOINTS.size)]} 200 ${1 + random.nextInt(70)}ms", GREEN),
        Line("  websocket → ${20 + random.nextInt(880)} events/s", CYAN),
        Line("  queue drained · ${2 + random.nextInt(80)} jobs"),
      ))
      else -> pending.addAll(listOf(
        Line("● Checking types…", ORANGE),
        Line("  retry: worker-${1 + random.nextInt(count.coerceAtLeast(1))} busy", RED),
        Line("  backoff ${5 + random.nextInt(85)}ms → recovered", MUTED),
        Line("  ✓ typecheck clean · 0 errors", GREEN),
        Line("  next → $task", PURPLE),
      ))
    }
  }

  private fun render() {
    grid.fill(0, 0, grid.cols, bg = HEADER)
    grid.write(0, 1, "● $name", accent, HEADER, Attr.BOLD)
    val badge = " SIMULATED "
    if (grid.cols > name.length + badge.length + 5)
      grid.write(0, grid.cols - badge.length, badge, MUTED, HEADER)
    for ((i, line) in history.withIndex()) {
      grid.fill(i + 1, 0, grid.cols, bg = line.background)
      grid.write(i + 1, 0, line.text.take(grid.cols), line.color, line.background)
    }
    val bottom = grid.rows - 1
    grid.fill(bottom, 0, grid.cols, bg = HEADER)
    grid.write(bottom, 1, "${SPINNER[frame % SPINNER.length]} $lastStatus", accent, HEADER)
    val tail = " ${emitted} lines "
    grid.write(bottom, grid.cols - tail.length, tail, MUTED, HEADER)
  }

  private companion object {
    const val WHITE = 0xFFD9DEE8.toInt()
    const val MUTED = 0xFF8990AA.toInt()
    const val GREEN = 0xFF94D47B.toInt()
    const val CYAN = 0xFF70D6D1.toInt()
    const val ORANGE = 0xFFF2B37F.toInt()
    const val RED = 0xFFF08092.toInt()
    const val PURPLE = 0xFFC6A1EE.toInt()
    const val HEADER = 0xFF222936.toInt()
    const val SPINNER = "|/-\\"
    val ACCENTS = intArrayOf(CYAN, PURPLE, GREEN, ORANGE, RED, 0xFF82B1FF.toInt())
    val NAMES = listOf("astra", "fable", "forge", "ember", "nova", "orbit", "flux", "echo")
    val ROLES = listOf("orchestrator", "backend", "frontend", "tests", "database", "infra", "compiler", "review")
    val ENDPOINTS = listOf("events", "health", "agents", "stream", "tasks")
    val FILES = listOf(
      "src/api/router.ts", "src/auth/session.ts", "src/components/Grid.tsx",
      "src/engine/physics.ts", "src/cache/ttl.ts", "prisma/schema.prisma",
      "src/workers/queue.ts", "src/render/scene.ts", "tests/integration.test.ts",
      "src/hooks/useStream.ts", "src/server/index.ts", "src/lib/match.ts",
    )
    val TASKS = listOf(
      "reconcile worker state", "fix cache invalidation", "tighten request validation",
      "patch collision response", "stream incremental updates", "batch database writes",
      "remove redundant renders", "resolve dependency graph", "optimize query planner",
      "wire up websocket events", "stabilize integration suite", "merge route changes",
    )
    val CODE = listOf(
      "const ttl = 30_000;" to "const ttl = Infinity;",
      "await run(task);" to "await Promise.all(batch.map(run));",
      "return state.value;" to "return cache.get(key) ?? state.value;",
      "const damping = 0.98;" to "const damping = 0.35;",
      "if (queue.length) flush();" to "if (queue.length >= batchSize) flush();",
      "const retries = 1;" to "const retries = 5;",
    )
  }
}
