package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class VcsFileDiff(
    val file: String,
    val patch: String?,
    val additions: Long,
    val deletions: Long,
    val status: String?,
    val binary: Boolean,
    val truncated: Boolean,
)

/** Working-tree snapshots only; no local paths are opened and no patch is executed. */
object VcsDiffCodec {
    private const val MAX_PATCH_CHARACTERS = 100_000

    fun directory(value: String): String = V2BoundedJson.path(value)

    fun decode(body: String): List<VcsFileDiff> =
        with(V2BoundedJson) {
            if (body.length > 1_048_576) throw V2WireException("Diff exceeds response limit")
            // The legacy endpoint returns an array; reuse the same bounded JSON parser.
            val rows = root("{\"data\":$body}").array("data")
            if (rows.size > 1000) throw V2WireException("Diff exceeds file limit")
            rows.map { element ->
                val row =
                    element as? JsonObject ?: throw V2WireException("Diff item is not an object")
                val file = row.string("file")
                if (file.isBlank() || file.length > 4096 || '\u0000' in file)
                    throw V2WireException("Diff path is invalid")
                val patch = row.optionalString("patch")
                val status = row.optionalString("status")
                if (status != null && status !in setOf("added", "deleted", "modified"))
                    throw V2WireException("Unsupported diff status")
                VcsFileDiff(
                    file = file,
                    patch = patch?.take(MAX_PATCH_CHARACTERS),
                    additions = row.count("additions"),
                    deletions = row.count("deletions"),
                    status = status,
                    binary =
                        patch?.lineSequence()?.any {
                            it.startsWith("Binary files ") && it.endsWith(" differ")
                        } == true,
                    truncated = patch != null && patch.length > MAX_PATCH_CHARACTERS,
                )
            }
        }

    private fun JsonObject.count(name: String): Long {
        val value = this[name] as? JsonPrimitive ?: throw V2WireException("Missing diff count")
        return value.longOrNull?.takeIf { !value.isString && it >= 0 }
            ?: throw V2WireException("Invalid diff count")
    }
}
