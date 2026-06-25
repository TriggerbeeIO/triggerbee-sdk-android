package com.triggerbee.sdk.internal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.triggerbee.sdk.models.ClosedWidgetEntry
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent visitor state — uid, identifier, closed widgets, and audience-relevant state
 * (goals, landing-page query params) used for realtime audience matching before the data
 * lands in the Visitors database. Extracted as an interface so tests can swap in an
 * in-memory impl without spinning up DataStore.
 */
internal interface SessionStore {
    suspend fun getUid(): Long?
    suspend fun setUid(uid: Long)
    suspend fun getIdentifier(): String?
    suspend fun setIdentifier(identifier: String)
    suspend fun getClosedWidgets(): List<ClosedWidgetEntry>
    suspend fun setClosedWidgets(entries: List<ClosedWidgetEntry>)
    suspend fun getAudienceState(): VisitorAudienceState
    suspend fun setAudienceState(state: VisitorAudienceState)
}

/**
 * Production [SessionStore] backed by a single DataStore Preferences file. Replaces the
 * proof-of-concept SDK's mix of DataStore + SharedPreferences.
 */
internal class DataStoreSessionStore(context: Context) : SessionStore {

    private val dataStore: DataStore<Preferences> = context.dataStoreInstance

    override suspend fun getUid(): Long? = dataStore.data.first()[UID_KEY]
        ?.takeIf { it != 0L }

    override suspend fun setUid(uid: Long) {
        dataStore.edit { it[UID_KEY] = uid }
    }

    override suspend fun getIdentifier(): String? = dataStore.data.first()[IDENTIFIER_KEY]
        ?.takeIf { it.isNotBlank() }

    override suspend fun setIdentifier(identifier: String) {
        dataStore.edit { it[IDENTIFIER_KEY] = identifier }
    }

    override suspend fun getClosedWidgets(): List<ClosedWidgetEntry> {
        val raw = dataStore.data.first()[CLOSED_WIDGETS_KEY] ?: return emptyList()
        return raw.mapNotNull(ClosedWidgetEntry::decode)
    }

    override suspend fun setClosedWidgets(entries: List<ClosedWidgetEntry>) {
        dataStore.edit { prefs ->
            prefs[CLOSED_WIDGETS_KEY] = entries.map { it.encode() }.toSet()
        }
    }

    override suspend fun getAudienceState(): VisitorAudienceState {
        val raw = dataStore.data.first()[AUDIENCE_STATE_KEY] ?: return VisitorAudienceState()
        return try {
            JSON.decodeFromString(raw)
        } catch (_: SerializationException) {
            // Schema drift from a previous SDK version — drop the stale blob, start fresh.
            VisitorAudienceState()
        }
    }

    override suspend fun setAudienceState(state: VisitorAudienceState) {
        dataStore.edit { it[AUDIENCE_STATE_KEY] = JSON.encodeToString(state) }
    }

    private companion object {
        val UID_KEY = longPreferencesKey("uid")
        val IDENTIFIER_KEY = stringPreferencesKey("identifier")
        val CLOSED_WIDGETS_KEY = stringSetPreferencesKey("closed_widgets")
        val AUDIENCE_STATE_KEY = stringPreferencesKey("audience_state")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private val Context.dataStoreInstance: DataStore<Preferences> by preferencesDataStore(name = "triggerbee")
