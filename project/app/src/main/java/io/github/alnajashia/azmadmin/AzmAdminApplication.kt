package io.github.alnajashia.azmadmin

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Installs the TLS and certificate providers the wireless-ADB client
 * needs: Conscrypt supplies TLS 1.3 for the ADB pairing/connect channels
 * (the ADB daemon requires it), and BouncyCastle generates the manager's
 * self-signed ADB identity certificate.
 */
class AzmAdminApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
