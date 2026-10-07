package com.c0x12c.redlab.labctl

import com.c0x12c.redlab.labctl.exercise.Exercises
import com.c0x12c.redlab.labctl.exercise.NewOptions
import com.c0x12c.redlab.labctl.scenario.Answers
import com.c0x12c.redlab.labctl.scenario.ScenarioCatalog

class UsageError(message: String) : RuntimeException(message)

/** Turns the arguments the Makefile passes into typed options, with a message a learner can act on. */
object CommandLine {

  fun newOptions(args: List<String>): NewOptions {
    var options = NewOptions()
    var index = 0
    while (index < args.size) {
      when (val flag = args[index]) {
        "--level" -> options = options.copy(level = level(args.getOrNull(++index)))
        "--topic" -> options = options.copy(topic = topic(args.getOrNull(++index)))
        "--id" -> options = options.copy(id = args.getOrNull(++index).orEmpty())
        "--force" -> options = options.copy(force = true)
        else -> throw UsageError("Unknown option '$flag'.")
      }
      index++
    }
    return options
  }

  fun loadMultiplier(raw: String?): Double {
    val value = raw?.trim()?.replace(',', '.')?.toDoubleOrNull()
      ?: throw UsageError("X must be a number, for example `make load X=1.5`. X=1 is the normal load.")
    if (value < Exercises.MIN_LOAD || value > Exercises.MAX_LOAD) {
      throw UsageError("X must be between ${Exercises.MIN_LOAD} and ${Exercises.MAX_LOAD.toInt()}. X=1 is the normal load.")
    }
    return value
  }

  fun quizCount(args: List<String>, default: Int): Int {
    val at = args.indexOf("-n")
    if (at < 0) {
      return default
    }
    return args.getOrNull(at + 1)?.toIntOrNull()?.takeIf { it > 0 }
      ?: throw UsageError("N must be a whole number above 0, for example `make quiz N=5`.")
  }

  private fun level(raw: String?): Int {
    val levels = ScenarioCatalog.all.map { it.level }.distinct().sorted()
    return raw?.toIntOrNull()?.takeIf { it in levels }
      ?: throw UsageError("LEVEL must be one of ${levels.joinToString(", ")}. See `make list`.")
  }

  private fun topic(raw: String?): String =
    raw?.takeIf { it in Answers.TOPICS }
      ?: throw UsageError("Unknown TOPIC '${raw.orEmpty()}'. Topics: ${Answers.TOPICS.joinToString(", ")}.")
}
