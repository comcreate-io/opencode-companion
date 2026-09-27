package dev.local.opencodecompanion.connected

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.opencodecompanion.CompanionTheme
import dev.local.opencodecompanion.client.SendState
import dev.local.opencodecompanion.client.session.ConnectionState
import dev.local.opencodecompanion.client.session.SessionUiState
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.V2CreateSessionCommand
import dev.local.opencodecompanion.protocol.V2ModelSelection
import dev.local.opencodecompanion.protocol.V2PermissionReply
import dev.local.opencodecompanion.protocol.V2PermissionRequest
import dev.local.opencodecompanion.protocol.V2QuestionRequest
import dev.local.opencodecompanion.protocol.V2TextFragment
import dev.local.opencodecompanion.protocol.transcript.Kind
import dev.local.opencodecompanion.protocol.transcript.PartKey
import dev.local.opencodecompanion.protocol.transcript.presentationOutput

private enum class Page {
    Machines,
    Sessions,
    Conversation,
    NewSession,
    Changes,
}

/** One native screen tree; every side effect is delegated to the retained coordinator owner. */
@Composable
fun ConnectedScreen(viewModel: ConnectedViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(Page.Machines) }
    val conversationScroll = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(state.selectedSession) {
        if (state.selectedSession != null) {
            page = Page.Conversation
            following = true
        }
    }
    BackHandler(page != Page.Machines) {
        page =
            when (page) {
                Page.Conversation,
                Page.NewSession -> Page.Sessions
                Page.Changes -> Page.Conversation
                else -> Page.Machines
            }
    }
    val c = CompanionTheme.colors
    Column(modifier.fillMaxSize().background(c.background).safeDrawingPadding().imePadding()) {
        val machine = state.machines.firstOrNull { it.id == state.selectedMachine }
        val session = state.sessions.firstOrNull { it.key == state.selectedSession }
        Row(
            Modifier.fillMaxWidth()
                .background(c.layer1)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "OPENCODE  /  MOBILE",
                    color = c.faint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    listOfNotNull(machine?.displayName, session?.summary?.directory)
                        .joinToString("  /  ")
                        .ifBlank { "Machines" },
                    color = c.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (
                state.connection is ConnectionState.Unavailable ||
                    state.connection == ConnectionState.Cached
            ) {
                SmallAction("Reconnect", enabled = state.selectedMachine != null) {
                    viewModel.foreground()
                }
            }
            SmallAction("Machines") { page = Page.Machines }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        if (notice != null) {
            Row(
                Modifier.fillMaxWidth().background(c.warningBackground).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    notice.orEmpty(),
                    Modifier.weight(1f).padding(vertical = 12.dp),
                    color = c.text,
                    fontSize = 13.sp,
                )
                SmallAction("Dismiss") { viewModel.clearNotice() }
            }
        }
        val status =
            when (val connection = state.connection) {
                ConnectionState.Cached -> "Cached · host state not verified"
                ConnectionState.Connecting -> "Connecting to host…"
                ConnectionState.Ready -> "Ready · host current"
                is ConnectionState.Unavailable -> connection.reason.explanation()
            }
        Text(
            status,
            Modifier.fillMaxWidth()
                .background(c.layer1)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            color = if (state.connection == ConnectionState.Ready) c.success else c.muted,
            fontSize = 12.sp,
        )
        if (state.selectedSession in state.interrupting || state.settlingRequestIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(c.warningBackground).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Waiting for the host to confirm the action. Recheck before deciding whether to retry.",
                    Modifier.weight(1f).padding(vertical = 8.dp),
                    color = c.text,
                    fontSize = 12.sp,
                )
                SmallAction("Recheck", enabled = !state.busy) { viewModel.recheckPendingActions() }
            }
        }
        when (page) {
            Page.Machines ->
                MachinesPage(
                    state,
                    viewModel,
                    Modifier.weight(1f),
                    onOpen = { page = Page.Sessions },
                )
            Page.Sessions ->
                SessionsPage(
                    state,
                    viewModel,
                    Modifier.weight(1f),
                    onNew = { page = Page.NewSession },
                )
            Page.NewSession ->
                NewSessionPage(state, viewModel, Modifier.weight(1f)) { page = Page.Sessions }
            Page.Conversation ->
                ConversationPage(
                    state,
                    viewModel,
                    conversationScroll,
                    following,
                    { following = it },
                    Modifier.weight(1f),
                    onBack = { page = Page.Sessions },
                    onChanges = {
                        page = Page.Changes
                        viewModel.refreshChanges()
                    },
                )
            Page.Changes -> ChangesPage(state, Modifier.weight(1f)) { page = Page.Conversation }
        }
    }
}

