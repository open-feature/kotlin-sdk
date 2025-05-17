package dev.openfeature.sdk

import org.junit.Assert
import org.junit.Test

class EvaluationMetadataTest {

    private val metadata = EvaluationMetadata.builder()
        .putString("key1", "value1")
        .putInt("key2", 42)
        .putBoolean("key3", true)
        .putDouble("key4", 2.71828)
        .build()

    @Test
    fun testAddAndGet() {
        Assert.assertEquals("value1", metadata.getString("key1"))
        Assert.assertEquals(42, metadata.getInt("key2"))
        Assert.assertEquals(true, metadata.getBoolean("key3"))
        Assert.assertEquals(2.71828, metadata.getDouble("key4"))
    }

    @Test
    fun testGetNonExistentKey() {
        Assert.assertNull(metadata.getString("key5"))
    }

    @Test
    fun testInvalidType() {
        Assert.assertNull(metadata.getString("key2"))
        Assert.assertNull(metadata.getInt("key3"))
        Assert.assertNull(metadata.getBoolean("key4"))
        Assert.assertNull(metadata.getDouble("key1"))
    }
}