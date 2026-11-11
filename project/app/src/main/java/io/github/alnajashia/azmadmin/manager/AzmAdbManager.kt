package io.github.alnajashia.azmadmin.manager

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The manager's own ADB identity for wireless debugging, in the same role
 * Shizuku's AdbKey plays: an RSA-2048 key pair plus a self-signed X.509
 * certificate, generated once and persisted in the app's private files.
 * The ADB daemon records this key when the user pairs with the 6-digit
 * code from the wireless-debugging screen, and trusts it for later
 * connections — pairing is what authorizes this app to talk to adbd, and
 * the user can revoke it at any time in Developer options.
 */
class AzmAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(android.os.Build.VERSION.SDK_INT)
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "adb_key.pk8")
        val certFile = File(dir, "adb_cert.der")
        if (keyFile.isFile && certFile.isFile) {
            key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certFile.inputStream())
        } else {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            val pair = generator.generateKeyPair()
            key = pair.private
            cert = selfSigned(pair)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key

    override fun getCertificate(): Certificate = cert

    override fun getDeviceName(): String = "AzmAdmin"

    private fun selfSigned(pair: KeyPair): Certificate {
        val principal = X500Principal("CN=AzmAdmin")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            principal,
            BigInteger.valueOf(now),
            Date(now - 24L * 60 * 60 * 1000),
            Date(now + 10L * 365 * 24 * 60 * 60 * 1000),
            principal,
            pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    companion object {
        @Volatile
        private var instance: AzmAdbManager? = null

        fun getInstance(context: Context): AzmAdbManager =
            instance ?: synchronized(this) {
                instance ?: AzmAdbManager(context.applicationContext).also { instance = it }
            }
    }
}
