package org.apptwin.fixture

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.util.Log

/** Observable local sync, with no network or real data writes. */
class FixtureSyncService : Service() {
    private val adapter by lazy {
        object : AbstractThreadedSyncAdapter(this, false) {
            override fun onPerformSync(account: Account?, extras: Bundle?, authority: String?,
                provider: ContentProviderClient?, result: SyncResult?) {
                Log.i("FixtureSync", "perform-enter provider=${provider != null}")
                val proof = getSharedPreferences("fixture_sync_proof", MODE_PRIVATE)
                proof.edit().putInt("completed", proof.getInt("completed", 0) + 1).commit()
                result?.stats?.numUpdates = 1
                Log.i("FixtureSync", "perform-completed")
            }
        }
    }
    override fun onBind(intent: Intent?): android.os.IBinder {
        Log.i("FixtureSync", "service-bound")
        val endpoint = adapter.syncAdapterBinder
        Log.i("FixtureSync", "endpoint descriptor=${endpoint.interfaceDescriptor} class=${endpoint.javaClass.name} loader=${endpoint.javaClass.classLoader?.javaClass?.name}")
        Log.i("FixtureSync", "endpoint-methods=${endpoint.javaClass.declaredMethods.map { it.name }.sorted().joinToString(",")}")
        val contract = Class.forName("android.content.ISyncAdapter")
        Log.i("FixtureSync", "contract loader=${contract.classLoader?.javaClass?.name} methods=${contract.declaredMethods.map { it.name }.sorted().joinToString(",")}")
        val actualStub = endpoint.javaClass.superclass
        listOf("TRANSACTION_startSync", "TRANSACTION_cancelSync").forEach { name ->
            val value = runCatching {
                actualStub!!.getDeclaredField(name).apply { isAccessible = true }.getInt(null).toString()
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
            Log.i("FixtureSync", "runtime-wire stub=${actualStub?.name} loader=${actualStub?.classLoader?.javaClass?.name} field=$name value=$value")
        }
        return endpoint
    }
}
