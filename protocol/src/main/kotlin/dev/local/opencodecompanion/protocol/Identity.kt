package dev.local.opencodecompanion.protocol

@JvmInline
value class MachineId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class ProjectId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class SessionKey(val machineId: MachineId, val sessionId: SessionId)

data class ProjectKey(val machineId: MachineId, val projectId: ProjectId)
