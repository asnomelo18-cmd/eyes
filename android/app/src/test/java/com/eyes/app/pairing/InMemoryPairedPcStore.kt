package com.eyes.app.pairing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryPairedPcStore : PairedPcStore {
    private val state = MutableStateFlow<List<PairedPc>>(emptyList())
    private val revoked = mutableSetOf<String>()
    override val changes: Flow<List<PairedPc>> = state
    override suspend fun all() = state.value
    override suspend fun put(p: PairedPc) { state.value = state.value.filterNot { it.pcId == p.pcId } + p }
    override suspend fun remove(pcId: String) { state.value = state.value.filterNot { it.pcId == pcId } }
    override suspend fun revokedIds(): Set<String> = revoked.toSet()
    override suspend fun setRevoked(pcId: String, revoked: Boolean) {
        if (revoked) this.revoked += pcId else this.revoked -= pcId
    }
}
