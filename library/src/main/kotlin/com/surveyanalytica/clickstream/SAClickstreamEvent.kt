package com.surveyanalytica.clickstream

/**
 * Represents a single clickstream event that will be batched and sent to
 * the SurveyAnalytica workflow engine.
 *
 * Manual JSON serialization is used to avoid any Gson/Moshi/Jackson dependency,
 * keeping the library footprint minimal.
 */
internal data class SAClickstreamEvent(
    /** Discriminates the event kind: "event", "page_view", "uid_transition", "consent_rejected" */
    val type: String,
    /** The SurveyAnalytica contact ID resolved via SharedPreferences (anonymous or identified). */
    val contactId: String?,
    /** Session UUID generated fresh on each SDK initialization. */
    val sessionId: String,
    /** Named event (e.g. "button_tapped"). Empty string for non-event types. */
    val event: String,
    /** Arbitrary key-value properties supplied by the caller. Values must be String-serializable. */
    val properties: Map<String, Any>,
    /** Snapshot of device metadata at the time the event is built. */
    val device: SADeviceInfo,
    /** ISO-8601 timestamp (UTC) at the time the event is enqueued. */
    val ts: String,
    /** Only set on uid_transition events. */
    val oldId: String? = null,
    /** Only set on uid_transition events. */
    val newId: String? = null,
) {
    /**
     * Serializes this event to a JSON string without any reflection or external libraries.
     * Only String, Number, Boolean, and null property values are handled — complex nested
     * objects in [properties] are converted via [Any.toString].
     */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append('{')

        sb.appendQuoted("type").append(':').appendQuoted(type).append(',')
        sb.appendQuoted("contactId").append(':')
        if (contactId != null) sb.appendQuoted(contactId) else sb.append("null")
        sb.append(',')
        sb.appendQuoted("sessionId").append(':').appendQuoted(sessionId).append(',')
        sb.appendQuoted("event").append(':').appendQuoted(event).append(',')
        sb.appendQuoted("ts").append(':').appendQuoted(ts).append(',')

        // Device info
        sb.appendQuoted("device").append(':')
        sb.append(device.toJson())
        sb.append(',')

        // Properties map
        sb.appendQuoted("properties").append(':')
        sb.appendMap(properties)

        // Optional uid_transition fields
        if (oldId != null) {
            sb.append(',')
            sb.appendQuoted("oldId").append(':').appendQuoted(oldId)
        }
        if (newId != null) {
            sb.append(',')
            sb.appendQuoted("newId").append(':').appendQuoted(newId)
        }

        sb.append('}')
        return sb.toString()
    }
}

/**
 * Static device metadata captured once during [SAClickstream.initialize].
 */
internal data class SADeviceInfo(
    val os: String,
    val osVersion: String,
    val device: String,
    val appVersion: String,
    val platform: String = "android",
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.appendQuoted("platform").append(':').appendQuoted(platform).append(',')
        sb.appendQuoted("os").append(':').appendQuoted(os).append(',')
        sb.appendQuoted("osVersion").append(':').appendQuoted(osVersion).append(',')
        sb.appendQuoted("device").append(':').appendQuoted(device).append(',')
        sb.appendQuoted("appVersion").append(':').appendQuoted(appVersion)
        sb.append('}')
        return sb.toString()
    }
}

// ---------------------------------------------------------------------------
// StringBuilder extension helpers — keep serialization self-contained
// ---------------------------------------------------------------------------

/** Appends a JSON-escaped, double-quoted string. */
internal fun StringBuilder.appendQuoted(value: String): StringBuilder {
    append('"')
    append(value.jsonEscape())
    append('"')
    return this
}

/**
 * Appends a [Map] as a JSON object. String, Number, Boolean and null values
 * are handled natively; all others fall back to their [toString] as a quoted string.
 */
internal fun StringBuilder.appendMap(map: Map<String, Any>): StringBuilder {
    append('{')
    val entries = map.entries.toList()
    entries.forEachIndexed { index, (key, value) ->
        appendQuoted(key).append(':')
        appendAnyValue(value)
        if (index < entries.size - 1) append(',')
    }
    append('}')
    return this
}

internal fun StringBuilder.appendAnyValue(value: Any?): StringBuilder {
    when (value) {
        null -> append("null")
        is Boolean -> append(value.toString())
        is Number -> append(value.toString())
        is String -> appendQuoted(value)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            appendMap(value as Map<String, Any>)
        }
        is List<*> -> {
            append('[')
            value.forEachIndexed { index, item ->
                appendAnyValue(item)
                if (index < value.size - 1) append(',')
            }
            append(']')
        }
        else -> appendQuoted(value.toString())
    }
    return this
}

/**
 * Escapes characters that would be illegal in a JSON string value.
 * Handles the six standard JSON escape sequences plus all C0 control characters
 * (U+0000–U+001F). Uses explicit char.code comparisons so no literal control
 * characters appear in source text.
 */
internal fun String.jsonEscape(): String {
    val sb = StringBuilder(length)
    for (char in this) {
        when {
            char == '"' -> sb.append("\\\"")
            char == '\\' -> sb.append("\\\\")
            char == '\n' -> sb.append("\\n")
            char == '\r' -> sb.append("\\r")
            char == '\t' -> sb.append("\\t")
            char == '\b' -> sb.append("\\b")
            char.code == 0x0C -> sb.append("\\f") // form feed (U+000C)
            char.code < 0x20 -> sb.append("\\u%04x".format(char.code)) // other C0 controls
            else -> sb.append(char)
        }
    }
    return sb.toString()
}
