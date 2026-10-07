package com.c0x12c.redlab.labctl.exercise

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.c0x12c.redlab.labctl.Terminal
import com.c0x12c.redlab.labctl.control.Annotator
import com.c0x12c.redlab.labctl.control.LabProbe
import com.c0x12c.redlab.labctl.scenario.Answers
import com.c0x12c.redlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.redlab.labctl.state.StateStore
import java.nio.file.Path
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExercisesTest {

  private class ScriptedTerminal(private val answers: ArrayDeque<String?>) : Terminal {
    val lines = mutableListOf<String>()

    override fun out(line: String) {
      lines.add(line)
    }

    override fun ask(prompt: String): String? = answers.removeFirst()
  }

  private val probe = object : LabProbe {
    override fun cpuCount(): Int = 4

    override fun productDetailSeconds(): Double? = null

    override fun serviceStatus(): Map<String, String?> = mapOf("api" to null)
  }

  private val annotations = mutableListOf<String>()
  private val annotator = Annotator { text, _, _ -> annotations.add(text) }

  private fun exercises(dir: Path, terminal: Terminal): Exercises =
    Exercises(StateStore(dir.resolve("state.json")), probe, annotator, terminal, random = Random(1), now = { 1_700_000_000.0 })

  @Test
  fun `a full round of new, hint and answer is scored with the hint penalty`(@TempDir dir: Path) {
    val index = { options: List<Pair<String, String>>, key: String -> (options.indexOfFirst { it.first == key } + 1).toString() }
    val terminal = ScriptedTerminal(
      ArrayDeque(listOf(index(Answers.NATURES, "arch"), index(Answers.LOCATIONS, "api_db"), index(Answers.CAUSES, "pool_exhausted"), "y"))
    )
    val exercises = exercises(dir, terminal)

    assertThat(exercises.newExercise(NewOptions(id = "pool_hold"))).isEqualTo(0)
    assertThat(annotations.any { it.startsWith("Deploy checkout-api v2.31") }).isTrue()
    assertThat(exercises.hint()).isEqualTo(0)
    assertThat(exercises.answer()).isEqualTo(0)

    assertThat(terminal.lines.any { it.startsWith("RESULT: 3.5/4") }).isTrue()
    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.active).isNull()
    assertThat(state.history.single().score).isEqualTo(3.5)
    assertThat(annotations.last()).isEqualTo("lab: fault cleared")
  }

  @Test
  fun `a partial cause earns one point and a wrong answer none`(@TempDir dir: Path) {
    val index = { options: List<Pair<String, String>>, key: String -> (options.indexOfFirst { it.first == key } + 1).toString() }
    val terminal = ScriptedTerminal(
      ArrayDeque(listOf(index(Answers.NATURES, "load"), index(Answers.LOCATIONS, "host"), index(Answers.CAUSES, "downstream_slow"), "n"))
    )
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "retry_storm"))
    exercises.answer()

    assertThat(terminal.lines.any { it.startsWith("RESULT: 1/4") }).isTrue()
    assertThat(StateStore(dir.resolve("state.json")).load().active).isNotNull()
  }

  @Test
  fun `a second new without force is refused and reveal is not scored`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(listOf("y")))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "oom"))
    assertThat(exercises.newExercise(NewOptions(id = "oom"))).isEqualTo(1)
    assertThat(exercises.reveal()).isEqualTo(0)

    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.history.single().score).isNull()
    assertThat(state.active).isNull()
  }

  @Test
  fun `load changes the base override within bounds`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque())
    val exercises = exercises(dir, terminal)

    exercises.load(9.0)

    assertThat(StateStore(dir.resolve("state.json")).load().base.getValue("loadgen")["rate_multiplier"]).isEqualTo(6.0)
  }

  private val index = { options: List<Pair<String, String>>, key: String -> (options.indexOfFirst { it.first == key } + 1).toString() }

  @Test
  fun `a bad pick asks again and the hint line shows the running penalty`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(
      ArrayDeque(listOf("", "abc", "99", index(Answers.NATURES, "arch"), index(Answers.LOCATIONS, "container"), index(Answers.CAUSES, "cpu_throttle"), "y"))
    )
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "cpu_throttle"))
    exercises.hint()
    exercises.hint()
    assertThat(terminal.lines).contains("Hint 1/3 (-0.5 point, -0.5 in total): " + ScenarioCatalog.byId.getValue("cpu_throttle").hints[0])
    assertThat(terminal.lines.any { it.startsWith("Hint 2/3 (-0.5 point, -1 in total)") }).isTrue()
    assertThat(exercises.answer()).isEqualTo(0)

    assertThat(terminal.lines.count { it == "  Enter a number from 1 to ${Answers.NATURES.size}." }).isEqualTo(3)
    assertThat(terminal.lines.any { it.startsWith("RESULT: 3/4  (correct 4/4, 2 hint(s) used)") }).isTrue()
  }

  @Test
  fun `closed input in the middle of an answer scores nothing and keeps the exercise`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(listOf(index(Answers.NATURES, "arch"), null)))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "oom"))
    assertThat(exercises.answer()).isEqualTo(1)

    assertThat(terminal.lines.last()).contains("Nothing was scored")
    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.history).hasSize(0)
    assertThat(state.active!!.answered).isFalse()
  }

  @Test
  fun `reveal after a kept answer adds no second history entry`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(
      ArrayDeque(listOf(index(Answers.NATURES, "arch"), index(Answers.LOCATIONS, "container"), index(Answers.CAUSES, "oom"), "n", "y"))
    )
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "oom"))
    exercises.answer()
    assertThat(exercises.answer()).isEqualTo(0)
    exercises.hint()
    assertThat(terminal.lines.last()).contains("already submitted")
    exercises.reveal()

    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.history.single().score).isEqualTo(4.0)
    assertThat(state.active).isNull()
  }

  @Test
  fun `an answer after a kept reveal is refused`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(listOf("n")))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "oom"))
    exercises.reveal()
    exercises.answer()

    assertThat(terminal.lines.last()).contains("already submitted")
    assertThat(StateStore(dir.resolve("state.json")).load().history.single().score).isNull()
  }
}
