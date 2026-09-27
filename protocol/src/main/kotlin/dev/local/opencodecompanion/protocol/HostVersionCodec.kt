package dev.local.opencodecompanion.protocol

/** The legacy health route identifies the build; V2 /api/health does not. */
object HostVersionCodec {
    const val SUPPORTED_VERSION = "1.18.32"

    fun decode(body: String): String =
        with(V2BoundedJson) {
            val value = root(body)
            if (!value.boolean("healthy")) throw V2WireException("Host is not healthy")
            id(value.string("version"))
        }
}
