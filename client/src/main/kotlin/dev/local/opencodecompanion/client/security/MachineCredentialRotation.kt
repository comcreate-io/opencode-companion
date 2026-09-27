package dev.local.opencodecompanion.client.security

import dev.local.opencodecompanion.client.storage.DurableStore
import dev.local.opencodecompanion.client.storage.MachineProfile
import dev.local.opencodecompanion.client.storage.PendingCredentialRotation
import dev.local.opencodecompanion.protocol.MachineId
import kotlinx.coroutines.CancellationException

enum class RotationBlock {
    MachineMissing,
    ScopeChanged,
    Pending,
    UnresolvedOutgoing,
}

enum class RotationFailure {
    InvalidScope,
    VaultStateUncertain,
    StorageStateChanged,
    StorageFailure,
}

sealed interface RotationOutcome {
    data class Completed(val profile: MachineProfile) : RotationOutcome

    data class RolledBack(val profile: MachineProfile) : RotationOutcome

    data class Blocked(val reason: RotationBlock) : RotationOutcome

    data class FailClosed(val reason: RotationFailure) : RotationOutcome
}

data class RotationReconciliation(val machineId: MachineId, val outcome: RotationOutcome)

/**
 * Same-origin credential replacement. The caller owns an exclusive per-machine dispatch and
 * credential lock for the whole operation; Room and Keystore cannot share one transaction. On
 * startup, reconcile pending records before exposing affected machines for dispatch.
 */
class MachineCredentialRotation(
    private val store: DurableStore,
    private val credentials: AndroidCredentialStore,
) {
    suspend fun rotate(
        machineId: MachineId,
        expectedOrigin: String,
        expectedGeneration: Long,
        newAuthorization: String,
    ): RotationOutcome = storageBoundary {
        val profile =
            store.machine(machineId)
                ?: return@storageBoundary RotationOutcome.Blocked(RotationBlock.MachineMissing)
        if (
            profile.origin != expectedOrigin ||
                profile.credentialGeneration != expectedGeneration ||
                profile.credentialReference == null ||
                expectedGeneration == Long.MAX_VALUE
        )
            return@storageBoundary RotationOutcome.Blocked(RotationBlock.ScopeChanged)
        if (store.pendingCredentialRotations().any { it.machineId == machineId })
            return@storageBoundary RotationOutcome.Blocked(RotationBlock.Pending)
        if (store.unresolvedOutgoing(machineId).isNotEmpty())
            return@storageBoundary RotationOutcome.Blocked(RotationBlock.UnresolvedOutgoing)

        val old =
            scope(machineId, profile.origin, expectedGeneration)
                ?: return@storageBoundary RotationOutcome.FailClosed(RotationFailure.InvalidScope)
        val next =
            scope(machineId, profile.origin, expectedGeneration + 1)
                ?: return@storageBoundary RotationOutcome.FailClosed(RotationFailure.InvalidScope)
        if (profile.credentialReference != old.reference)
            return@storageBoundary RotationOutcome.FailClosed(RotationFailure.InvalidScope)
        val pending =
            PendingCredentialRotation(
                machineId,
                old.origin,
                old.generation,
                old.reference,
                next.origin,
                next.generation,
                next.reference,
            )
        if (!store.beginCredentialRotation(pending))
            return@storageBoundary RotationOutcome.Blocked(RotationBlock.ScopeChanged)

        // A failed write can still have replaced the envelope; classify by authenticated readback.
        credentials.rotate(old, next, newAuthorization)
        reconcileOne(pending)
    }

    /** Every returned machine must be handled before its dispatch path is enabled. */
    suspend fun reconcilePending(): List<RotationReconciliation> =
        store.pendingCredentialRotations().map { pending ->
            RotationReconciliation(pending.machineId, storageBoundary { reconcileOne(pending) })
        }

    private suspend fun reconcileOne(pending: PendingCredentialRotation): RotationOutcome {
        if (
            pending.newOrigin != pending.oldOrigin || pending.newGeneration <= pending.oldGeneration
        )
            return RotationOutcome.FailClosed(RotationFailure.InvalidScope)
        val old =
            scope(pending.machineId, pending.oldOrigin, pending.oldGeneration)
                ?: return RotationOutcome.FailClosed(RotationFailure.InvalidScope)
        val next =
            scope(pending.machineId, pending.newOrigin, pending.newGeneration)
                ?: return RotationOutcome.FailClosed(RotationFailure.InvalidScope)
        if (pending.oldReference != old.reference || pending.newReference != next.reference)
            return RotationOutcome.FailClosed(RotationFailure.InvalidScope)
        val newRead = credentials.load(next)
        val oldRead = credentials.load(old)
        return when {
            newRead is CredentialResult.Success &&
                oldRead == CredentialResult.Failure(CredentialFailure.ScopeMismatch) -> {
                if (!store.finishCredentialRotation(pending))
                    RotationOutcome.FailClosed(RotationFailure.StorageStateChanged)
                else
                    store.machine(pending.machineId)?.let(RotationOutcome::Completed)
                        ?: RotationOutcome.FailClosed(RotationFailure.StorageStateChanged)
            }
            oldRead is CredentialResult.Success &&
                newRead == CredentialResult.Failure(CredentialFailure.ScopeMismatch) -> {
                if (!store.cancelPendingCredentialRotation(pending))
                    RotationOutcome.FailClosed(RotationFailure.StorageStateChanged)
                else
                    store.machine(pending.machineId)?.let(RotationOutcome::RolledBack)
                        ?: RotationOutcome.FailClosed(RotationFailure.StorageStateChanged)
            }
            else -> RotationOutcome.FailClosed(RotationFailure.VaultStateUncertain)
        }
    }

    private fun scope(machineId: MachineId, origin: String, generation: Long): CredentialScope? =
        (CredentialScope.create(machineId, origin, generation) as? CredentialResult.Success)?.value

    private suspend fun storageBoundary(action: suspend () -> RotationOutcome): RotationOutcome =
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RotationOutcome.FailClosed(RotationFailure.StorageFailure)
        }
}
