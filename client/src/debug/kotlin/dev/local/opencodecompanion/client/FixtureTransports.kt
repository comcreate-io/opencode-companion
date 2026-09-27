package dev.local.opencodecompanion.client

import okhttp3.OkHttpClient

/** Debug-only bridge for instrumentation with an explicitly trusted disposable fixture CA. */
object FixtureTransports {
    fun reads(client: OkHttpClient): ReadOnlyV2Transport = ReadOnlyV2Transport.forTests(client)

    fun sessions(client: OkHttpClient): SessionV2Transport = SessionV2Transport.forTests(client)

    fun streams(client: OkHttpClient): SessionV2Streams = SessionV2Streams.forTests(client)
}
