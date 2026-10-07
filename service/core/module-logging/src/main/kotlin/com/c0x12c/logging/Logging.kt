package com.c0x12c.logging

import net.logstash.logback.marker.LogstashMarker
import net.logstash.logback.marker.Markers.appendEntries
import net.logstash.logback.marker.Markers.appendFields
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The log level constant which maps one to one with each [Logger] enabled method.
 */
enum class LogLevel {
  ERROR,
  DEBUG,
  INFO,
  WARN,
  TRACE
}

@DslMarker
annotation class LogDsl

/**
 * A DSL toggle that allow child classes to optionally toggle on/of log at different level.
 * Default all logs is enabled.
 */
@LogDsl
class Toggle {
  var error: Boolean = true
  var debug: Boolean = true
  var info: Boolean = true
  var warn: Boolean = true
  var trace: Boolean = true
}

/**
 * Core logging interface with the ability to attach logging method to each class without
 * having to declare any class member.
 */
interface Logging {
  /**
   * The actual logger implementation.
   */
  val delegate: Logger get() = javaClass.logger()

  /**
   * Use this method to override delegate logger then toggle log level
   *
   * @param toggle The abstract logging toggle to be overridden
   */
  fun logger(toggle: Toggle.() -> Unit): Logger {
    val result = Toggle().apply(toggle)
    return object : ForwardingLogger(javaClass.logger()) {
      override fun isErrorEnabled(): Boolean {
        return result.error
      }

      override fun isDebugEnabled(): Boolean {
        return result.debug
      }

      override fun isInfoEnabled(): Boolean {
        return result.info
      }

      override fun isWarnEnabled(): Boolean {
        return result.warn
      }

      override fun isTraceEnabled(): Boolean {
        return result.trace
      }
    }
  }
}

/**
 * Forwarding class allow any subclass overwrite a subset of methods of [Logger] to modify logging behaviors.
 */
private open class ForwardingLogger(val delegate: Logger) : Logger by delegate

fun loggable(clazz: Class<*>): Logging = object : Logging {
  override val delegate: Logger = ForwardingLogger(clazz.logger())
}

inline fun <reified T : Any> logging(): Logging = loggable(T::class.java)

private fun Class<*>.logger(): Logger {
  return LoggerFactory.getLogger(this)
}

fun Logging.name(): String {
  return delegate.name
}

fun Logging.enable(level: LogLevel): Boolean {
  return when (level) {
    LogLevel.ERROR -> delegate.isErrorEnabled
    LogLevel.DEBUG -> delegate.isDebugEnabled
    LogLevel.INFO -> delegate.isInfoEnabled
    LogLevel.WARN -> delegate.isWarnEnabled
    LogLevel.TRACE -> delegate.isTraceEnabled
  }
}

fun marker(args: Any): LogstashMarker {
  if (args is Map<*, *>) {
    return appendEntries(args)
  }
  return appendFields(args)
}

fun Logging.warn(args: Any, msg: () -> Any?) {
  if (enable(LogLevel.WARN)) {
    delegate.warn(marker(args), msg.toStringSafe())
  }
}

fun Logging.warn(msg: String?) {
  if (enable(LogLevel.WARN)) {
    delegate.warn(msg)
  }
}

fun Logging.warn(msg: String?, t: Throwable?) {
  if (enable(LogLevel.WARN)) {
    delegate.warn(msg, t)
  }
}

fun Logging.warn(msg: () -> Any?) {
  warn(msg.toStringSafe())
}

fun Logging.warn(t: Throwable, msg: () -> Any?) {
  warn(msg.toStringSafe(), t)
}

fun Logging.info(msg: String?) {
  if (enable(LogLevel.INFO)) {
    delegate.info(msg)
  }
}

fun Logging.info(args: Any, msg: () -> Any?) {
  if (enable(LogLevel.INFO)) {
    delegate.info(marker(args), msg.toStringSafe())
  }
}

fun Logging.info(msg: String?, t: Throwable?) {
  if (enable(LogLevel.INFO)) {
    delegate.info(msg, t)
  }
}

fun Logging.info(msg: () -> Any?) {
  info(msg.toStringSafe())
}

fun Logging.info(t: Throwable, msg: () -> Any?) {
  info(msg.toStringSafe(), t)
}

fun Logging.severe(args: Any, msg: () -> Any?) {
  if (enable(LogLevel.ERROR)) {
    delegate.error(marker(args), msg.toStringSafe())
  }
}

fun Logging.severe(msg: String?) {
  if (enable(LogLevel.ERROR)) {
    delegate.error(msg)
  }
}

fun Logging.severe(msg: String?, t: Throwable?) {
  if (enable(LogLevel.ERROR)) {
    delegate.error(msg, t)
  }
}

fun Logging.severe(msg: () -> Any?) {
  severe(msg.toStringSafe())
}

fun Logging.severe(t: Throwable, msg: () -> Any?) {
  severe(msg.toStringSafe(), t)
}

fun Logging.debug(args: Any, msg: () -> Any?) {
  if (enable(LogLevel.DEBUG)) {
    delegate.debug(marker(args), msg.toStringSafe())
  }
}

fun Logging.debug(msg: String?) {
  if (enable(LogLevel.DEBUG)) {
    delegate.debug(msg)
  }
}

fun Logging.debug(msg: String?, t: Throwable?) {
  if (enable(LogLevel.DEBUG)) {
    delegate.debug(msg, t)
  }
}

fun Logging.debug(t: Throwable, msg: () -> Any?) {
  debug(msg.toStringSafe(), t)
}

fun Logging.debug(msg: () -> Any?) {
  debug(msg.toStringSafe())
}

fun Logging.trace(msg: String?) {
  if (enable(LogLevel.TRACE)) {
    delegate.trace(msg)
  }
}

fun Logging.trace(msg: String?, t: Throwable?) {
  if (enable(LogLevel.TRACE)) {
    delegate.trace(msg, t)
  }
}

fun Logging.trace(msg: () -> Any?) {
  trace(msg.toStringSafe())
}

fun Logging.trace(t: Throwable, msg: () -> Any?) {
  trace(msg.toStringSafe(), t)
}
