package dev.openfeature.kotlin.sdk.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoggerTests {
    @Test
    fun `NoOpLogger executes without throwing`() {
        val logger = NoOpLogger()

        logger.debug({ "test message" }, { emptyMap() })
        logger.info({ "test message" }, { emptyMap() })
        logger.warn({ "test message" }, { emptyMap() })
        logger.error({ "test message" }, { emptyMap() })

        val throwable = RuntimeException("test exception")
        logger.debug({ "test message" }, { emptyMap() }, throwable)
        logger.info({ "test message" }, { emptyMap() }, throwable)
        logger.warn({ "test message" }, { emptyMap() }, throwable)
        logger.error({ "test message" }, { emptyMap() }, throwable)
    }

    @Test
    fun `NoOpLogger does not evaluate message lambda`() {
        val logger = NoOpLogger()
        var evaluated = false

        logger.debug({ evaluated = true; "should not be evaluated" }, { emptyMap() })

        assertEquals(false, evaluated)
    }

    @Test
    fun `NoOpLogger does not evaluate attributes lambda`() {
        val logger = NoOpLogger()
        var evaluated = false

        logger.debug({ "msg" }, { evaluated = true; emptyMap() })

        assertEquals(false, evaluated)
    }

    @Test
    fun `NoOpLogger does not evaluate info lambdas`() {
        val logger = NoOpLogger()
        var msgEvaluated = false
        var attrsEvaluated = false

        logger.info({ msgEvaluated = true; "msg" }, { attrsEvaluated = true; emptyMap() })

        assertEquals(false, msgEvaluated)
        assertEquals(false, attrsEvaluated)
    }

    @Test
    fun `NoOpLogger does not evaluate warn lambdas`() {
        val logger = NoOpLogger()
        var msgEvaluated = false
        var attrsEvaluated = false

        logger.warn({ msgEvaluated = true; "msg" }, { attrsEvaluated = true; emptyMap() })

        assertEquals(false, msgEvaluated)
        assertEquals(false, attrsEvaluated)
    }

    @Test
    fun `NoOpLogger does not evaluate error lambdas`() {
        val logger = NoOpLogger()
        var msgEvaluated = false
        var attrsEvaluated = false

        logger.error({ msgEvaluated = true; "msg" }, { attrsEvaluated = true; emptyMap() })

        assertEquals(false, msgEvaluated)
        assertEquals(false, attrsEvaluated)
    }

    @Test
    fun `TestLogger captures debug messages with attributes`() {
        val logger = TestLogger()
        val attrs = mapOf("flag" to "my-flag", "type" to "BOOLEAN")
        val throwable = RuntimeException("test exception")

        logger.debug({ "debug message" }, { attrs })
        logger.debug({ "debug with throwable" }, { emptyMap() }, throwable)

        assertEquals(2, logger.debugMessages.size)
        assertEquals("debug message", logger.debugMessages[0].message)
        assertEquals(attrs, logger.debugMessages[0].attributes)
        assertNull(logger.debugMessages[0].throwable)
        assertEquals("debug with throwable", logger.debugMessages[1].message)
        assertEquals(emptyMap<String, Any?>(), logger.debugMessages[1].attributes)
        assertNotNull(logger.debugMessages[1].throwable)
    }

    @Test
    fun `TestLogger captures info messages with attributes`() {
        val logger = TestLogger()
        val attrs = mapOf("key" to "value")

        logger.info({ "info message" }, { attrs })

        assertEquals(1, logger.infoMessages.size)
        assertEquals("info message", logger.infoMessages[0].message)
        assertEquals(attrs, logger.infoMessages[0].attributes)
        assertNull(logger.infoMessages[0].throwable)
    }

    @Test
    fun `TestLogger captures warn messages with attributes`() {
        val logger = TestLogger()
        val attrs = mapOf("key" to "value")

        logger.warn({ "warn message" }, { attrs })

        assertEquals(1, logger.warnMessages.size)
        assertEquals("warn message", logger.warnMessages[0].message)
        assertEquals(attrs, logger.warnMessages[0].attributes)
    }

    @Test
    fun `TestLogger captures error messages with attributes`() {
        val logger = TestLogger()
        val attrs = mapOf("key" to "value")
        val throwable = RuntimeException("test exception")

        logger.error({ "error message" }, { attrs }, throwable)

        assertEquals(1, logger.errorMessages.size)
        assertEquals("error message", logger.errorMessages[0].message)
        assertEquals(attrs, logger.errorMessages[0].attributes)
        assertEquals(throwable, logger.errorMessages[0].throwable)
    }

    @Test
    fun `TestLogger clear removes all messages`() {
        val logger = TestLogger()

        logger.debug({ "debug" }, { emptyMap() })
        logger.info({ "info" }, { emptyMap() })
        logger.warn({ "warn" }, { emptyMap() })
        logger.error({ "error" }, { emptyMap() })

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

        logger.debug({ "message 1" }, { emptyMap() })
        logger.info({ "message 2" }, { emptyMap() })
        logger.warn({ "message 3" }, { emptyMap() })
        logger.error({ "message 4" }, { emptyMap() })

        val allMessages = logger.getAllMessages()
        assertEquals(4, allMessages.size)
        assertEquals("message 1", allMessages[0].message)
        assertEquals("message 2", allMessages[1].message)
        assertEquals("message 3", allMessages[2].message)
        assertEquals("message 4", allMessages[3].message)
    }

    @Test
    fun `TestLogger attributes are evaluated and stored`() {
        val logger = TestLogger()
        val attrs = mapOf<String, Any?>("flag" to "test-flag", "value" to true, "count" to 42)

        logger.debug({ "msg" }, { attrs })

        val entry = logger.debugMessages[0]
        assertEquals("test-flag", entry.attributes["flag"])
        assertEquals(true, entry.attributes["value"])
        assertEquals(42, entry.attributes["count"])
    }

    @Test
    fun `TestLogger supports null values in attributes`() {
        val logger = TestLogger()

        logger.debug({ "msg" }, { mapOf("nullKey" to null) })

        assertTrue(logger.debugMessages[0].attributes.containsKey("nullKey"))
        assertNull(logger.debugMessages[0].attributes["nullKey"])
    }

    @Test
    fun `formatLogLine returns message when attributes empty and no throwable`() {
        assertEquals("hello world", formatLogLine("hello world", emptyMap()))
    }

    @Test
    fun `formatLogLine appends attributes as key=value pairs`() {
        val result = formatLogLine("msg", mapOf("flag" to "my-flag", "type" to "BOOLEAN"))
        assertTrue(result.startsWith("msg "))
        assertTrue(result.contains("flag=my-flag"))
        assertTrue(result.contains("type=BOOLEAN"))
    }

    @Test
    fun `formatLogLine renders null attribute values`() {
        val result = formatLogLine("msg", mapOf("key" to null))
        assertEquals("msg key=null", result)
    }

    @Test
    fun `formatLogLine appends throwable stacktrace on new line`() {
        val t = RuntimeException("boom")
        val result = formatLogLine("msg", emptyMap(), t)
        assertTrue(result.startsWith("msg\n"))
        assertTrue(result.contains("boom"))
    }

    @Test
    fun `formatLogLine with attributes and throwable includes both`() {
        val t = RuntimeException("boom")
        val result = formatLogLine("msg", mapOf("k" to "v"), t)
        assertTrue(result.startsWith("msg k=v\n"))
        assertTrue(result.contains("boom"))
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