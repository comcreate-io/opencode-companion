package dev.local.opencodecompanion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DemoMachine(val id: String, val name: String, val address: String, val detail: String)

data class DemoSession(
    val id: String,
    val machineId: String,
    val projectId: String,
    val title: String,
    val project: String,
    val updated: String,
)

data class DemoChange(val path: String, val status: String, val added: Int, val removed: Int)

data class ProofContent(
    val prompt: String,
    val response: String,
    val conclusion: String,
    val toolName: String,
    val toolFile: String,
    val toolDetail: String,
    val approvalTitle: String,
    val approvalRequestId: String,
    val approvalCommand: String,
    val modelLabel: String,
    val diffContext: String,
    val diffAdded: List<String>,
)

enum class ProofPage {
    MACHINES,
    SESSIONS,
    CONVERSATION,
    CHANGES,
}

enum class ProofConnection {
    READY,
    RECONNECTING,
    UNKNOWN,
}

private data class ApprovalKey(
    val destination: Triple<String, String, String>,
    val requestId: String,
)

/** Presentation only. Debug fixtures and their interactions are supplied by src/debug. */
@Composable
fun CompanionProof(
    machines: List<DemoMachine>,
    sessions: List<DemoSession>,
    changes: List<DemoChange>,
    content: ProofContent,
    modifier: Modifier = Modifier,
    initialPage: ProofPage = ProofPage.CONVERSATION,
    initialConnection: ProofConnection = ProofConnection.READY,
) {
    require(machines.isNotEmpty()) { "The proof needs at least one synthetic machine" }
    require(
        machines.all { machine -> sessions.any { session -> session.machineId == machine.id } }
    ) {
        "Every synthetic machine needs a session"
    }
    require(sessions.all { session -> machines.any { it.id == session.machineId } }) {
        "Every synthetic session needs a machine"
    }
    require(machines.map { it.id }.distinct().size == machines.size) {
        "Synthetic machine IDs must be unique"
    }
    require(sessions.map { it.machineId to it.id }.distinct().size == sessions.size) {
        "Synthetic session IDs must be unique within a machine"
    }
    var page by remember { mutableStateOf(initialPage) }
    var machine by remember { mutableStateOf(machines.first()) }
    var session by remember {
        mutableStateOf(sessions.first { it.machineId == machines.first().id })
    }
    var connection by remember { mutableStateOf(initialConnection) }
    var toolExpanded by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateMapOf<Triple<String, String, String>, String>() }
    val approvalChoices = remember { mutableStateMapOf<ApprovalKey, String>() }
    val destination = Triple(machine.id, session.projectId, session.id)
    val approvalKey = ApprovalKey(destination, content.approvalRequestId)
    var demoNotice by remember { mutableStateOf<String?>(null) }
    val c = CompanionTheme.colors
    BackHandler(enabled = page != ProofPage.CONVERSATION) { page = ProofPage.CONVERSATION }

    Column(modifier.background(c.background).safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth()
                .background(c.layer1)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "OPENCODE  /  MOBILE PROOF",
                    color = c.faint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    machine.name + "  /  " + session.project,
                    color = c.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextAction("Switch", onClick = { page = ProofPage.MACHINES })
        }
        Hairline()
        if (page == ProofPage.CONVERSATION) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction("Sessions", onClick = { page = ProofPage.SESSIONS })
                Spacer(Modifier.width(8.dp))
                Text(
                    session.title,
                    color = c.text,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextAction("Changes", onClick = { page = ProofPage.CHANGES })
            }
            Hairline()
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (page) {
                ProofPage.MACHINES ->
                    MachinePage(machines, machine) {
                        machine = it
                        session = sessions.first { candidate -> candidate.machineId == it.id }
                        page = ProofPage.SESSIONS
                        connection = ProofConnection.READY
                        demoNotice = null
                    }
                ProofPage.SESSIONS ->
                    SessionPage(machine, sessions.filter { it.machineId == machine.id }, session) {
                        session = it
                        page = ProofPage.CONVERSATION
                        demoNotice = null
                    }
                ProofPage.CONVERSATION -> {
                    ConnectionBanner(connection) { connection = ProofConnection.READY }
                    ConversationPage(
                        machine.name,
                        session.project,
                        content,
                        toolExpanded,
                        { toolExpanded = !toolExpanded },
                        approvalChoices[approvalKey],
                    ) { choice ->
                        approvalChoices[approvalKey] = choice
                        demoNotice = "Preview only · $choice was not sent to a host"
                    }
                    demoNotice?.let { Notice(it) }
                }
                ProofPage.CHANGES -> ChangesPage(changes, content)
            }
        }
        if (page == ProofPage.CONVERSATION) {
            Hairline()
            Composer(
                machine.name,
                session.project,
                content.modelLabel,
                drafts[destination].orEmpty(),
                { drafts[destination] = it },
                connection == ProofConnection.READY,
            ) {
                demoNotice = "Preview only · message was not sent to a host"
            }
        } else {
            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextAction("Back to conversation", onClick = { page = ProofPage.CONVERSATION })
                TextAction(
                    "Connection states",
                    onClick = {
                        connection =
                            when (connection) {
                                ProofConnection.READY -> ProofConnection.RECONNECTING
                                ProofConnection.RECONNECTING -> ProofConnection.UNKNOWN
                                ProofConnection.UNKNOWN -> ProofConnection.READY
                            }
                        page = ProofPage.CONVERSATION
                    },
                )
            }
        }
    }
}

