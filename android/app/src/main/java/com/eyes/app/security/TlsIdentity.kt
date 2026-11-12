package com.eyes.app.security

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/** Paire de clés + certificat auto-signé du téléphone, créés une fois (PKCS12 dans [dir]). */
class TlsIdentity(private val dir: File) {
    companion object {
        const val ALIAS = "eyes"
        // Fichier privé de l'application : ce mot de passe ne protège rien de plus.
        val PASSWORD = "eyes-local".toCharArray()
        private const val FILE = "eyes-tls.p12"
        private const val VALIDITY_MS = 20L * 365 * 24 * 3600 * 1000
        private val bc = BouncyCastleProvider()
    }

    private val file = File(dir, FILE)
    private var cached: KeyStore? = null

    @Synchronized
    fun keyStore(): KeyStore {
        cached?.let { return it }
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { ks.load(it, PASSWORD) }
        } else {
            create(ks)
        }
        cached = ks
        return ks
    }

    private fun create(ks: KeyStore) {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=Eyes")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()), Date(now - 86_400_000), Date(now + VALIDITY_MS), name, kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(kp.private)
        val cert: X509Certificate = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, PASSWORD, arrayOf(cert))
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.outputStream().use { ks.store(it, PASSWORD) }
        if (!tmp.renameTo(file)) error("impossible d'enregistrer l'identité TLS")
    }

    fun sha256(): String {
        val der = keyStore().getCertificate(ALIAS).encoded
        return MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
    }
}
