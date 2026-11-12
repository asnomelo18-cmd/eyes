package com.eyes.app.pairing

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class PairedPc(val pcId: String, val pcName: String, val tokenHash: String, val pairedAt: Long)

/** PC jumelés, et PC retirés (pour répondre `revoked` plutôt que `unknown`). */
interface PairedPcStore {
    suspend fun all(): List<PairedPc>
    suspend fun put(p: PairedPc)
    suspend fun remove(pcId: String)
    suspend fun revokedIds(): Set<String>
    suspend fun setRevoked(pcId: String, revoked: Boolean)
    val changes: Flow<List<PairedPc>>
}

private val Context.pairingDataStore by preferencesDataStore(name = "pairings")

class DataStorePairedPcStore(private val context: Context) : PairedPcStore {
    private val pcsKey = stringPreferencesKey("pcs")
    private val revokedKey = stringPreferencesKey("revoked")
    private val listSer = ListSerializer(PairedPc.serializer())
    private val setSer = SetSerializer(String.serializer())

    private fun decodePcs(raw: String?): List<PairedPc> =
        raw?.let { runCatching { Json.decodeFromString(listSer, it) }.getOrNull() } ?: emptyList()

    private fun decodeSet(raw: String?): Set<String> =
        raw?.let { runCatching { Json.decodeFromString(setSer, it) }.getOrNull() } ?: emptySet()

    override val changes: Flow<List<PairedPc>> = context.pairingDataStore.data.map { decodePcs(it[pcsKey]) }

    override suspend fun all(): List<PairedPc> = changes.first()

    override suspend fun put(p: PairedPc) {
        context.pairingDataStore.edit { prefs ->
            val list = decodePcs(prefs[pcsKey]).filterNot { it.pcId == p.pcId } + p
            prefs[pcsKey] = Json.encodeToString(listSer, list)
        }
    }

    override suspend fun remove(pcId: String) {
        context.pairingDataStore.edit { prefs ->
            prefs[pcsKey] = Json.encodeToString(listSer, decodePcs(prefs[pcsKey]).filterNot { it.pcId == pcId })
        }
    }

    override suspend fun revokedIds(): Set<String> = decodeSet(context.pairingDataStore.data.first()[revokedKey])

    override suspend fun setRevoked(pcId: String, revoked: Boolean) {
        context.pairingDataStore.edit { prefs ->
            val current = decodeSet(prefs[revokedKey])
            prefs[revokedKey] = Json.encodeToString(setSer, if (revoked) current + pcId else current - pcId)
        }
    }
}
