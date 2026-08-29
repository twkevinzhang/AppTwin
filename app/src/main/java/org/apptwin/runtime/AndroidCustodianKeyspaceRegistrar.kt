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

internal fun interface CustodianKeyspaceRegistrar {
    fun prepare(group: Group, app: GroupApp)
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
