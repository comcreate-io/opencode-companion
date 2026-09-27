package dev.local.opencodecompanion.connected

import android.app.Application
import dev.local.opencodecompanion.client.session.SessionCoordinator
import kotlinx.coroutines.CoroutineScope

/** A test Application can supply a local HTTPS fixture before MainActivity is created. */
interface ConnectedGraphFactory {
    fun createCoordinator(application: Application, scope: CoroutineScope): SessionCoordinator
}
