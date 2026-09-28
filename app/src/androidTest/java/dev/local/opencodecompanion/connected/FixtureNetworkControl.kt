package dev.local.opencodecompanion.connected

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Fault injection for the generated-CA instrumentation graph only. No request content is kept. */
internal class FixtureNetworkControl {
    private val online = AtomicBoolean(true)
    private val blocked = AtomicInteger()
    private val prompts = AtomicInteger()
    private val unexpectedCall = AtomicReference<CountDownLatch?>()
    private val acceptedStreams = Collections.synchronizedMap(IdentityHashMap<Call, String>())
    private val sentStreamRequests = Collections.synchronizedMap(IdentityHashMap<Call, String>())
    private var client: OkHttpClient? = null
    private val socketFactory = GuardedSocketFactory()

    fun install(builder: OkHttpClient.Builder): OkHttpClient {
        check(client == null) { "Fixture network control already installed" }
        val installed =
            builder.socketFactory(socketFactory).eventListenerFactory { listener() }.build()
        check(installed.socketFactory === socketFactory)
        client = installed
        return installed
    }

    private fun listener() =
        object : EventListener() {
            override fun callStart(call: Call) {
                unexpectedCall.get()?.countDown()
                val request = call.request()
                val segments = request.url.pathSegments
                if (
                    request.method == "POST" &&
                        segments.size == 4 &&
                        segments[0] == "api" &&
                        segments[1] == "session" &&
                        segments[3] == "prompt"
                )
                    prompts.incrementAndGet()
            }

            override fun responseHeadersEnd(call: Call, response: Response) {
                if (
                    response.code in 200..299 &&
                        response
                            .header("Content-Type")
                            ?.substringBefore(';')
                            ?.trim()
                            ?.equals("text/event-stream", ignoreCase = true) == true
                )
                    acceptedStreams[call] = call.request().url.encodedPath
            }

            override fun requestHeadersEnd(call: Call, request: Request) {
                val path = request.url.encodedPath
                if (
                    path == "/api/event" ||
                        (path.startsWith("/api/session/") && path.endsWith("/event"))
                ) {
                    sentStreamRequests[call] = path
                }
            }

            override fun callEnd(call: Call) {
                acceptedStreams.remove(call)
                sentStreamRequests.remove(call)
            }

            override fun callFailed(call: Call, ioe: IOException) {
                acceptedStreams.remove(call)
                sentStreamRequests.remove(call)
            }
        }

    val promptAttempts: Int
        get() = prompts.get()

    val blockedConnections: Int
        get() = blocked.get()

    val runningCalls: Int
        get() = requireClient().dispatcher.runningCallsCount()

    val queuedCalls: Int
        get() = requireClient().dispatcher.queuedCallsCount()

    /** Requires accepted SSE headers and a still-running call for each exact route. */
    fun bothStreamsAccepted(sessionId: String): Boolean {
        val running =
            requireClient().dispatcher.runningCalls().filterNot { it.isCanceled() }.toSet()
        synchronized(acceptedStreams) {
            val paths = acceptedStreams.filterKeys { it in running }.values
            return "/api/event" in paths && "/api/session/$sessionId/event" in paths
        }
    }

    /**
     * An idle durable stream sends its request but may withhold response headers until an event.
     */
    fun globalAcceptedAndDurableRequested(sessionId: String): Boolean {
        val running =
            requireClient().dispatcher.runningCalls().filterNot { it.isCanceled() }.toSet()
        val globalAccepted =
            synchronized(acceptedStreams) {
                acceptedStreams.any { (call, path) -> call in running && path == "/api/event" }
            }
        val durableRequested =
            synchronized(sentStreamRequests) {
                sentStreamRequests.any { (call, path) ->
                    call in running && path == "/api/session/$sessionId/event"
                }
            }
        return globalAccepted && durableRequested
    }

    fun offline() {
        online.set(false)
        requireClient().dispatcher.cancelAll()
        requireClient().connectionPool.evictAll()
    }

    fun evictIdle() = requireClient().connectionPool.evictAll()

    fun online() {
        online.set(true)
    }

    fun watchUnexpectedCall(): CountDownLatch = CountDownLatch(1).also { unexpectedCall.set(it) }

    fun stopWatching() {
        unexpectedCall.set(null)
    }

    private fun requireClient(): OkHttpClient =
        checkNotNull(client) { "Fixture client not installed" }

    private inner class GuardedSocketFactory : SocketFactory() {
        override fun createSocket(): Socket = GuardedSocket()

        override fun createSocket(host: String, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket =
            GuardedSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }

        override fun createSocket(
            host: InetAddress,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket =
            GuardedSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }
    }

    private inner class GuardedSocket : Socket() {
        override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (!online.get()) {
                blocked.incrementAndGet()
                throw ConnectException("Fixture network unavailable")
            }
            super.connect(endpoint, timeout)
        }
    }
}
