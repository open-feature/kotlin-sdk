package dev.openfeature.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


class EvaluationMetadataTest {

    private val metadata = EvaluationMetadata.builder()
        .putString("key1", "value1")
        .putInt("key2", 42)
        .putBoolean("key3", true)
        .putDouble("key4", 2.71828)
        .build()

    @Test
    fun testAddAndGet() {
        assertEquals("value1", metadata.getString("key1"))
        assertEquals(42, metadata.getInt("key2"))
        assertEquals(true, metadata.getBoolean("key3"))
        assertEquals(2.71828, metadata.getDouble("key4"))
    }

    @Test
    fun testGetNonExistentKey() {
        assertNull(metadata.getString("key5"))
    }

    @Test
    fun testInvalidType() {
        assertNull(metadata.getString("key2"))
        assertNull(metadata.getInt("key3"))
        assertNull(metadata.getBoolean("key4"))
        assertNull(metadata.getDouble("key1"))
    }
}