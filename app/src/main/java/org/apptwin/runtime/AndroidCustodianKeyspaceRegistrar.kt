package org.apptwin.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import com.lody.virtual.client.hook.proxies.keystore.CustodianKeyspaceState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.apptwin.custodian.contract.CustodianContract
import org.apptwin.custodian.contract.ICustodianService
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp

internal interface CustodianKeyspaceRegistrar {
    fun prepare(group: Group, app: GroupApp)

    fun isPreparedForPureLaunch(group: Group, app: GroupApp): Boolean

    fun retainForArchive(group: Group, packageName: String): String

    fun cancelArchiveRetention(group: Group)

    fun sealArchive(
        sourceSpaceId: String,
        archiveId: String,
        packageName: String,
        keyspaceId: String,
        archiveSha256: String,
    )

    fun resolveArchivedOwner(
        archiveId: String,
        packageName: String,
        keyspaceId: String,
        archiveSha256: String,
    ): String?

    fun claimArchive(
        archiveId: String,
        currentOwnerSpaceId: String,
        destinationGroup: Group,
        packageName: String,
        keyspaceId: String,
        archiveSha256: String,
    )

    fun reassignArchive(
        archiveId: String,
        currentOwnerSpaceId: String,
        destinationSpaceId: String,
        packageName: String,
        keyspaceId: String,
        archiveSha256: String,
    )
}

/** Registers LINE's stable keyspace before the first guest process starts. */
internal class AndroidCustodianKeyspaceRegistrar(context: Context) : CustodianKeyspaceRegistrar {
    private val appContext = context.applicationContext

    override fun prepare(group: Group, app: GroupApp) {
        val binding = requireNotNull(group.environmentBinding) { "Space environment is missing" }
        val existing = CustodianKeyspaceState.readForUser(binding.internalId)
        // Existing production Spaces keep their proven legacy aliases until an explicit migration.
        if (!CustodianActivationPolicy.shouldPrepare(app.packageName, existing != null, app.state)) {
            return
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Custodian registration must not block the main thread"
        }

        verifyInstalledCustodian()
        val keyspace = withService { service ->
            check(service.protocolVersion == CustodianContract.PROTOCOL_VERSION) {
                "Custodian protocol is incompatible"
            }
            check(service.healthStatus == CustodianContract.HEALTH_READY) {
                "Custodian store is unavailable"
            }
            if (existing == null) {
                service.registerKeyspace(group.id, app.packageName)
            } else {
                check(existing.ownerSpaceId == group.id) { "Custodian owner does not match Space" }
                service.resolveKeyspace(group.id, app.packageName)
            }
        }
        check(!keyspace.isNullOrBlank()) { "Custodian keyspace is unavailable" }
        existing?.let { check(it.keyspaceId == keyspace) { "Custodian keyspace changed unexpectedly" } }
        CustodianKeyspaceState.writeForUser(binding.internalId, group.id, keyspace)
    }

    override fun isPreparedForPureLaunch(group: Group, app: GroupApp): Boolean {
        val binding = group.environmentBinding ?: return false
        val existing = CustodianKeyspaceState.readForUser(binding.internalId)
        if (!CustodianActivationPolicy.shouldPrepare(app.packageName, existing != null, app.state)) {
            return true
        }
        return existing != null &&
            existing.ownerSpaceId == group.id &&
            existing.keyspaceId.isNotBlank()
    }

    override fun retainForArchive(group: Group, packageName: String): String {
        val binding = requireNotNull(group.environmentBinding) { "Space environment is missing" }
        val state = requireNotNull(CustodianKeyspaceState.readForUser(binding.internalId)) {
            "Space 沒有可封存的 Custodian keyspace"
        }
        check(state.ownerSpaceId == group.id) { "Custodian owner does not match Space" }
        CustodianKeyspaceState.writeRetainedForUser(
            binding.internalId,
            packageName,
            state.keyspaceId,
        )
        return state.keyspaceId
    }

    override fun cancelArchiveRetention(group: Group) {
        val userId = group.environmentBinding?.internalId ?: return
        val marker = CustodianKeyspaceState.retainedFileForUser(userId)
        check(!marker.exists() || marker.delete()) { "無法取消 Custodian 存檔保留" }
    }

    override fun sealArchive(
        sourceSpaceId: String,
        archiveId: String,
        packageName: String,
        keyspaceId: String,
        archiveSha256: String,
    ) {
        val sealed = withService { service ->
            service.sealArchive(
                sourceSpaceId,
                archiveId,
                packageName,
                keyspaceId,
                archiveSha256,
            )
        }
        check(sealed) { "Custodian 拒絕封存 reservation" }
    }

    override fun resolveArchivedOwner(
        archiveId: String,
        packageName: String,
        keyspaceId: String,
        archiveSha256: String,
    ): String? = withService { service ->
        service.resolveArchivedOwner(archiveId, packageName, keyspaceId, archiveSha256)
    }

    override fun claimArchive(
        archiveId: String,
        currentOwnerSpaceId: String,
        destinationGroup: Group,
        packageName: String,
        keyspaceId: String,
        archiveSha256: String,
    ) {
        val destinationBinding = requireNotNull(destinationGroup.environmentBinding) {
            "Destination Space environment is missing"
        }
        reassignArchive(
            archiveId,
            currentOwnerSpaceId,
            destinationGroup.id,
            packageName,
            keyspaceId,
            archiveSha256,
        )
        CustodianKeyspaceState.writeForUser(
            destinationBinding.internalId,
            destinationGroup.id,
            keyspaceId,
        )
        CustodianKeyspaceState.writeRetainedForUser(
            destinationBinding.internalId,
            packageName,
            keyspaceId,
        )
    }

    override fun reassignArchive(
        archiveId: String,
        currentOwnerSpaceId: String,
        destinationSpaceId: String,
        packageName: String,
        keyspaceId: String,
        archiveSha256: String,
    ) {
        val claimed = withService { service ->
            service.claimArchive(
                archiveId,
                currentOwnerSpaceId,
                destinationSpaceId,
                packageName,
                keyspaceId,
                archiveSha256,
            )
        }
        check(claimed) { "Custodian archive ownership 已變更或正在使用" }
    }

    private fun verifyInstalledCustodian() {
        val packageManager = appContext.packageManager
        check(
            packageManager.checkSignatures(
                appContext.packageName,
                CustodianContract.CUSTODIAN_PACKAGE,
            ) == PackageManager.SIGNATURE_MATCH,
        ) { "Custodian signer does not match AppTwin" }
    }

    private fun <T> withService(block: (ICustodianService) -> T): T {
        verifyInstalledCustodian()
        val result = AtomicReference<ICustodianService?>()
        val failure = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                result.set(ICustodianService.Stub.asInterface(binder))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit

            override fun onNullBinding(name: ComponentName) {
                failure.set(IllegalStateException("Custodian returned no service"))
                connected.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                failure.set(IllegalStateException("Custodian binding died"))
                connected.countDown()
            }
        }
        val intent = Intent(CustodianContract.ACTION_BIND)
            .setPackage(CustodianContract.CUSTODIAN_PACKAGE)
        check(appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "Custodian service cannot be bound"
        }
        try {
            check(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Custodian service connection timed out"
            }
            failure.get()?.let { throw it }
            return block(requireNotNull(result.get()) { "Custodian service is unavailable" })
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_SECONDS = 5L
    }
}
