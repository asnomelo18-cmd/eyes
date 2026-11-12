package com.eyes.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TlsIdentityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun tlsFingerprintStableAcrossInstances() {
        val dir = tmp.newFolder()
        val a = TlsIdentity(dir).sha256()
        val b = TlsIdentity(dir).sha256()
        assertEquals(a, b)
        assertTrue(Regex("[0-9a-f]{64}").matches(a))
    }

    @Test
    fun differentDevicesHaveDifferentFingerprints() {
        assertNotEquals(TlsIdentity(tmp.newFolder()).sha256(), TlsIdentity(tmp.newFolder()).sha256())
    }

    @Test
    fun keyStoreHasKeyAndCert() {
        val ks = TlsIdentity(tmp.newFolder()).keyStore()
        val alias = ks.aliases().toList().single()
        assertTrue(ks.isKeyEntry(alias))
        assertTrue(ks.getCertificate(alias) != null)
    }
}
