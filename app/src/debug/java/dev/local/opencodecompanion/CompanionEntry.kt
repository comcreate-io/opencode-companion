package dev.local.opencodecompanion

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

// These fixtures exist only in the debug source set. No server data or network behavior is implied.
private val machines =
    listOf(
        DemoMachine("host-laptop", "laptop", "laptop.example.test", "ready"),
        DemoMachine("host-studio", "studio", "studio.example.test", "last seen yesterday"),
    )
private val sessions =
    listOf(
        DemoSession(
            "session-drafts",
            "host-laptop",
            "project-companion",
            "Draft restore review",
            "companion-demo",
            "12 min ago",
        ),
        DemoSession(
            "session-build",
            "host-laptop",
            "project-sample",
            "Inspect build output",
            "sample-app",
            "yesterday",
        ),
        DemoSession(
            "session-first",
            "host-studio",
            "project-notes",
            "First session",
            "notes",
            "Sep 24",
        ),
    )
private val changes =
    listOf(
        DemoChange("app/src/main/DraftStore.kt", "Modified", 12, 3),
        DemoChange("app/src/main/Composer.kt", "Modified", 6, 1),
        DemoChange("app/src/test/DraftStoreTest.kt", "Added", 34, 0),
    )
private val content =
    ProofContent(
        prompt = "Check the saved draft behavior and show me the files that changed.",
        response =
            "I found the draft restore path. The state is scoped to the selected machine and session, so switching context keeps each draft separate.",
        conclusion =
            "The visible changes are limited to the composer and its state holder. Review the file list before accepting anything.",
        toolName = "Read",
        toolFile = "src/main/.../DraftStore.kt",
        toolDetail = "Read 84 lines. Draft key combines machine, project and session identifiers.",
        approvalTitle = "Run test command?",
        approvalRequestId = "request-test-command",
        approvalCommand = "./gradlew testDebugUnitTest",
        modelLabel = "Build  ·  GPT",
        diffContext = "  fun draftKey(machine: MachineId, session: SessionId)",
        diffAdded =
            listOf("+ require(machine.value.isNotBlank())", "+ return SessionKey(machine, session)"),
    )

@Composable
fun CompanionEntry(modifier: Modifier = Modifier) {
    CompanionProof(machines, sessions, changes, content, modifier)
}

@Preview(name = "Proof light", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PreviewLight() {
    CompanionTheme(dark = false) { CompanionEntry() }
}

@Preview(name = "Proof dark", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PreviewDark() {
    CompanionTheme(dark = true) { CompanionEntry() }
}

@Preview(name = "Machines", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PreviewMachines() {
    CompanionTheme(dark = false) {
        CompanionProof(machines, sessions, changes, content, initialPage = ProofPage.MACHINES)
    }
}

@Preview(name = "Changes dark", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PreviewChanges() {
    CompanionTheme(dark = true) {
        CompanionProof(machines, sessions, changes, content, initialPage = ProofPage.CHANGES)
    }
}

@Preview(name = "Reconnect", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PreviewReconnect() {
    CompanionTheme(dark = false) {
        CompanionProof(
            machines,
            sessions,
            changes,
            content,
            initialConnection = ProofConnection.RECONNECTING,
        )
    }
}

@Preview(name = "Outcome unknown", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PreviewUnknown() {
    CompanionTheme(dark = true) {
        CompanionProof(
            machines,
            sessions,
            changes,
            content,
            initialConnection = ProofConnection.UNKNOWN,
        )
    }
}