@Composable
fun DisconnectedShell(modifier: Modifier = Modifier) {
    val c = CompanionTheme.colors
    Column(modifier.background(c.background).safeDrawingPadding().padding(24.dp)) {
        Text("OpenCode", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(28.dp))
        Text(
            "No machine connected",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = c.text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The native client is being built. Connection setup and live sessions are not available in this build.",
            color = c.muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun MachinePage(
    machines: List<DemoMachine>,
    selected: DemoMachine,
    onSelect: (DemoMachine) -> Unit,
) {
    PageHeading("Machines", "Choose where the session lives")
    machines.forEach { machine ->
        SelectRow(machine.name, machine.address + " · " + machine.detail, machine == selected) {
            onSelect(machine)
        }
    }
    Notice("Synthetic machines · debug visual proof")
}

@Composable
private fun SessionPage(
    machine: DemoMachine,
    sessions: List<DemoSession>,
    selected: DemoSession,
    onSelect: (DemoSession) -> Unit,
) {
    PageHeading("Sessions", machine.name)
    sessions.forEach { session ->
        SelectRow(session.title, session.project + " · " + session.updated, session == selected) {
            onSelect(session)
        }
    }
    Notice("Synthetic sessions · debug visual proof")
}

@Composable
private fun ConversationPage(
    machine: String,
    project: String,
    content: ProofContent,
    expanded: Boolean,
    onExpand: () -> Unit,
    approvalChoice: String?,
    onApproval: (String) -> Unit,
) {
    val c = CompanionTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) {
        Text(
            "TODAY  ·  DEBUG FIXTURE",
            color = c.faint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(22.dp))
        Text("You", color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(content.prompt, color = c.text, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(26.dp))
        Text("OpenCode", color = c.accentText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(content.response, color = c.text, fontSize = 15.sp, lineHeight = 23.sp)
        Spacer(Modifier.height(14.dp))
        ToolRow(content, expanded, onExpand)
        Spacer(Modifier.height(18.dp))
        Text(content.conclusion, color = c.text, fontSize = 15.sp, lineHeight = 23.sp)
        Spacer(Modifier.height(20.dp))
        ApprovalCard(machine, project, content, approvalChoice, onApproval)
    }
}

@Composable
private fun ToolRow(content: ProofContent, expanded: Boolean, onExpand: () -> Unit) {
    val c = CompanionTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(c.layer1)
            .border(1.dp, c.border, RoundedCornerShape(6.dp))
    ) {
        Row(
            Modifier.fillMaxWidth()
                .height(52.dp)
                .clickable(onClick = onExpand)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸", color = c.muted, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(content.toolName, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "  ·  " + content.toolFile.substringAfterLast('/'),
                color = c.muted,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("done", color = c.success, fontSize = 12.sp)
        }
        if (expanded) {
            Hairline()
            Text(
                content.toolFile,
                Modifier.padding(12.dp),
                color = c.faint,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                content.toolDetail,
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
                color = c.muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun ApprovalCard(
    machine: String,
    project: String,
    content: ProofContent,
    choice: String?,
    onChoose: (String) -> Unit,
) {
    val c = CompanionTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(c.warningBackground)
            .border(1.dp, c.warning.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(14.dp)
    ) {
        Text(
            "PERMISSION REQUEST  ·  SYNTHETIC",
            color = c.warning,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            content.approvalTitle,
            color = c.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "$machine · $project\n${content.approvalCommand}",
            color = c.muted,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (choice == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Reject", false) { onChoose("Reject") }
                ActionButton("Allow once", true) { onChoose("Allow once") }
            }
        } else {
            Text(
                "$choice selected in preview. No response was sent.",
                color = c.warning,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ChangesPage(changes: List<DemoChange>, content: ProofContent) {
    val c = CompanionTheme.colors
    var selected by remember { mutableStateOf(changes.first()) }
    PageHeading("Changes", "Read-only review · synthetic diff")
    changes.forEach { change ->
        SelectRow(
            change.path,
            "${change.status}   +${change.added}  −${change.removed}",
            change == selected,
        ) {
            selected = change
        }
    }
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(c.layer1)
            .border(1.dp, c.border, RoundedCornerShape(5.dp))
    ) {
        Text(
            selected.path,
            Modifier.fillMaxWidth().padding(12.dp),
            color = c.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
        Hairline()
        Text(
            "@@ -18,6 +18,8 @@",
            Modifier.padding(start = 12.dp, top = 10.dp),
            color = c.faint,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            content.diffContext,
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            color = c.muted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        content.diffAdded.forEach { line ->
            Text(
                line,
                Modifier.fillMaxWidth()
                    .background(c.success.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                color = c.success,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun Composer(
    machine: String,
    project: String,
    model: String,
    draft: String,
    onDraft: (String) -> Unit,
    ready: Boolean,
    onPreviewSend: () -> Unit,
) {
    val c = CompanionTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.layer1).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$machine / $project",
                color = c.muted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(model, color = c.accentText, fontSize = 11.sp)
        }
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(c.background)
                .border(1.dp, c.border, RoundedCornerShape(7.dp))
                .padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                modifier =
                    Modifier.weight(1f).height(68.dp).padding(top = 7.dp).semantics {
                        contentDescription = "Message draft"
                    },
                textStyle = TextStyle(color = c.text, fontSize = 15.sp, lineHeight = 21.sp),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty())
                            Text("Message OpenCode…", color = c.faint, fontSize = 15.sp)
                        inner()
                    }
                },
            )
            ActionButton(
                "Send",
                ready && draft.isNotBlank(),
                enabled = ready && draft.isNotBlank(),
                onClick = onPreviewSend,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            if (ready) "Debug preview · Send shows a local notice only"
            else "Sending unavailable until the host is ready",
            color = c.faint,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ConnectionBanner(state: ProofConnection, onRetry: () -> Unit) {
    if (state == ProofConnection.READY) return
    val c = CompanionTheme.colors
    val unknown = state == ProofConnection.UNKNOWN
    Row(
        Modifier.fillMaxWidth()
            .background(if (unknown) c.dangerBackground else c.warningBackground)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (unknown) "Outcome unknown" else "Reconnecting",
                color = if (unknown) c.danger else c.warning,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (unknown)
                    "A request may have reached the host. Check status before sending again."
                else "Saved content is available. Checking the host…",
                color = c.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        TextAction("Retry", onRetry)
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    val c = CompanionTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp)
    ) {
        Text(title, color = c.text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = c.muted, fontSize = 13.sp)
    }
}

@Composable
private fun SelectRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val c = CompanionTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .height(68.dp)
            .clickable(onClick = onClick)
            .background(if (selected) c.layer1 else c.background)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) c.accent else c.border)
        )
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.text,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = c.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", color = c.faint, fontSize = 22.sp)
    }
    Hairline()
}

@Composable
private fun Notice(text: String) {
    val c = CompanionTheme.colors
    Text(
        text,
        Modifier.fillMaxWidth().padding(16.dp),
        color = c.faint,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(CompanionTheme.colors.border))
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    val c = CompanionTheme.colors
    Box(
        Modifier.height(48.dp)
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = c.accentText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionButton(
    label: String,
    prominent: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = CompanionTheme.colors
    val bg = if (prominent) c.accent else c.layer2
    Box(
        Modifier.height(48.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bg.copy(alpha = if (enabled) 1f else 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (prominent) c.onAccent else c.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
