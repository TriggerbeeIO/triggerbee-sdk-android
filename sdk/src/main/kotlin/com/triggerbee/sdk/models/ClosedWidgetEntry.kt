package com.triggerbee.sdk.models

/**
 * In-memory + persisted record of a widget the visitor has closed. Sent on the next
 * pageload/recheck so the backend can apply the matching repetition rule (e.g.
 * "don't show again for 7 days"). SDK-internal — the persistence format and field
 * shape are not part of the public 1.0 contract.
 */
internal data class ClosedWidgetEntry(
    val widgetId: Int,
    val closedTime: Long,
    val reason: String?,
    val pageviews: Int,
) {
    /**
     * Encode as `widgetId|closedTime|pageviews|reason` for DataStore's string-set storage.
     * Pipe-separated because reasons are short enum-like strings without pipes.
     */
    fun encode(): String =
        "$widgetId|$closedTime|$pageviews|${reason ?: ""}"

    companion object {
        fun decode(raw: String): ClosedWidgetEntry? {
            val parts = raw.split('|', limit = 4)
            if (parts.size != 4) return null
            val widgetId = parts[0].toIntOrNull() ?: return null
            val closedTime = parts[1].toLongOrNull() ?: return null
            val pageviews = parts[2].toIntOrNull() ?: return null
            val reason = parts[3].takeIf { it.isNotEmpty() }
            return ClosedWidgetEntry(widgetId, closedTime, reason, pageviews)
        }
    }
}
