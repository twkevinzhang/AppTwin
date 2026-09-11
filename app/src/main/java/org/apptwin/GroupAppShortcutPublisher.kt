package org.apptwin

import android.app.Application
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.PersistableBundle
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth

internal sealed interface ShortcutCreationResult {
    data object Requested : ShortcutCreationResult
    data object Updated : ShortcutCreationResult
    data object Unsupported : ShortcutCreationResult
    data class Failed(val reason: String) : ShortcutCreationResult
}

internal sealed interface ShortcutReconciliationResult {
    data class Reconciled(val updatedCount: Int) : ShortcutReconciliationResult
    data class Failed(val reason: String) : ShortcutReconciliationResult
}

internal data class ProductShortcutSpec(
    val id: String,
    val groupId: String,
    val packageName: String,
    val groupName: String,
    val appLabel: String,
    val available: Boolean,
)

internal interface ProductShortcutPlatform {
    val pinSupported: Boolean
    fun pinnedIds(): Set<String>
    fun isCurrent(spec: ProductShortcutSpec): Boolean
    fun requestPin(spec: ProductShortcutSpec): Boolean
    fun update(specs: List<ProductShortcutSpec>): Boolean
    fun enable(ids: List<String>): Boolean
    fun disable(ids: List<String>, message: String): Boolean
}

