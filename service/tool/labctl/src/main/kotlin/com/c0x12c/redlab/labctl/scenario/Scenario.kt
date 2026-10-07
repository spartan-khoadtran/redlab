package com.c0x12c.redlab.labctl.scenario

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/** What an exercise may read from the running lab before it draws its parameters. */
data class ExerciseContext(
  val cpuCount: Int? = null,
  val productDetailSeconds: Double? = null
)

/** The random parameters of one run, plus the ticket time, with typed readers for the texts. */
class Params(
  val values: Map<String, Any?>,
  val startedAt: String
) {
  fun int(key: String): Int = (values.getValue(key) as Number).toInt()

  fun double(key: String): Double = (values.getValue(key) as Number).toDouble()

  fun string(key: String): String = values.getValue(key).toString()

  fun percent(key: String): String = "${(double(key) * PERCENT).roundToInt()}%"

  fun fixed(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

  private companion object {
    const val PERCENT = 100
  }
}

/** Overrides per service, plus container settings under `docker` (today only the api's cpus). */
data class FaultSpec(
  val services: Map<String, Map<String, Any?>> = emptyMap(),
  val docker: Map<String, Map<String, Any?>> = emptyMap()
)

/** nature and location are sets of accepted keys; cause is one key; partial causes earn one point. */
data class Answer(
  val nature: Set<String>,
  val location: Set<String>,
  val cause: String,
  val partial: Set<String> = emptySet()
)

class Scenario(
  val id: String,
  val title: String,
  val level: Int,
  val topics: List<String>,
  val params: (Random, ExerciseContext) -> Map<String, Any>,
  val faults: (Params) -> FaultSpec,
  val ticket: (Params) -> String,
  val hints: List<String>,
  val answer: Answer,
  val explain: (Params) -> String,
  val evidence: List<String>,
  val doc: String,
  /** A real change event, written as a Grafana annotation when the exercise starts. */
  val marker: String? = null,
  /** Probability of a fake change event 2 to 15 minutes before the start. */
  val decoy: Double = 0.0
)
