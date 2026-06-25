package com.triggerbee.sdk.internal

import com.triggerbee.sdk.models.ClosedWidgetEntry

/** Test double for [SessionStore] — keeps everything in volatile fields, no Android deps. */
internal class InMemorySessionStore : SessionStore {
    private var uid: Long? = null
    private var identifier: String? = null
    private var closedWidgets: List<ClosedWidgetEntry> = emptyList()
    private var audienceState: VisitorAudienceState = VisitorAudienceState()

    override suspend fun getUid(): Long? = uid
    override suspend fun setUid(uid: Long) { this.uid = uid }
    override suspend fun getIdentifier(): String? = identifier
    override suspend fun setIdentifier(identifier: String) { this.identifier = identifier }
    override suspend fun getClosedWidgets(): List<ClosedWidgetEntry> = closedWidgets
    override suspend fun setClosedWidgets(entries: List<ClosedWidgetEntry>) {
        this.closedWidgets = entries.toList()
    }
    override suspend fun getAudienceState(): VisitorAudienceState = audienceState
    override suspend fun setAudienceState(state: VisitorAudienceState) { this.audienceState = state }
}
