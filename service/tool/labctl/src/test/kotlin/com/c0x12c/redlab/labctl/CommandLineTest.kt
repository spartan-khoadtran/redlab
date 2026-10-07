package com.c0x12c.redlab.labctl

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.c0x12c.redlab.labctl.exercise.NewOptions
import org.junit.jupiter.api.Test

class CommandLineTest {

  @Test
  fun `new options parse every flag the Makefile passes`() {
    val options = CommandLine.newOptions(listOf("--level", "2", "--topic", "async", "--id", "pool_hold", "--force"))

    assertThat(options).isEqualTo(NewOptions(level = 2, topic = "async", id = "pool_hold", force = true))
  }

  @Test
  fun `a level that is not 1 to 3 is refused`() {
    assertFailure { CommandLine.newOptions(listOf("--level", "abc")) }.hasMessage("LEVEL must be one of 1, 2, 3. See `make list`.")
    assertFailure { CommandLine.newOptions(listOf("--level", "9")) }.isInstanceOf(UsageError::class)
  }

  @Test
  fun `an unknown topic is refused with the topic list`() {
    assertFailure { CommandLine.newOptions(listOf("--topic", "nope")) }.isInstanceOf(UsageError::class)
  }

  @Test
  fun `load takes a number in range, with a dot or a comma`() {
    assertThat(CommandLine.loadMultiplier("1.5")).isEqualTo(1.5)
    assertThat(CommandLine.loadMultiplier("1,5")).isEqualTo(1.5)
    assertFailure { CommandLine.loadMultiplier("abc") }.isInstanceOf(UsageError::class)
    assertFailure { CommandLine.loadMultiplier("9") }.isInstanceOf(UsageError::class)
  }

  @Test
  fun `quiz count must be a positive whole number`() {
    assertThat(CommandLine.quizCount(listOf("-n", "3"), 5)).isEqualTo(3)
    assertThat(CommandLine.quizCount(emptyList(), 5)).isEqualTo(5)
    assertFailure { CommandLine.quizCount(listOf("-n", "x"), 5) }.isInstanceOf(UsageError::class)
  }
}
