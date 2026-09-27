package dev.local.opencodecompanion

import android.app.Application
import dev.local.opencodecompanion.client.session.AndroidSessionCoordinatorFactory
import dev.local.opencodecompanion.client.session.SessionCoordinator
import dev.local.opencodecompanion.connected.ConnectedGraphFactory
import kotlinx.coroutines.CoroutineScope

/** Test applications can replace this graph before Activity creation. */
open class CompanionApplication : Application(), ConnectedGraphFactory {
    override fun createCoordinator(
        application: Application,
        scope: CoroutineScope,
    ): SessionCoordinator = AndroidSessionCoordinatorFactory.create(application, scope)
}
