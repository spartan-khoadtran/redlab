package com.c0x12c.redlab.labctl.state

data class ActiveExercise(
  val id: String,
  val params: Map<String, Any>,
  /** Epoch seconds. */
  val started: Double,
  val hints: Int = 0,
  val n: Int,
  val answered: Boolean = false
)

data class HistoryEntry(
  val n: Int,
  val id: String,
  val title: String,
  val level: Int,
  /** Null for a revealed exercise, which is not scored. */
  val score: Double?,
  val hints: Int,
  val minutes: Double,
  /** Epoch seconds. */
  val at: Double
)

/** The whole lab state. `base` holds overrides that outlive an exercise, such as the load multiplier. */
data class LabState(
  val active: ActiveExercise? = null,
  val history: List<HistoryEntry> = emptyList(),
  val base: Map<String, Map<String, Any?>> = emptyMap()
)
