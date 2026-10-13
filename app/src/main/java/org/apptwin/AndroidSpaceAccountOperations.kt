package org.apptwin

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.Intent
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.client.ipc.VActivityManager
import java.util.concurrent.TimeUnit
import org.apptwin.accounts.SpaceAccountSync
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupHealth
import org.apptwin.runtime.VirtualRuntimeController

/** Android implementation of the SpaceAccountOperations feature boundary. */
internal class AndroidSpaceAccountOperations(
    private val application: Application,
    private val groupStore: FileGroupStore,
    private val runtimeController: VirtualRuntimeController,
) : SpaceAccountOperations {
    override suspend fun addSpaceAccount(groupId: String, type: String) {
        val userId = requireNotNull(prepareAccountApps(groupId).environmentBinding).internalId
        awaitVisibleAccountSession()
        require(VAccountManager.get().getAuthenticatorTypes(userId).any { it.type == type }) { "此空間不支援這個帳號類型" }
        val future = VAccountManager.get().addAccount(userId,type,null,null,null,null,null,null)
        try {
            val result = future.getResult(30, TimeUnit.SECONDS)
            val intent = result.getParcelable<Intent>(AccountManager.KEY_INTENT)
            if (intent != null) check(VActivityManager.get().startActivity(intent, userId) >= 0) { "無法開啟空間帳號登入" }
        } finally { if (!future.isDone) future.cancel(true) }
    }

    override suspend fun synchronizeSpaceAccount(groupId: String, account: SpaceAccountEntry, authority: String): String {
        val userId = requireNotNull(prepareAccountApps(groupId).environmentBinding).internalId
        awaitVisibleAccountSession()
        require(VAccountManager.get().getAccounts(userId,account.type).any { it.name == account.name }) { "此空間帳號已不存在" }
        return SpaceAccountSync.synchronize(userId, Account(account.name,account.type),authority).status
    }

    override suspend fun removeSpaceAccount(groupId: String, account: SpaceAccountEntry): AccountRemovalResult {
        val userId = requireNotNull(prepareAccountApps(groupId).environmentBinding).internalId
        awaitVisibleAccountSession()
        val future = VAccountManager.get().removeAccount(userId, Account(account.name,account.type), null)
        try {
            val result = future.getResult(35, TimeUnit.SECONDS)
            val intent = result.getParcelable<Intent>(AccountManager.KEY_INTENT)
            if (intent != null) {
                check(VActivityManager.get().startActivity(intent, userId) >= 0) { "無法開啟空間帳號移除確認" }
                return AccountRemovalResult.CONFIRMATION_OPENED
            }
            return if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) AccountRemovalResult.REMOVED else AccountRemovalResult.REJECTED
        } finally { if (!future.isDone) future.cancel(true) }
    }

    internal fun prepareAccountApps(groupId: String): Group {
        val group = requireNotNull(groupStore.find(groupId)) { "空間已不存在" }
        check(group.health == GroupHealth.HEALTHY) { "空間目前無法管理帳號" }
        group.apps.forEach { runtimeController.preparePackageForAccounts(group, it) }
        val current = requireNotNull(groupStore.find(groupId)) { "空間已不存在" }
        check(current.health == GroupHealth.HEALTHY && current.environmentBinding == group.environmentBinding) { "空間狀態已變更" }
        return current
    }

    private suspend fun awaitVisibleAccountSession() {
        val host = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val visibleHost = checkNotNull(org.apptwin.runtime.mainActivityLaunchHosts.current()) {
                "請在 AppTwin 畫面管理空間帳號"
            }
            org.apptwin.runtime.DaemonWorkloadAuthorization.startFromVisibleHost(visibleHost)
            visibleHost
        }
        val ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            org.apptwin.runtime.DaemonWorkloadAuthorization.awaitVisibleSessionReady(application, 5_000L)
        }
        check(ready) { "AppTwin 背景服務尚未就緒，請確認背景服務設定" }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            check(org.apptwin.runtime.mainActivityLaunchHosts.current() === host) { "AppTwin 畫面已離開" }
        }
    }
}
