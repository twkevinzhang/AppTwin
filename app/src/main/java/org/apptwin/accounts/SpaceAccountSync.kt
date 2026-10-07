package org.apptwin.accounts

import android.accounts.Account
import android.app.IServiceConnection
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ISyncAdapter
import android.content.ISyncContext
import android.content.Intent
import android.content.SyncResult
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.hook.secondary.ServiceConnectionDelegate
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.helper.compat.CompatServiceConnection
import com.lody.virtual.helper.compat.SyncAdapterWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** User-requested sync only. No periodic work or host ContentResolver fallback. */
object SpaceAccountSync {
    data class Adapter(val authority: String, val packageName: String, val serviceName: String)
    data class Result(val status: String, val success: Boolean)
    private const val DEADLINE_MS = 120_000L
    private val active = Mutex()

    fun adapters(userId: Int, accountType: String): List<Adapter> {
        val namespace = "http://schemas.android.com/apk/res/android"
        return VPackageManager.get().queryIntentServices(
            Intent("android.content.SyncAdapter"), null, PackageManager.GET_META_DATA, userId,
        ).mapNotNull { resolved ->
            val service = resolved.serviceInfo ?: return@mapNotNull null
            val xml = service.metaData?.getInt("android.content.SyncAdapter", 0) ?: 0
            if (xml == 0) return@mapNotNull null
            runCatching {
                val resources = VirtualCore.get().getResources(service.packageName)
                resources.getXml(xml).use { parser ->
                    fun attribute(name: String): String? {
                        val resource = parser.getAttributeResourceValue(namespace, name, 0)
                        return if (resource != 0) resources.getString(resource)
                        else parser.getAttributeValue(namespace, name)
                    }
                    while (parser.eventType != XmlPullParser.START_TAG &&
                        parser.eventType != XmlPullParser.END_DOCUMENT) parser.next()
                    if (parser.name != "sync-adapter" ||
                        attribute("accountType") != accountType ||
                        !parser.getAttributeBooleanValue(namespace, "userVisible", true)) return@use null
                    val authority = attribute("contentAuthority")
                    if (authority.isNullOrBlank()) null
                    else Adapter(authority, service.packageName, service.name)
                }
            }.getOrNull()
        }.distinct().sortedBy { it.authority }
    }

    suspend fun synchronize(userId: Int, account: Account, authority: String): Result =
        withContext(Dispatchers.IO) {
            if (!active.tryLock()) return@withContext Result("另一項同步正在執行", false)
            try {
                val choices = adapters(userId, account.type).filter { it.authority == authority }
                if (choices.size != 1) return@withContext Result("沒有唯一可用的同步服務", false)
                if (VAccountManager.get().getAccounts(userId, account.type).none { it == account })
                    return@withContext Result("帳號已不存在", false)
                execute(userId, account, choices.single())
            } finally { active.unlock() }
        }

    private suspend fun execute(userId: Int, account: Account, adapter: Adapter): Result {
        val completed = CompletableDeferred<Result>()
        val expected = ComponentName(adapter.packageName, adapter.serviceName)
        val started = AtomicBoolean(false)
        val syncContext = object : ISyncContext.Stub() {
            override fun sendHeartbeat() = Unit // Heartbeats never extend the hard deadline.
            override fun onFinished(result: SyncResult?) {
                val success = result != null && !result.hasError()
                Log.i("SpaceAccountSync", "finished user=$userId success=$success")
                completed.complete(Result(if (success) "同步完成" else "同步服務回報失敗", success))
            }
        }
        val callback = object : CompatServiceConnection() {
            override fun onConnected(name: ComponentName?, service: IBinder?, dead: Boolean) {
                Log.i("SpaceAccountSync", "callback user=$userId exact=${name == expected} dead=$dead binder=${service != null}")
                if (completed.isCompleted) return
                if (dead || name != expected || service == null) {
                    completed.complete(Result("同步服務連線中斷", false)); return
                }
                if (!started.compareAndSet(false, true)) return
                try {
                    if (VAccountManager.get().getAccounts(userId, account.type).none { it == account }) {
                        completed.complete(Result("帳號已不存在", false)); return
                    }
                    val endpoint = service
                    val descriptor = endpoint.interfaceDescriptor
                    Log.i("SpaceAccountSync", "endpoint user=$userId descriptor=$descriptor class=${endpoint.javaClass.name}")
                    check(descriptor == "android.content.ISyncAdapter") { "Unexpected sync service interface" }
                    Log.i("SpaceAccountSync", "start-sync-begin user=$userId")
                    check(VActivityManager.get().service.startScopedAccountSync(userId, expected, account,
                        adapter.authority, syncContext.asBinder())) { "Scoped sync request denied" }
                    Log.i("SpaceAccountSync", "start-sync-returned user=$userId")
                } catch (failure: Exception) {
                    Log.w("SpaceAccountSync", "start-sync-error user=$userId class=${failure.javaClass.simpleName}")
                    completed.complete(Result("無法啟動同步服務", false))
                }
            }
        }
        val connection = ServiceConnectionDelegate.getDelegate(callback)
        var bound = false
        try {
            Log.i("SpaceAccountSync", "bind-begin user=$userId component=$expected")
            val compiledContract = ISyncAdapter.Stub::class.java
            Log.i("SpaceAccountSync", "compiled-contract loader=${compiledContract.classLoader?.javaClass?.name} methods=${ISyncAdapter::class.java.declaredMethods.map { it.name }.sorted().joinToString(",")}")
            listOf("TRANSACTION_startSync", "TRANSACTION_cancelSync").forEach { field ->
                val value = runCatching { compiledContract.getDeclaredField(field).apply { isAccessible = true }.getInt(null).toString() }
                    .getOrElse { "unavailable:${it.javaClass.simpleName}" }
                Log.i("SpaceAccountSync", "compiled-wire field=$field value=$value")
            }
            bound = VActivityManager.get().bindService(
                null, null, Intent("android.content.SyncAdapter").setComponent(expected),
                null, connection, Context.BIND_AUTO_CREATE, userId,
            ) > 0
            Log.i("SpaceAccountSync", "bind-result user=$userId bound=$bound")
            if (!bound) return Result("無法連接同步服務", false)
            return withTimeoutOrNull(DEADLINE_MS) { completed.await() }
                ?: Result("同步逾時，已取消", false)
        } catch (_: Exception) {
            return Result("同步服務失敗", false)
        } finally {
            if (!completed.isCompleted) {
                completed.complete(Result("同步已取消", false))
            }
            runCatching { VActivityManager.get().service.cancelScopedAccountSync(userId, expected, syncContext.asBinder()) }
            if (bound) runCatching { VActivityManager.get().service.unbindService(connection, userId) }
            ServiceConnectionDelegate.removeDelegate(callback)
        }
    }
}
