package dev.local.opencodecompanion.client

import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Common credential boundary for finite calls and independently owned SSE calls. */
internal object V2HttpBoundary {
    /** OkHttp can suppress a handshake failure beneath an address-fallback ConnectException. */
    fun hasTlsFailure(error: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending.add(error)
        var inspected = 0
        while (pending.isNotEmpty() && inspected < 32) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            inspected++
            if (current is SSLException) return true
            current.cause?.let(pending::addLast)
            for (suppressed in current.suppressed) {
                if (pending.size + inspected >= 32) break
                pending.addLast(suppressed)
            }
        }
        return false
    }

    enum class Lifetime {
        FINITE,
        GLOBAL_STREAM,
        DURABLE_STREAM,
    }

    fun harden(
        base: OkHttpClient,
        lifetime: Lifetime,
        globalIdleTimeout: Duration = Duration.ofSeconds(30),
    ): OkHttpClient =
        base
            .newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
                authenticator(Authenticator.NONE)
                proxyAuthenticator(Authenticator.NONE)
                cookieJar(CookieJar.NO_COOKIES)
                cache(null)
            }
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(
                when (lifetime) {
                    Lifetime.FINITE -> Duration.ofSeconds(10)
                    Lifetime.GLOBAL_STREAM -> globalIdleTimeout
                    // OpenCode 1.18.32 durable tails have no initial frame or heartbeat.
                    // Lifecycle cancellation and the bounded global/finite reads own liveness.
                    Lifetime.DURABLE_STREAM -> Duration.ZERO
                }
            )
            .callTimeout(Duration.ofSeconds(if (lifetime == Lifetime.FINITE) 20 else 0))
            .build()

    fun authorizedRequest(destination: ReadDestination, url: HttpUrl): Request.Builder {
        require(
            url.scheme == "https" &&
                url.host == destination.origin.host &&
                url.port == destination.origin.port
        ) {
            "Request origin differs from captured destination"
        }
        return Request.Builder().url(url).header("Authorization", destination.authorizationHeader())
    }
}
