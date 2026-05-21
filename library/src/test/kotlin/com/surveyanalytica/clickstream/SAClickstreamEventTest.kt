package com.surveyanalytica.clickstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SAClickstreamEvent] JSON serialization, [SADeviceInfo] serialization,
 * and [String.jsonEscape] helper.
 *
 * These tests run on the JVM (no Android framework required).
 */
class SAClickstreamEventTest {

    private val sampleDevice = SADeviceInfo(
        os = "android",
        osVersion = "13",
        device = "Google Pixel 7",
        appVersion = "2.1.0",
    )

    // -------------------------------------------------------------------------
    // jsonEscape
    // -------------------------------------------------------------------------

    @Test
    fun `jsonEscape leaves plain strings unchanged`() {
        assertEquals("hello world", "hello world".jsonEscape())
    }

    @Test
    fun `jsonEscape escapes double quotes`() {
        assertEquals("say \\\"hello\\\"", "say \"hello\"".jsonEscape())
    }

    @Test
    fun `jsonEscape escapes backslash`() {
        assertEquals("path\\\\to\\\\file", "path\\to\\file".jsonEscape())
    }

    @Test
    fun `jsonEscape escapes newline and tab`() {
        assertEquals("line1\\nline2\\ttabbed", "line1\nline2\ttabbed".jsonEscape())
    }

    @Test
    fun `jsonEscape escapes carriage return`() {
        assertEquals("\\r", "\r".jsonEscape())
    }

    @Test
    fun `jsonEscape escapes control characters with unicode notation`() {
        // ASCII 0x01 (SOH control char) should become 
        val result = "".jsonEscape()
        assertEquals("\\u0001", result)
    }

    // -------------------------------------------------------------------------
    // SADeviceInfo.toJson()
    // -------------------------------------------------------------------------

    @Test
    fun `SADeviceInfo toJson contains all expected fields`() {
        val json = sampleDevice.toJson()
        assertTrue(json.contains("\"platform\":\"android\""))
        assertTrue(json.contains("\"os\":\"android\""))
        assertTrue(json.contains("\"osVersion\":\"13\""))
        assertTrue(json.contains("\"device\":\"Google Pixel 7\""))
        assertTrue(json.contains("\"appVersion\":\"2.1.0\""))
    }

    @Test
    fun `SADeviceInfo toJson produces valid JSON brackets`() {
        val json = sampleDevice.toJson()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
    }

    // -------------------------------------------------------------------------
    // SAClickstreamEvent.toJson()
    // -------------------------------------------------------------------------

    @Test
    fun `event toJson contains required top-level fields`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = "contact-abc",
            sessionId = "session-xyz",
            event = "button_tapped",
            properties = mapOf("label" to "Buy Now", "price" to 9.99),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        assertTrue(json.contains("\"type\":\"event\""))
        assertTrue(json.contains("\"contactId\":\"contact-abc\""))
        assertTrue(json.contains("\"sessionId\":\"session-xyz\""))
        assertTrue(json.contains("\"event\":\"button_tapped\""))
        assertTrue(json.contains("\"ts\":\"2024-01-01T00:00:00.000Z\""))
    }

    @Test
    fun `event toJson serializes string properties correctly`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = "c1",
            sessionId = "s1",
            event = "test",
            properties = mapOf("key" to "value"),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        assertTrue(json.contains("\"properties\":{\"key\":\"value\"}"))
    }

    @Test
    fun `event toJson serializes number properties correctly`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = "c1",
            sessionId = "s1",
            event = "test",
            properties = mapOf("count" to 42, "price" to 3.14),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        assertTrue(json.contains("\"count\":42"))
        assertTrue(json.contains("\"price\":3.14"))
    }

    @Test
    fun `event toJson serializes boolean properties correctly`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = "c1",
            sessionId = "s1",
            event = "test",
            properties = mapOf("active" to true, "deleted" to false),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        assertTrue(json.contains("\"active\":true"))
        assertTrue(json.contains("\"deleted\":false"))
    }

    @Test
    fun `event toJson outputs null for null contactId`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = null,
            sessionId = "s1",
            event = "test",
            properties = emptyMap(),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        assertTrue(json.contains("\"contactId\":null"))
    }

    @Test
    fun `uid_transition event includes oldId and newId`() {
        val event = SAClickstreamEvent(
            type = "uid_transition",
            contactId = "new-id",
            sessionId = "s1",
            event = "",
            properties = emptyMap(),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
            oldId = "anon-uuid-123",
            newId = "new-id",
        )
        val json = event.toJson()
        assertTrue(json.contains("\"type\":\"uid_transition\""))
        assertTrue(json.contains("\"oldId\":\"anon-uuid-123\""))
        assertTrue(json.contains("\"newId\":\"new-id\""))
    }

    @Test
    fun `regular event does not include oldId or newId`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = "c1",
            sessionId = "s1",
            event = "page_view",
            properties = emptyMap(),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        assertFalse(json.contains("\"oldId\""))
        assertFalse(json.contains("\"newId\""))
    }

    @Test
    fun `event toJson escapes special characters in property values`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = "c1",
            sessionId = "s1",
            event = "test",
            properties = mapOf("message" to "say \"hello\"\nworld"),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        // The escaped string should appear in the JSON output
        assertTrue(json.contains("say \\\"hello\\\"\\nworld"))
    }

    @Test
    fun `event toJson handles empty properties map`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = "c1",
            sessionId = "s1",
            event = "app_open",
            properties = emptyMap(),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        assertTrue(json.contains("\"properties\":{}"))
    }

    @Test
    fun `event toJson includes device block`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = "c1",
            sessionId = "s1",
            event = "test",
            properties = emptyMap(),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        assertTrue(json.contains("\"device\":{"))
        assertTrue(json.contains("\"os\":\"android\""))
    }

    @Test
    fun `event toJson produces valid JSON-like structure with matching braces`() {
        val event = SAClickstreamEvent(
            type = "event",
            contactId = "c1",
            sessionId = "s1",
            event = "test",
            properties = mapOf("a" to "b"),
            device = sampleDevice,
            ts = "2024-01-01T00:00:00.000Z",
        )
        val json = event.toJson()
        val openBraces = json.count { it == '{' }
        val closeBraces = json.count { it == '}' }
        assertEquals("Mismatched braces in JSON output", openBraces, closeBraces)
    }
}
