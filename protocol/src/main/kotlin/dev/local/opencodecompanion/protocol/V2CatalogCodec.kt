package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.JsonObject

data class V2CatalogLocation(
    val directory: String,
    val workspaceId: String?,
    val projectId: ProjectId,
    val projectDirectory: String,
)

data class V2CatalogPage<T>(val location: V2CatalogLocation, val items: List<T>)

data class V2AgentSummary(
    val id: String,
    val description: String?,
    val mode: V2AgentMode,
    val hidden: Boolean,
)

enum class V2AgentMode {
    Subagent,
    Primary,
    All,
}

data class V2ModelSummary(
    val id: String,
    val providerId: String,
    val name: String,
    val status: V2ModelStatus,
    val enabled: Boolean,
    val tools: Boolean,
)

enum class V2ModelStatus {
    Alpha,
    Beta,
    Deprecated,
    Active,
}

/** Discards `request`, `api`, and provider configuration before creating any returned DTO. */
object V2CatalogCodec {
    fun agents(body: String): V2CatalogPage<V2AgentSummary> =
        with(V2BoundedJson) {
            val root = root(body)
            val items = root.array("data")
            if (items.size > 4096) throw V2WireException("agent catalog exceeds item limit")
            V2CatalogPage(
                root.catalogLocation(),
                items.map { element ->
                    val agent =
                        element as? JsonObject ?: throw V2WireException("agent must be an object")
                    val mode =
                        when (agent.string("mode")) {
                            "subagent" -> V2AgentMode.Subagent
                            "primary" -> V2AgentMode.Primary
                            "all" -> V2AgentMode.All
                            else -> throw V2WireException("unsupported agent mode")
                        }
                    V2AgentSummary(
                        id(agent.string("id")),
                        agent.optionalString("description"),
                        mode,
                        agent.boolean("hidden"),
                    )
                },
            )
        }

    fun models(body: String): V2CatalogPage<V2ModelSummary> =
        with(V2BoundedJson) {
            val root = root(body)
            val items = root.array("data")
            if (items.size > 4096) throw V2WireException("model catalog exceeds item limit")
            V2CatalogPage(
                root.catalogLocation(),
                items.map { element ->
                    val model =
                        element as? JsonObject ?: throw V2WireException("model must be an object")
                    val status =
                        when (model.string("status")) {
                            "alpha" -> V2ModelStatus.Alpha
                            "beta" -> V2ModelStatus.Beta
                            "deprecated" -> V2ModelStatus.Deprecated
                            "active" -> V2ModelStatus.Active
                            else -> throw V2WireException("unsupported model status")
                        }
                    V2ModelSummary(
                        id(model.string("id")),
                        id(model.string("providerID")),
                        model.string("name"),
                        status,
                        model.boolean("enabled"),
                        model.obj("capabilities").boolean("tools"),
                    )
                },
            )
        }

    private fun JsonObject.catalogLocation(): V2CatalogLocation =
        with(V2BoundedJson) {
            val location = obj("location")
            val project = location.obj("project")
            V2CatalogLocation(
                path(location.string("directory")),
                location.optionalString("workspaceID")?.let(::id),
                ProjectId(id(project.string("id"))),
                path(project.string("directory")),
            )
        }
}
