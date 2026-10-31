package com.omiyage.backend.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** In-memory appender on one logger; [text] renders message, MDC and stack trace of every captured event. */
class LogCapture(loggerName: String) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(loggerName) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val originalLevel = logger.level

    init {
        logger.level = Level.ALL
        appender.start()
        logger.addAppender(appender)
    }

    val events: List<ILoggingEvent> get() = appender.list.toList()

    fun named(name: String): List<ILoggingEvent> = events.filter { it.loggerName == name }

    fun text(): String = events.joinToString("\n") { event ->
        listOf(event.loggerName, event.formattedMessage, event.mdcPropertyMap.toString(), ThrowableProxyUtil.asString(event.throwableProxy).orEmpty())
            .joinToString(" ")
    }

    override fun close() {
        logger.detachAppender(appender)
        logger.level = originalLevel
    }

    companion object {
        fun root() = LogCapture(Logger.ROOT_LOGGER_NAME)
    }
}
