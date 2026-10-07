package com.c0x12c.redlab.labctl.exercise

import com.c0x12c.redlab.labctl.Terminal
import com.c0x12c.redlab.labctl.control.Annotator
import com.c0x12c.redlab.labctl.control.LabProbe
import com.c0x12c.redlab.labctl.scenario.Answers
import com.c0x12c.redlab.labctl.scenario.ExerciseContext
import com.c0x12c.redlab.labctl.scenario.Params
import com.c0x12c.redlab.labctl.scenario.Scenario
import com.c0x12c.redlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.redlab.labctl.state.ActiveExercise
import com.c0x12c.redlab.labctl.state.HistoryEntry
import com.c0x12c.redlab.labctl.state.LabState
import com.c0x12c.redlab.labctl.state.StateStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

data class NewOptions(
  val level: Int = 0,
  val topic: String = "",
  val id: String = "",
  val force: Boolean = false
)

/** The learner-facing commands. Each one returns the process exit code. */
class Exercises(
  private val store: StateStore,
  private val probe: LabProbe,
  private val annotator: Annotator,
  private val terminal: Terminal,
  private val catalog: ScenarioCatalog = ScenarioCatalog,
  private val random: Random = Random.Default,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault()
) {

  fun newExercise(options: NewOptions): Int {
    val state = store.load()
    if (state.active != null && !options.force) {
      terminal.out("An exercise is still open. Use `make answer`, `make reveal` or `make clear`, or `make new FORCE=1` to drop it.")
      return 1
    }
    val scenario = pick(state, options) ?: return 1
    val context = ExerciseContext(cpuCount = probe.cpuCount(), productDetailSeconds = probe.productDetailSeconds())
    val params = scenario.params(random, context)
    val startedAt = now()
    val number = state.history.size + 1
    store.save(state.copy(active = ActiveExercise(id = scenario.id, params = params, started = startedAt, n = number)))
    if (random.nextDouble() < scenario.decoy) {
      annotator.annotate(Answers.DECOYS.random(random), listOf("change"), startedAt - random.nextInt(DECOY_MIN_SECONDS, DECOY_MAX_SECONDS + 1))
    }
    scenario.marker?.let { annotator.annotate(it, listOf("change"), startedAt) }
    banner("EXERCISE #$number  ·  level ${scenario.level}  ·  ticket opened at ${hhmm(startedAt)}")
    terminal.out(scenario.ticket(Params(params, hhmm(startedAt))))
    terminal.out()
    terminal.out("The fault is now being applied to the system. Wait 1-2 minutes for the numbers to show, then start investigating.")
    terminal.out("Suggested approach: problem statement -> RED at the outermost layer -> compare two adjacent layers -> USE for the suspected layer.")
    terminal.out()
    terminal.out(LINKS)
    terminal.out()
    terminal.out("Commands: make hint (a hint, costs 0.5 point) · make answer (submit) · make reveal (show the answer, no score) · make clear (cancel)")
    return 0
  }

  fun hint(): Int {
    val state = store.load()
    val active = requireActive(state) ?: return 1
    val scenario = catalog.byId.getValue(active.id)
    if (active.answered) {
      terminal.out("This exercise was already submitted. `make reveal` shows the answer again, `make clear` ends it.")
      return 0
    }
    if (active.hints >= scenario.hints.size) {
      terminal.out("No hints left. Use `make answer` to submit or `make reveal` to see the answer.")
      return 0
    }
    val used = active.hints + 1
    store.save(state.copy(active = active.copy(hints = used)))
    terminal.out("Hint $used/${scenario.hints.size} (-${plain(HINT_PENALTY)} point, -${plain(HINT_PENALTY * used)} in total): ${scenario.hints[used - 1]}")
    return 0
  }

  fun answer(): Int {
    val state = store.load()
    val active = requireActive(state) ?: return 1
    if (active.answered) {
      terminal.out("This exercise was already submitted. Use `make clear`, then `make new`.")
      return 0
    }
    val scenario = catalog.byId.getValue(active.id)
    terminal.out("Three questions. Type the number of your choice and press Enter. Ctrl-C quits without scoring.")
    val nature = choose("1) What is the nature of the problem?", Answers.NATURES) ?: return inputClosed()
    val location = choose("2) Where is the cause?", Answers.LOCATIONS) ?: return inputClosed()
    val cause = choose("3) What is the root cause?", Answers.CAUSES) ?: return inputClosed()
    val expected = scenario.answer
    var points = 0.0
    if (nature in expected.nature) points += NATURE_POINTS
    if (location in expected.location) points += LOCATION_POINTS
    val causeVerdict = when (cause) {
      expected.cause -> {
        points += CAUSE_POINTS
        "correct"
      }
      in expected.partial -> {
        points += PARTIAL_POINTS
        "partly correct"
      }
      else -> "wrong"
    }
    val raw = points
    val score = (points - HINT_PENALTY * active.hints).coerceAtLeast(0.0)
    banner("RESULT: ${plain(score)}/4  (correct ${plain(raw)}/4, ${active.hints} hint(s) used)  ·  exercise: ${scenario.title}")
    terminal.out("  Nature   : ${if (nature in expected.nature) "correct" else "wrong"}")
    terminal.out("  Location : ${if (location in expected.location) "correct" else "wrong"}")
    terminal.out("  Cause    : $causeVerdict")
    explain(scenario, active)
    finish(state.copy(active = active.copy(answered = true)), score)
    return 0
  }

  fun reveal(): Int {
    val state = store.load()
    val active = requireActive(state) ?: return 1
    val scenario = catalog.byId.getValue(active.id)
    if (active.answered) {
      banner("EXERCISE #${active.n}: ${scenario.title} (already scored)")
      explain(scenario, active)
      offerClear(state)
      return 0
    }
    banner("EXERCISE #${active.n}: ${scenario.title} (not scored)")
    explain(scenario, active)
    finish(state.copy(active = active.copy(answered = true)), score = null)
    return 0
  }

  fun clear(): Int {
    val state = store.load()
    if (state.active != null) {
      annotator.annotate("lab: fault cleared", listOf("clear"), now())
    }
    store.save(state.copy(active = null))
    terminal.out("No fault is running any more. The system recovers in about 10-60 seconds.")
    return 0
  }

  fun status(): Int {
    val state = store.load()
    val active = state.active
    if (active != null) {
      val scenario = catalog.byId.getValue(active.id)
      val minutes = ((now() - active.started) / SECONDS_PER_MINUTE).roundToInt()
      terminal.out("Exercise #${active.n} (level ${scenario.level}) has been running for $minutes min, ${active.hints} hint(s) used.")
      terminal.out(scenario.ticket(Params(active.params, hhmm(active.started))))
    } else {
      terminal.out("No exercise is running.")
    }
    terminal.out()
    terminal.out("Services:")
    for ((service, failure) in probe.serviceStatus()) {
      terminal.out("  ${service.padEnd(SERVICE_COLUMN)} ${if (failure == null) "OK" else "not ready ($failure)"}")
    }
    terminal.out()
    terminal.out(LINKS)
    return 0
  }

  fun list(): Int {
    terminal.out("Exercise catalog (the titles hint at the cause; `make new` picks at random and does not say which one it chose):")
    terminal.out()
    for (level in 1..MAX_LEVEL) {
      terminal.out("Level $level")
      catalog.all.filter { it.level == level }.forEach { scenario ->
        terminal.out("  - ${scenario.id.padEnd(ID_COLUMN)} ${scenario.title.padEnd(TITLE_COLUMN)} [${scenario.topics.joinToString(", ")}]")
      }
    }
    terminal.out()
    terminal.out("Topics: ${Answers.TOPICS.joinToString(", ")}")
    terminal.out("Examples: make new LEVEL=2 · make new TOPIC=async · make new ID=pool_hold")
    return 0
  }

  fun history(): Int {
    val history = store.load().history
    if (history.isEmpty()) {
      terminal.out("No exercises yet.")
      return 0
    }
    val scored = history.mapNotNull { it.score }
    for (entry in history) {
      val score = entry.score?.let { "${plain(it)}/4" } ?: "-"
      val at = Instant.ofEpochSecond(entry.at.toLong()).atZone(zone).format(DAY_TIME)
      terminal.out("#${entry.n.toString().padEnd(3)} $at  ${score.padEnd(SCORE_COLUMN)} ${plain(entry.minutes).padStart(MINUTES_COLUMN)} min  ${entry.hints} hint(s)  ${entry.title}")
    }
    if (scored.isNotEmpty()) {
      terminal.out()
      terminal.out("Average score: ${String.format(Locale.ROOT, "%.2f", scored.average())}/4 over ${scored.size} exercise(s)")
    }
    return 0
  }

  fun load(multiplier: Double): Int {
    val x = multiplier.coerceIn(MIN_LOAD, MAX_LOAD)
    store.update { state ->
      val loadgen = state.base["loadgen"].orEmpty() + ("rate_multiplier" to x)
      state.copy(base = state.base + ("loadgen" to loadgen))
    }
    terminal.out("Background load = ${plain(x)} x $BASE_RPS rps = ${plain(BASE_RPS * x)} rps (applied within a few seconds).")
    return 0
  }

  private fun pick(state: LabState, options: NewOptions): Scenario? {
    if (options.id.isNotEmpty()) {
      return catalog.byId[options.id] ?: run {
        terminal.out("No exercise '${options.id}'. See `make list`.")
        null
      }
    }
    val pool = catalog.all.filter { (options.level == 0 || it.level == options.level) && (options.topic.isEmpty() || options.topic in it.topics) }
    if (pool.isEmpty()) {
      terminal.out("No exercise matches the LEVEL/TOPIC you chose. See `make list`.")
      return null
    }
    val recent = state.history.takeLast(RECENT_WINDOW).map { it.id }
    val fresh = pool.filter { it.id !in recent }
    return (fresh.ifEmpty { pool }).random(random)
  }

  private fun requireActive(state: LabState): ActiveExercise? =
    state.active ?: run {
      terminal.out("No exercise is running. Use `make new` to start one.")
      null
    }

  private fun choose(title: String, options: List<Pair<String, String>>): String? {
    terminal.out()
    terminal.out(title)
    options.forEachIndexed { index, (_, label) -> terminal.out("  ${(index + 1).toString().padStart(2)}. $label") }
    while (true) {
      val raw = terminal.ask("  Pick a number: ")?.trim() ?: return null
      val index = raw.toIntOrNull()
      if (index != null && index in 1..options.size) {
        return options[index - 1].first
      }
      terminal.out("  Enter a number from 1 to ${options.size}.")
    }
  }

  private fun inputClosed(): Int {
    terminal.out()
    terminal.out("No answer was read (the input closed). Nothing was scored. Run `make answer` again in a terminal.")
    return 1
  }

  private fun explain(scenario: Scenario, active: ActiveExercise) {
    val params = Params(active.params, hhmm(active.started))
    terminal.out()
    terminal.out("ANSWER")
    terminal.out("  Nature   : ${scenario.answer.nature.joinToString(" / ") { Answers.label(Answers.NATURES, it) }}")
    terminal.out("  Location : ${scenario.answer.location.joinToString(" / ") { Answers.label(Answers.LOCATIONS, it) }}")
    terminal.out("  Cause    : ${Answers.label(Answers.CAUSES, scenario.answer.cause)}")
    terminal.out()
    terminal.out("EXPLANATION")
    terminal.out("  " + scenario.explain(params))
    terminal.out()
    terminal.out("EVIDENCE TO LOOK FOR (PromQL, paste into Grafana Explore or Prometheus)")
    scenario.evidence.forEach { terminal.out("  - $it") }
    terminal.out()
    terminal.out("Read again in the theory doc: ${scenario.doc}")
  }

  private fun finish(state: LabState, score: Double?) {
    val active = state.active ?: return
    val scenario = catalog.byId.getValue(active.id)
    val entry = HistoryEntry(
      n = active.n,
      id = active.id,
      title = scenario.title,
      level = scenario.level,
      score = score,
      hints = active.hints,
      minutes = ((now() - active.started) / SECONDS_PER_MINUTE * TENTHS).roundToInt() / TENTHS,
      at = now()
    )
    val next = state.copy(history = state.history + entry)
    store.save(next)
    offerClear(next)
  }

  private fun offerClear(state: LabState) {
    val reply = terminal.ask("\nClear the fault so the system goes back to normal? [Y/n] ")
    if (reply == null) {
      terminal.out()
    }
    val clearIt = reply == null || reply.trim().lowercase() !in setOf("n", "no")
    if (clearIt) {
      annotator.annotate("lab: fault cleared", listOf("clear"), now())
      store.save(state.copy(active = null))
      terminal.out("Fault cleared. The system recovers in about 10-60 seconds. Use `make new` for the next exercise.")
    } else {
      terminal.out("The fault keeps running so you can keep looking. Use `make clear` when you are done.")
    }
  }

  private fun banner(text: String) {
    terminal.out()
    terminal.out("=".repeat(BANNER_WIDTH))
    terminal.out(text)
    terminal.out("=".repeat(BANNER_WIDTH))
  }

  private fun hhmm(epochSeconds: Double): String = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone).format(HOUR_MINUTE)

  private fun plain(value: Double): String = if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", value)

  companion object {
    const val HINT_PENALTY = 0.5
    const val NATURE_POINTS = 1.0
    const val LOCATION_POINTS = 1.0
    const val CAUSE_POINTS = 2.0
    const val PARTIAL_POINTS = 1.0
    const val BASE_RPS = 50
    const val MIN_LOAD = 0.1
    const val MAX_LOAD = 6.0
    private const val MAX_LEVEL = 3
    private const val RECENT_WINDOW = 5
    private const val DECOY_MIN_SECONDS = 120
    private const val DECOY_MAX_SECONDS = 900
    private const val BANNER_WIDTH = 72
    private const val SERVICE_COLUMN = 8
    private const val ID_COLUMN = 18
    private const val TITLE_COLUMN = 48
    private const val SCORE_COLUMN = 6
    private const val MINUTES_COLUMN = 5
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val SECONDS_PER_MINUTE = 60.0
    private const val TENTHS = 10.0
    private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    const val LINKS = "  Grafana     http://localhost:3000   (dashboards '01 · RED by layer' and '02 · USE by layer')\n" +
      "  Traces      in Grafana: Explore > Tempo (search, service graph), or click an exemplar dot on a latency panel\n" +
      "  Prometheus  http://localhost:9090"
  }
}