@Composable
private fun MachinesPage(
    state: SessionUiState,
    viewModel: ConnectedViewModel,
    modifier: Modifier,
    onOpen: () -> Unit,
) {
    val c = CompanionTheme.colors
    var name by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("opencode") }
    var password by remember { mutableStateOf("") }
    var accepted by remember { mutableStateOf(false) }
    var editingCredential by remember { mutableStateOf<MachineId?>(null) }
    LazyColumn(
        modifier.testTag("machine-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("heading") { Heading("Machines", "Choose the host that owns this work") }
        items(state.machines, key = { it.id.value }) { machine ->
            Panel(
                Modifier.fillMaxWidth().clickable {
                    viewModel.selectMachine(machine.id)
                    onOpen()
                }
            ) {
                Text(
                    machine.displayName,
                    color = c.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    machine.origin,
                    color = c.muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (machine.id == state.selectedMachine)
                    Text("Selected", color = c.accentText, fontSize = 12.sp)
                SmallAction("Update password", enabled = !state.busy) {
                    editingCredential = machine.id
                }
            }
            if (editingCredential == machine.id) {
                CredentialEditor(
                    machine.displayName,
                    machine.origin,
                    state.busy,
                    onSave = { user, secret ->
                        viewModel.replaceCredential(machine.id, user, secret)
                        editingCredential = null
                    },
                    onCancel = { editingCredential = null },
                )
            }
        }
        item("setup") {
            Panel {
                Heading("Add a machine", "HTTPS origin and host access")
                Input("Machine name", name, { name = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Input(
                    "https://host.example",
                    origin,
                    { origin = it },
                    Modifier.fillMaxWidth(),
                    KeyboardType.Uri,
                )
                Spacer(Modifier.height(8.dp))
                Input("Username", username, { username = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Input(
                    "Server password",
                    password,
                    { password = it },
                    Modifier.fillMaxWidth(),
                    secret = true,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "This host uses a shared password. Rotating it disconnects every client; individual device revocation is unavailable.",
                    color = c.muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(accepted, role = Role.Checkbox) { accepted = it }
                        .sizeIn(minHeight = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (accepted) "☑" else "☐", color = c.accentText, fontSize = 20.sp)
                    Text(" I understand and accept", color = c.text, fontSize = 14.sp)
                }
                Text(
                    "Candidate testing access. Use a host and credential you control.",
                    color = c.faint,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryAction(
                    "Save machine",
                    enabled =
                        name.isNotBlank() &&
                            origin.isNotBlank() &&
                            password.isNotBlank() &&
                            accepted &&
                            !state.busy,
                ) {
                    val captured = password
                    password = "" // Never keep a submitted credential in composable state.
                    viewModel.addMachine(
                        name.trim(),
                        origin.trim(),
                        username.trim(),
                        captured,
                        accepted,
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialEditor(
    name: String,
    origin: String,
    busy: Boolean,
    onSave: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    val c = CompanionTheme.colors
    var username by remember(origin) { mutableStateOf("opencode") }
    var password by remember(origin) { mutableStateOf("") }
    Panel {
        Heading("Update access · $name", origin)
        Text(
            "Enter the password already configured on this host. Sessions and drafts stay on this machine. This does not change the host's password.",
            color = c.muted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Input("Updated username", username, { username = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Input(
            "Updated server password",
            password,
            { password = it },
            Modifier.fillMaxWidth(),
            secret = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallAction("Cancel", onClick = onCancel)
            PrimaryAction(
                "Save updated password",
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
            ) {
                val captured = password
                password = ""
                onSave(username.trim(), captured)
            }
        }
    }
}

@Composable
private fun SessionsPage(
    state: SessionUiState,
    viewModel: ConnectedViewModel,
    modifier: Modifier,
    onNew: () -> Unit,
) {
    val c = CompanionTheme.colors
    val machine = state.machines.firstOrNull { it.id == state.selectedMachine }
    LazyColumn(
        modifier.testTag("session-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("heading") {
            Heading("Sessions", machine?.displayName ?: "Select a machine")
            if (machine != null)
                SmallAction(
                    "New session",
                    enabled = state.connection == ConnectionState.Ready,
                    onClick = onNew,
                )
        }
        if (state.sessions.isEmpty())
            item("empty") {
                Text(
                    if (machine == null) "Choose a machine to see its sessions."
                    else "No sessions are saved for this machine.",
                    color = c.muted,
                    fontSize = 14.sp,
                )
            }
        items(state.sessions, key = { it.key.machineId.value + ":" + it.key.sessionId.value }) {
            session ->
            Panel(
                Modifier.fillMaxWidth()
                    .testTag("session:${session.key.sessionId.value}")
                    .clickable { viewModel.selectSession(session.key) }
            ) {
                Text(
                    session.summary.title,
                    color = c.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${session.summary.directory} · ${session.summary.projectId.value}",
                    color = c.muted,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NewSessionPage(
    state: SessionUiState,
    viewModel: ConnectedViewModel,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    val c = CompanionTheme.colors
    var agent by remember(state.selectedMachine) { mutableStateOf<String?>(null) }
    var model by remember(state.selectedMachine) { mutableStateOf<V2ModelSelection?>(null) }
    LazyColumn(
        modifier.testTag("session-catalog"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        item("heading") { Heading("New session", "Choices reported by the selected host") }
        item("agent-label") { Text("Agent", color = c.muted, fontSize = 12.sp) }
        item("agent-default") { SmallAction("Host default") { agent = null } }
        items(
            state.agents.filter {
                !it.hidden && it.mode != dev.local.opencodecompanion.protocol.V2AgentMode.Subagent
            },
            key = { "agent:${it.id}" },
        ) { option ->
            SmallAction((if (agent == option.id) "✓ " else "") + option.id) { agent = option.id }
        }
        item("model-label") { Text("Model", color = c.muted, fontSize = 12.sp) }
        item("model-default") { SmallAction("Host default") { model = null } }
        items(state.models.filter { it.enabled }, key = { "model:${it.providerId}:${it.id}" }) {
            option ->
            val selection = V2ModelSelection(option.providerId, option.id)
            SmallAction((if (model == selection) "✓ " else "") + option.name) { model = selection }
        }
        item("submit") {
            PrimaryAction(
                "Create session",
                enabled = state.connection == ConnectionState.Ready && !state.busy,
            ) {
                viewModel.createSession(V2CreateSessionCommand(agentId = agent, model = model))
                onBack()
            }
        }
    }
}

private data class TimelineRow(
    val key: String,
    val label: String,
    val body: String,
    val detail: String? = null,
)

private fun SessionUiState.timeline(): List<TimelineRow> {
    val transcript = transcript ?: return emptyList()
    val rows = linkedMapOf<String, TimelineRow>()
    transcript.seen.toSortedMap().forEach { (_, event) ->
        when (val kind = event.kind) {
            is Kind.Prompt -> {
                val key = "prompt:${kind.messageId}"
                rows[key] = TimelineRow(key, "You", kind.text)
            }
            is Kind.TextEnded -> {
                val key = "text:${kind.messageId}:${kind.textId}"
                rows[key] = TimelineRow(key, "Assistant", kind.text)
            }
            is Kind.ToolCalled -> {
                val key = "tool:${kind.messageId}:${kind.callId}"
                rows[key] = TimelineRow(key, "Tool · ${kind.tool}", "Running")
            }
            is Kind.ToolSuccess -> {
                val tool = transcript.tools[PartKey(kind.messageId, kind.callId)]
                val key = "tool:${kind.messageId}:${kind.callId}"
                val output = tool.presentationOutput()
                rows[key] =
                    TimelineRow(
                        key,
                        "Tool · ${tool?.name ?: kind.callId}",
                        output?.takeIf { it.isNotBlank() }?.take(240) ?: "Completed",
                        output?.takeIf { it.isNotBlank() },
                    )
            }
            is Kind.ToolFailed -> {
                val tool = transcript.tools[PartKey(kind.messageId, kind.callId)]
                val key = "tool:${kind.messageId}:${kind.callId}"
                val output = tool.presentationOutput()
                rows[key] =
                    TimelineRow(
                        key,
                        "Tool · ${tool?.name ?: kind.callId}",
                        output?.let { "Failed · ${it.take(240)}" } ?: "Failed",
                        output,
                    )
            }
            is Kind.StepFailed -> {
                val key = "step:${kind.messageId}"
                rows[key] = TimelineRow(key, "Assistant", "Step failed · ${kind.error.message}")
            }
            else -> Unit
        }
    }
    return rows.values.toList()
}

@Composable
private fun ConversationPage(
    state: SessionUiState,
    viewModel: ConnectedViewModel,
    listState: LazyListState,
    following: Boolean,
    setFollowing: (Boolean) -> Unit,
    modifier: Modifier,
    onBack: () -> Unit,
    onChanges: () -> Unit,
) {
    val c = CompanionTheme.colors
    val session = state.sessions.firstOrNull { it.key == state.selectedSession }
    val machine = state.machines.firstOrNull { it.id == state.selectedMachine }
    var composer by remember(state.selectedSession) { mutableStateOf("") }
    LaunchedEffect(state.draft?.key, state.draft?.cleared) {
        state.draft?.let { composer = if (it.cleared) "" else it.text }
    }
    var expandedTool by remember(state.selectedSession) { mutableStateOf<String?>(null) }
    val rows = remember(state.transcript) { state.timeline() }
    var autoScrolling by remember(state.selectedSession) { mutableStateOf(false) }
    val bottomTolerance = with(LocalDensity.current) { 12.dp.roundToPx() }
    // Scroll only while the reader is already following the bottom.
    LaunchedEffect(listState, state.selectedSession, bottomTolerance) {
        snapshotFlow {
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()
                val atBottom =
                    last != null &&
                        last.index == layout.totalItemsCount - 1 &&
                        last.offset + last.size <= layout.viewportEndOffset + bottomTolerance
                (listState.isScrollInProgress && !autoScrolling) to atBottom
            }
            .collect { (readerScrolling, atBottom) -> if (readerScrolling) setFollowing(atBottom) }
    }
    LaunchedEffect(rows, state.transientText, following) {
        if (following && listState.layoutInfo.totalItemsCount > 0) {
            autoScrolling = true
            try {
                withFrameNanos {}
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                listState.scrollToItem(lastIndex)
                withFrameNanos {}
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()
                if (last != null && last.index == lastIndex) {
                    val overflow = last.offset + last.size - layout.viewportEndOffset
                    if (overflow > 0) listState.scrollBy(overflow.toFloat())
                }
            } finally {
                autoScrolling = false
            }
        }
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallAction("Sessions", onClick = onBack)
            Text(
                session?.summary?.title ?: "Session",
                Modifier.weight(1f),
                color = c.text,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SmallAction("Changes", onClick = onChanges)
        }
        Text(
            "${machine?.displayName ?: "No machine"}  /  ${session?.summary?.directory ?: "No project"}",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            color = c.muted,
            fontSize = 12.sp,
            maxLines = 2,
        )
        if (
            state.outgoing.any {
                it.destination.session == state.selectedSession &&
                    (it.state == SendState.OutcomeUnknown || it.state == SendState.Dispatching)
            }
        ) {
            Text(
                "A message may have been received. Waiting for host evidence; do not resend it.",
                Modifier.fillMaxWidth().background(c.warningBackground).padding(12.dp),
                color = c.text,
                fontSize = 13.sp,
            )
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                if (rows.isEmpty())
                    item("empty") {
                        Text("No conversation content yet.", color = c.muted, fontSize = 14.sp)
                    }
                items(rows, key = { it.key }) { row ->
                    Panel(
                        Modifier.fillMaxWidth()
                            .then(
                                if (row.detail != null)
                                    Modifier.clickable {
                                        expandedTool =
                                            if (expandedTool == row.key) null else row.key
                                    }
                                else Modifier
                            )
                    ) {
                        Text(
                            row.label,
                            color = c.muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(row.body, color = c.text, fontSize = 14.sp, lineHeight = 21.sp)
                        if (row.detail != null && expandedTool == row.key) {
                            Text(row.detail, color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
                items(
                    state.transientText.fragments
                        .filterValues { it is V2TextFragment.Provisional }
                        .toList(),
                    key = { "live:${it.first.assistantMessageId}:${it.first.textId}" },
                ) { (_, fragment) ->
                    Panel {
                        Text("Assistant · live", color = c.muted, fontSize = 12.sp)
                        Text(fragment.text, color = c.text, fontSize = 14.sp)
                    }
                }
            }
            if (!following)
                SmallAction(
                    "New output",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) {
                    setFollowing(true)
                }
        }
        // Long or multiple requests remain scrollable without pushing the composer off-screen.
        if (state.permissions.isNotEmpty() || state.questions.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth()
                    .weight(0.8f, fill = false)
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                state.permissions
                    .filter { it.sessionKey == state.selectedSession }
                    .forEach { request -> PermissionCard(request, state, viewModel) }
                state.questions
                    .filter { it.sessionKey == state.selectedSession }
                    .forEach { request -> QuestionCard(request, state, viewModel) }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(c.layer1)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Input(
                "Message",
                composer,
                { value ->
                    composer = value
                    viewModel.saveDraft(value)
                },
                Modifier.weight(1f),
            )
            PrimaryAction(
                "Send",
                enabled =
                    state.connection == ConnectionState.Ready &&
                        composer.isNotBlank() &&
                        !state.busy &&
                        state.draft?.text == composer,
            ) {
                viewModel.send()
            }
            if (state.selectedSession in state.active)
                SmallAction(
                    if (state.selectedSession in state.interrupting) "Stopping" else "Stop",
                    enabled =
                        state.connection == ConnectionState.Ready &&
                            !state.busy &&
                            state.selectedSession !in state.interrupting,
                ) {
                    viewModel.interrupt()
                }
        }
    }
}

@Composable
private fun ChangesPage(state: SessionUiState, modifier: Modifier, onBack: () -> Unit) {
    val c = CompanionTheme.colors
    var expanded by remember(state.selectedSession) { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("heading") {
            SmallAction("Conversation", onClick = onBack)
            Heading(
                "Changes",
                state.sessions.firstOrNull { it.key == state.selectedSession }?.summary?.directory
                    ?: "No project",
            )
        }
        if (state.changesLoading)
            item("loading") { Text("Loading working-tree changes…", color = c.muted) }
        if (state.changesProblem != null)
            item("error") { Text(state.changesProblem?.explanation().orEmpty(), color = c.danger) }
        if (
            !state.changesLoading &&
                state.changesProblem == null &&
                state.changes?.isEmpty() == true
        )
            item("empty") {
                Text("No changes reported", color = c.text, fontSize = 15.sp)
                Text(
                    "Clean and non-Git directories can both return an empty result.",
                    color = c.muted,
                    fontSize = 12.sp,
                )
            }
        items(state.changes.orEmpty(), key = { it.file }) { diff ->
            Panel(
                Modifier.fillMaxWidth().clickable {
                    expanded = if (expanded == diff.file) null else diff.file
                }
            ) {
                Text(diff.file, color = c.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "${diff.status ?: "Changed"}  +${diff.additions}  −${diff.deletions}",
                    color = c.muted,
                    fontSize = 12.sp,
                )
                if (expanded == diff.file) {
                    val description =
                        when {
                            diff.binary -> "Binary file · patch unavailable"
                            diff.patch == null -> "Patch unavailable"
                            else -> diff.patch.orEmpty()
                        }
                    Text(
                        description,
                        color = c.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    if (diff.truncated) Text("Patch truncated", color = c.warning, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    request: V2PermissionRequest,
    state: SessionUiState,
    viewModel: ConnectedViewModel,
) {
    val c = CompanionTheme.colors
    Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            "Permission · ${request.action}",
            color = c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${state.machines.firstOrNull { it.id == request.sessionKey.machineId }?.displayName ?: "Host"} · ${request.sessionKey.sessionId.value}",
            color = c.muted,
            fontSize = 12.sp,
        )
        request.resources.forEach { Text(it, color = c.muted, fontSize = 12.sp, maxLines = 3) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(
                "Reject",
                enabled =
                    state.connection == ConnectionState.Ready &&
                        !state.busy &&
                        request.id !in state.settlingRequestIds,
            ) {
                viewModel.answerPermission(request, V2PermissionReply.REJECT)
            }
            PrimaryAction(
                "Allow once",
                enabled =
                    state.connection == ConnectionState.Ready &&
                        !state.busy &&
                        request.id !in state.settlingRequestIds,
            ) {
                viewModel.answerPermission(request, V2PermissionReply.ONCE)
            }
        }
    }
}

@Composable
private fun QuestionCard(
    request: V2QuestionRequest,
    state: SessionUiState,
    viewModel: ConnectedViewModel,
) {
    val c = CompanionTheme.colors
    val selected = remember(request.id) { mutableStateMapOf<Int, Set<String>>() }
    val custom = remember(request.id) { mutableStateMapOf<Int, String>() }
    Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            "Question · ${request.sessionKey.sessionId.value}",
            color = c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        request.questions.forEachIndexed { index, question ->
            Text(question.question, color = c.text, fontSize = 14.sp)
            question.options.forEach { option ->
                val checked = option.label in selected[index].orEmpty()
                val choose = {
                    selected[index] =
                        if (question.multiple == true) {
                            if (checked) selected[index].orEmpty() - option.label
                            else selected[index].orEmpty() + option.label
                        } else setOf(option.label)
                    if (question.multiple != true) custom[index] = ""
                }
                Row(
                    Modifier.fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag("question-option:${request.id}:$index:${option.label}")
                        .then(
                            if (question.multiple == true)
                                Modifier.toggleable(checked, role = Role.Checkbox) { choose() }
                            else
                                Modifier.selectable(
                                    checked,
                                    role = Role.RadioButton,
                                    onClick = choose,
                                )
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (checked) "☑" else "☐",
                        Modifier.clearAndSetSemantics {},
                        color = c.accentText,
                        fontSize = 18.sp,
                    )
                    Text(
                        option.label,
                        Modifier.padding(start = 10.dp),
                        color = c.text,
                        fontSize = 14.sp,
                    )
                }
                if (option.description.isNotBlank())
                    Text(option.description, color = c.muted, fontSize = 12.sp)
            }
            if (question.custom == true)
                Input(
                    "Other answer",
                    custom[index].orEmpty(),
                    {
                        custom[index] = it
                        if (question.multiple != true && it.isNotBlank())
                            selected[index] = emptySet()
                    },
                    Modifier.fillMaxWidth(),
                )
        }
        val complete =
            request.questions.indices.all {
                selected[it].orEmpty().isNotEmpty() || custom[it].orEmpty().isNotBlank()
            }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(
                "Reject",
                enabled =
                    state.connection == ConnectionState.Ready &&
                        !state.busy &&
                        request.id !in state.settlingRequestIds,
            ) {
                viewModel.rejectQuestion(request)
            }
            PrimaryAction(
                "Answer",
                enabled =
                    complete &&
                        state.connection == ConnectionState.Ready &&
                        !state.busy &&
                        request.id !in state.settlingRequestIds,
            ) {
                viewModel.answerQuestion(
                    request,
                    request.questions.indices.map { index ->
                        val options =
                            request.questions[index]
                                .options
                                .map { it.label }
                                .filter { it in selected[index].orEmpty() }
                        val customAnswer = custom[index]?.takeIf { it.isNotBlank() }
                        if (request.questions[index].multiple == true)
                            options + listOfNotNull(customAnswer)
                        else if (customAnswer != null) listOf(customAnswer) else options.take(1)
                    },
                )
            }
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    val c = CompanionTheme.colors
    Column {
        Text(title, color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = c.muted, fontSize = 13.sp)
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = CompanionTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.layer1)
            .border(1.dp, c.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

@Composable
private fun Input(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    val c = CompanionTheme.colors
    BasicTextField(
        value,
        onChange,
        modifier
            .background(c.layer2, RoundedCornerShape(6.dp))
            .sizeIn(minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 13.dp)
            .semantics { contentDescription = label },
        textStyle = TextStyle(color = c.text, fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation =
            if (secret) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(label, color = c.faint, fontSize = 14.sp)
                inner()
            }
        },
    )
}

@Composable
private fun SmallAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = CompanionTheme.colors
    Box(
        modifier
            .sizeIn(minHeight = 48.dp, minWidth = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) c.accentText else c.faint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = CompanionTheme.colors
    Box(
        Modifier.sizeIn(minHeight = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) c.accent else c.layer2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) c.onAccent else c.faint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
