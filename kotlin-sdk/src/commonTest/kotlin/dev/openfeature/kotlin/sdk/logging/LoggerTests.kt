package dev.openfeature.kotlin.sdk.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LoggerTests {
    @Test
    fun `NoOpLogger executes without throwing`() {
        val logger = NoOpLogger()

        // All methods should execute without throwing
        logger.debug { "test message" }
        logger.info { "test message" }
        logger.warn { "test message" }
        logger.error { "test message" }

        // With throwables
        val throwable = RuntimeException("test exception")
        logger.debug(throwable) { "test message" }
        logger.info(throwable) { "test message" }
        logger.warn(throwable) { "test message" }
        logger.error(throwable) { "test message" }
    }

    @Test
    fun `NoOpLogger does not evaluate message lambda`() {
        val logger = NoOpLogger()
        var evaluated = false

        logger.debug {
            evaluated = true
            "should not be evaluated"
        }

        assertEquals(false, evaluated)
    }

    @Test
    fun `TestLogger captures debug messages`() {
        val logger = TestLogger()
        val message = "debug message"
        val throwable = RuntimeException("test exception")

        logger.debug { message }
        logger.debug(throwable) { "$message with throwable" }

        assertEquals(2, logger.debugMessages.size)
        assertEquals(message, logger.debugMessages[0].message)
        assertNull(logger.debugMessages[0].throwable)
        assertEquals("$message with throwable", logger.debugMessages[1].message)
        assertNotNull(logger.debugMessages[1].throwable)
    }

    @Test
    fun `TestLogger captures info messages`() {
        val logger = TestLogger()
        val message = "info message"
        val throwable = RuntimeException("test exception")

        logger.info { message }
        logger.info(throwable) { "$message with throwable" }

        assertEquals(2, logger.infoMessages.size)
        assertEquals(message, logger.infoMessages[0].message)
        assertNull(logger.infoMessages[0].throwable)
        assertEquals("$message with throwable", logger.infoMessages[1].message)
        assertNotNull(logger.infoMessages[1].throwable)
    }

    @Test
    fun `TestLogger captures warn messages`() {
        val logger = TestLogger()
        val message = "warn message"
        val throwable = RuntimeException("test exception")

        logger.warn { message }
        logger.warn(throwable) { "$message with throwable" }

        assertEquals(2, logger.warnMessages.size)
        assertEquals(message, logger.warnMessages[0].message)
        assertNull(logger.warnMessages[0].throwable)
        assertEquals("$message with throwable", logger.warnMessages[1].message)
        assertNotNull(logger.warnMessages[1].throwable)
    }

    @Test
    fun `TestLogger captures error messages`() {
        val logger = TestLogger()
        val message = "error message"
        val throwable = RuntimeException("test exception")

        logger.error { message }
        logger.error(throwable) { "$message with throwable" }

        assertEquals(2, logger.errorMessages.size)
        assertEquals(message, logger.errorMessages[0].message)
        assertNull(logger.errorMessages[0].throwable)
        assertEquals("$message with throwable", logger.errorMessages[1].message)
        assertNotNull(logger.errorMessages[1].throwable)
    }

    @Test
    fun `TestLogger clear removes all messages`() {
        val logger = TestLogger()

        logger.debug { "debug" }
        logger.info { "info" }
        logger.warn { "warn" }
        logger.error { "error" }

        assertEquals(4, logger.getAllMessages().size)

        logger.clear()

        assertEquals(0, logger.debugMessages.size)
        assertEquals(0, logger.infoMessages.size)
        assertEquals(0, logger.warnMessages.size)
        assertEquals(0, logger.errorMessages.size)
        assertEquals(0, logger.getAllMessages().size)
    }

    @Test
    fun `TestLogger getAllMessages returns all messages in order`() {
        val logger = TestLogger()

        logger.debug { "message 1" }
        logger.info { "message 2" }
        logger.warn { "message 3" }
        logger.error { "message 4" }

        val allMessages = logger.getAllMessages()
        assertEquals(4, allMessages.size)
        assertEquals("message 1", allMessages[0].message)
        assertEquals("message 2", allMessages[1].message)
        assertEquals("message 3", allMessages[2].message)
        assertEquals("message 4", allMessages[3].message)
    }

    @Test
    fun `LoggerFactory returns non-null logger`() {
        val logger = LoggerFactory.getLogger("test-tag")
        assertNotNull(logger)
    }

    @Test
    fun `LoggerFactory default tag works`() {
        val logger = LoggerFactory.getLogger()
        assertNotNull(logger)
    }
}