/** Publishes and repairs explicit routes to one package in one AppTwin space. */
internal class GroupAppShortcutPublisher(
    private val application: Application,
    private val platform: ProductShortcutPlatform = AndroidProductShortcutPlatform(application),
    private val appLabel: (String) -> String = { packageName ->
        runCatching {
            val info = application.packageManager.getApplicationInfo(packageName, 0)
            application.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    },
    private val sourceInstalled: (String) -> Boolean = { packageName ->
        runCatching { application.packageManager.getApplicationInfo(packageName, 0) }.isSuccess
    },
    private val pendingPinIds: () -> Set<String> = ShortcutPinState(application)::pendingIds,
) {
    fun requestPin(item: GroupAppItem): ShortcutCreationResult {
        val spec = item.toShortcutSpec()
        return runCatching {
            if (!platform.pinSupported) {
                ShortcutCreationResult.Unsupported
            } else if (
                prepareExistingShortcut(spec) &&
                platform.requestPin(spec)
            ) {
                ShortcutCreationResult.Requested
            } else {
                ShortcutCreationResult.Failed("啟動器未接受捷徑要求")
            }
        }.getOrElse { error ->
            ShortcutCreationResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Repairs only pinned IDs that current durable Group membership proves belong to AppTwin.
     * Guest-emitted and orphaned pinned shortcuts are intentionally left untouched.
     */
    fun reconcile(groups: List<Group>): ShortcutReconciliationResult = runCatching {
        val expectedById = groups.asSequence()
            .flatMap { group -> group.apps.asSequence().map { app -> group.toShortcutSpec(app) } }
            .associateBy(ProductShortcutSpec::id)
        val pendingIds = pendingPinIds()
        val repairs = platform.pinnedIds().asSequence()
            .filterNot(pendingIds::contains)
            .mapNotNull(expectedById::get)
            .filterNot(platform::isCurrent)
            .toList()
        when {
            repairs.isEmpty() -> ShortcutReconciliationResult.Reconciled(0)
            platform.enable(repairs.map(ProductShortcutSpec::id)) && platform.update(repairs) ->
                ShortcutReconciliationResult.Reconciled(repairs.size)
            else -> ShortcutReconciliationResult.Failed("啟動器暫時無法更新捷徑")
        }
    }.getOrElse { error ->
        ShortcutReconciliationResult.Failed(error.message ?: error.javaClass.simpleName)
    }

    private fun prepareExistingShortcut(spec: ProductShortcutSpec): Boolean {
        if (spec.id !in platform.pinnedIds()) return true
        if (!platform.enable(listOf(spec.id))) return false
        return platform.isCurrent(spec) || platform.update(listOf(spec))
    }

    fun disable(group: Group): ShortcutReconciliationResult = disable(
        ids = group.apps.map { app -> GroupAppLaunchContract.launchKey(group.id, app.packageName) },
        message = "此分身空間已刪除",
    )

    fun disable(groupId: String, packageName: String): ShortcutReconciliationResult = disable(
        ids = listOf(GroupAppLaunchContract.launchKey(groupId, packageName)),
        message = "此分身 App 已刪除",
    )

    private fun disable(ids: List<String>, message: String): ShortcutReconciliationResult =
        runCatching {
            val pinnedProductIds = ids.filterTo(mutableListOf()) { it in platform.pinnedIds() }
            when {
                pinnedProductIds.isEmpty() -> ShortcutReconciliationResult.Reconciled(0)
                platform.disable(pinnedProductIds, message) ->
                    ShortcutReconciliationResult.Reconciled(pinnedProductIds.size)
                else -> ShortcutReconciliationResult.Failed("啟動器暫時無法停用捷徑")
            }
        }.getOrElse { error ->
            ShortcutReconciliationResult.Failed(error.message ?: error.javaClass.simpleName)
        }

    private fun GroupAppItem.toShortcutSpec() = ProductShortcutSpec(
        id = launchKey,
        groupId = groupId,
        packageName = app.packageName,
        groupName = groupName,
        appLabel = appLabel,
        available = groupHealth == GroupHealth.HEALTHY && app.state.isShortcutAvailable(),
    )

    private fun Group.toShortcutSpec(app: GroupApp) = ProductShortcutSpec(
        id = GroupAppLaunchContract.launchKey(id, app.packageName),
        groupId = id,
        packageName = app.packageName,
        groupName = name,
        appLabel = appLabel(app.packageName),
        available = health == GroupHealth.HEALTHY &&
            app.state.isShortcutAvailable() &&
            sourceInstalled(app.packageName),
    )

    private fun GroupAppState.isShortcutAvailable(): Boolean = this in setOf(
        GroupAppState.ADDED,
        GroupAppState.ENABLED,
    )
}

private class AndroidProductShortcutPlatform(
    private val application: Application,
) : ProductShortcutPlatform {
    private val pinState = ShortcutPinState(application)

    private val manager: ShortcutManager?
        get() = application.getSystemService(ShortcutManager::class.java)

    override val pinSupported: Boolean
        get() = manager?.isRequestPinShortcutSupported == true

    override fun pinnedIds(): Set<String> = manager?.pinnedShortcuts
        .orEmpty()
        .mapTo(linkedSetOf(), ShortcutInfo::getId)

    override fun isCurrent(spec: ProductShortcutSpec): Boolean {
        val current = manager?.pinnedShortcuts
            ?.firstOrNull { shortcut -> shortcut.id == spec.id }
            ?: return false
        val expectedIntent = GroupAppLaunchContract.intent(
            application,
            spec.groupId,
            spec.packageName,
        )
        val currentIntent = current.intent ?: return false
        return current.isEnabled &&
            current.shortLabel.toString() == shortLabel(spec) &&
            current.longLabel?.toString() == longLabel(spec) &&
            current.extras?.getString(EXTRA_RENDER_FINGERPRINT) == renderFingerprint(spec) &&
            currentIntent.filterEquals(expectedIntent) &&
            currentIntent.flags == expectedIntent.flags &&
            currentIntent.getStringExtra(GroupAppLaunchContract.EXTRA_GROUP_ID) == spec.groupId &&
            currentIntent.getStringExtra(GroupAppLaunchContract.EXTRA_PACKAGE_NAME) == spec.packageName
    }

    override fun requestPin(spec: ProductShortcutSpec): Boolean {
        val shortcutManager = manager ?: return false
        val shortcut = shortcut(spec)
        val nonce = pinState.markRequested(spec.id)
        return try {
            shortcutManager.requestPinShortcut(
                shortcut,
                ShortcutPinResultReceiver.intentSender(application, spec.id, nonce),
            ).also { accepted ->
                if (!accepted) pinState.cancel(spec.id, nonce)
            }
        } catch (error: RuntimeException) {
            pinState.cancel(spec.id, nonce)
            throw error
        }
    }

    override fun update(specs: List<ProductShortcutSpec>): Boolean =
        manager?.updateShortcuts(specs.map(::shortcut)) == true

    override fun enable(ids: List<String>): Boolean {
        val shortcutManager = manager ?: return false
        shortcutManager.enableShortcuts(ids)
        return true
    }

    override fun disable(ids: List<String>, message: String): Boolean {
        val shortcutManager = manager ?: return false
        shortcutManager.disableShortcuts(ids, message)
        return true
    }

    private fun shortcut(spec: ProductShortcutSpec): ShortcutInfo = ShortcutInfo.Builder(
        application,
        spec.id,
    )
        .setShortLabel(shortLabel(spec))
        .setLongLabel(longLabel(spec))
        .setIcon(Icon.createWithBitmap(badgedIcon(spec)))
        .setIntent(GroupAppLaunchContract.intent(application, spec.groupId, spec.packageName))
        .setExtras(PersistableBundle().apply {
            putString(EXTRA_RENDER_FINGERPRINT, renderFingerprint(spec))
        })
        .build()

    private fun renderFingerprint(spec: ProductShortcutSpec): String {
        val sourceUpdateTime = runCatching {
            application.packageManager.getPackageInfo(spec.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return listOf(
            SHORTCUT_RENDER_VERSION,
            sourceUpdateTime,
            spec.groupName,
            spec.appLabel,
            spec.available,
        ).joinToString(separator = "|") { field ->
            val value = field.toString()
            "${value.length}:$value"
        }
    }

    private fun shortLabel(spec: ProductShortcutSpec): String =
        "${spec.groupName} ${spec.appLabel}".take(MAX_SHORT_LABEL_LENGTH)

    private fun longLabel(spec: ProductShortcutSpec): String = buildString {
        append(spec.groupName)
        append(" · ")
        append(spec.appLabel)
        if (!spec.available) append(" · 目前不可使用")
    }

    private fun badgedIcon(spec: ProductShortcutSpec): Bitmap {
        val drawable = runCatching {
            application.packageManager.getApplicationIcon(spec.packageName)
        }.getOrElse {
            requireNotNull(application.applicationInfo.loadIcon(application.packageManager))
        }
        val bitmap = drawable.squareBitmap(ICON_SIZE)
        val canvas = Canvas(bitmap)
        val radius = ICON_SIZE * BADGE_RADIUS_RATIO
        // Pixel Launcher adds the shortcut owner's app badge at bottom-right. Keep the Space
        // initial at bottom-left so both the product owner and exact Space remain identifiable.
        val centerX = radius
        val centerY = ICON_SIZE - radius
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_COLOR }
        canvas.drawCircle(centerX, centerY, radius, background)
        val foreground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = radius * 1.25f
            isFakeBoldText = true
        }
        val initial = spec.groupName.trim().firstOrNull()?.toString().orEmpty()
        val baseline = centerY - (foreground.ascent() + foreground.descent()) / 2f
        canvas.drawText(initial, centerX, baseline, foreground)
        return if (spec.available) bitmap else bitmap.grayscale()
    }

    private fun Drawable.squareBitmap(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val previousBounds = Rect(bounds)
            setBounds(0, 0, size, size)
            draw(Canvas(bitmap))
            bounds = previousBounds
        }

    private fun Bitmap.grayscale(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { gray ->
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
                alpha = UNAVAILABLE_ICON_ALPHA
            }
            Canvas(gray).drawBitmap(this, 0f, 0f, paint)
        }

    private companion object {
        const val MAX_SHORT_LABEL_LENGTH = 24
        const val ICON_SIZE = 192
        const val BADGE_RADIUS_RATIO = 0.22f
        const val BADGE_COLOR = 0xFF6750A4.toInt()
        const val UNAVAILABLE_ICON_ALPHA = 150
        const val EXTRA_RENDER_FINGERPRINT = "org.apptwin.shortcut.RENDER_FINGERPRINT"
        const val SHORTCUT_RENDER_VERSION = 1
    }
}
