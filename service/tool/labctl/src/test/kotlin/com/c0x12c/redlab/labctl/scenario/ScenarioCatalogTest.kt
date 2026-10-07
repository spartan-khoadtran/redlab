package com.c0x12c.redlab.labctl.scenario

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.c0x12c.redlab.labctl.control.Services
import kotlin.random.Random
import org.junit.jupiter.api.Test

class ScenarioCatalogTest {

  private val natures = Answers.NATURES.map { it.first }.toSet()
  private val locations = Answers.LOCATIONS.map { it.first }.toSet()
  private val causes = Answers.CAUSES.map { it.first }.toSet()

  @Test
  fun `sixteen scenarios with unique ids and valid answer keys`() {
    assertThat(ScenarioCatalog.all).hasSize(16)
    assertThat(ScenarioCatalog.all.map { it.id }.toSet()).hasSize(16)
    for (scenario in ScenarioCatalog.all) {
      assertThat(scenario.level).isBetween(1, 3)
      assertThat(scenario.answer.nature.all { it in natures }).isTrue()
      assertThat(scenario.answer.location.all { it in locations }).isTrue()
      assertThat(scenario.answer.cause in causes).isTrue()
      assertThat(scenario.answer.partial.all { it in causes }).isTrue()
      assertThat(scenario.topics.all { it in Answers.TOPICS }).isTrue()
      assertThat(scenario.hints).hasSize(3)
      assertThat(scenario.evidence).isNotEmpty()
    }
  }

  @Test
  fun `every scenario renders its texts and targets known services`() {
    val random = Random(42)
    for (scenario in ScenarioCatalog.all) {
      repeat(5) {
        val params = Params(scenario.params(random, ExerciseContext(cpuCount = 8, productDetailSeconds = 0.015)), "14:05")
        val spec = scenario.faults(params)
        assertThat(spec.services.keys.all { it in Services.ENDPOINTS }).isTrue()
        assertThat(spec.docker.keys.all { it in Services.BASE_DOCKER }).isTrue()
        val ticket = scenario.ticket(params)
        val explain = scenario.explain(params)
        assertThat(ticket.contains("14:05")).isTrue()
        assertThat(explain.contains("null")).isFalse()
        assertThat(explain.contains("{")).isFalse()
      }
    }
  }

  @Test
  fun `the crawler rate is calibrated to the measured query cost and clamped`() {
    val scenario = ScenarioCatalog.byId.getValue("tenant_spike")
    val cheap = scenario.params(Random(1), ExerciseContext(productDetailSeconds = 0.001))["extra"] as Int
    val expensive = scenario.params(Random(1), ExerciseContext(productDetailSeconds = 0.5))["extra"] as Int
    assertThat(cheap).isEqualTo(600)
    assertThat(expensive).isEqualTo(40)
  }

  @Test
  fun `the noisy neighbour sizes itself to the host`() {
    val scenario = ScenarioCatalog.byId.getValue("noisy_neighbor")
    val spec = scenario.faults(Params(scenario.params(Random(1), ExerciseContext(cpuCount = 6)), ""))
    assertThat(spec.services.keys).containsOnly("noisy")
    assertThat(spec.services.getValue("noisy")["burners"]).isEqualTo(12)
  }
}
