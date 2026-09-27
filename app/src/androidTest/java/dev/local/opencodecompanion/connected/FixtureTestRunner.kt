package dev.local.opencodecompanion.connected

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import dev.local.opencodecompanion.CompanionApplication
import dev.local.opencodecompanion.client.FixtureTransports
import dev.local.opencodecompanion.client.session.AndroidSessionCoordinatorFactory
import dev.local.opencodecompanion.client.session.SessionCoordinator
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates

class FixtureTestRunner : AndroidJUnitRunner() {
    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        // Test window only: prevent idle sleep without changing lock-screen or device settings.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun newApplication(
        loader: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(loader, FixtureApplication::class.java.name, context)
}

/** The generated CA exists only in instrumentation input and this test process. */
class FixtureApplication : CompanionApplication() {
    override fun createCoordinator(
        application: Application,
        scope: CoroutineScope,
    ): SessionCoordinator {
        val args = InstrumentationRegistry.getArguments()
        val certificate =
            args.getString("fixtureCertificate")
                ?: return super.createCoordinator(application, scope)
        val bytes = android.util.Base64.decode(certificate, android.util.Base64.NO_WRAP)
        val parsed =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes))
        require(parsed is X509Certificate)
        val builder = HandshakeCertificates.Builder().addTrustedCertificate(parsed)
        args.getString("fixtureCertificate2")?.let { second ->
            val secondBytes = android.util.Base64.decode(second, android.util.Base64.NO_WRAP)
            val secondCertificate =
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(secondBytes))
            require(secondCertificate is X509Certificate)
            builder.addTrustedCertificate(secondCertificate)
        }
        val trust = builder.build()
        val client =
            OkHttpClient.Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .build()
        return AndroidSessionCoordinatorFactory.create(
            application,
            scope,
            FixtureTransports.reads(client),
            FixtureTransports.sessions(client),
            FixtureTransports.streams(client),
            databaseName = requireNotNull(args.getString("fixtureDatabase")),
        )
    }
}
