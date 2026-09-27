package dev.local.opencodecompanion.protocol

/** Global SSE is a notification stream, not a durable replay cursor. */
sealed interface V2GlobalEvent {
    data object Connected : V2GlobalEvent

    data class ScopedText(val session: SessionKey, val event: V2TextEvent) : V2GlobalEvent

    data class OtherNotifications(val type: String) : V2GlobalEvent
}

object V2GlobalEventCodec {
    fun decode(body: String, machineId: MachineId): V2GlobalEvent =
        with(V2BoundedJson) {
            val root = root(body)
            val type = root.string("type")
            id(root.string("id"))
            when (type) {
                "server.connected" -> {
                    root.obj("data")
                    V2GlobalEvent.Connected
                }
                "session.next.text.delta",
                "session.next.text.ended" -> {
                    val session =
                        SessionKey(machineId, SessionId(id(root.obj("data").string("sessionID"))))
                    val decoded = V2TextEvents.decode(body, session)
                    val event =
                        (decoded as? V2TextDecode.Text)?.event
                            ?: throw V2WireException("known text event was not decoded")
                    V2GlobalEvent.ScopedText(session, event)
                }
                else -> V2GlobalEvent.OtherNotifications(type)
            }
        }
}
