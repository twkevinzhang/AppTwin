package org.apptwin

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon

internal sealed interface ShortcutCreationResult {
    data object Requested : ShortcutCreationResult
    data object Unsupported : ShortcutCreationResult
    data class Failed(val reason: String) : ShortcutCreationResult
}

/** Publishes an explicit, immutable route to one package in one AppTwin space. */
internal class GroupAppShortcutPublisher(
    private val application: Application,
) {
    fun requestPin(item: GroupAppItem): ShortcutCreationResult {
        val manager = application.getSystemService(ShortcutManager::class.java)
            ?: return ShortcutCreationResult.Unsupported
        if (!manager.isRequestPinShortcutSupported) return ShortcutCreationResult.Unsupported

        return runCatching {
            val shortcut = ShortcutInfo.Builder(application, item.launchKey)
                .setShortLabel(shortLabel(item))
                .setLongLabel("${item.groupName} · ${item.appLabel}")
                .setIcon(Icon.createWithBitmap(badgedIcon(item)))
                .setIntent(
                    Intent(application, MainActivity::class.java)
                        .setAction(GroupAppLaunchContract.ACTION_LAUNCH_GROUP_APP)
                        .putExtra(GroupAppLaunchContract.EXTRA_GROUP_ID, item.groupId)
                        .putExtra(
                            GroupAppLaunchContract.EXTRA_PACKAGE_NAME,
                            item.app.packageName,
                        ),
                )
                .build()
            if (manager.requestPinShortcut(shortcut, null)) {
                ShortcutCreationResult.Requested
            } else {
                ShortcutCreationResult.Failed("啟動器未接受捷徑要求")
            }
        }.getOrElse { error ->
            ShortcutCreationResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun shortLabel(item: GroupAppItem): String =
        "${item.groupName} ${item.appLabel}".take(MAX_SHORT_LABEL_LENGTH)

    private fun badgedIcon(item: GroupAppItem): Bitmap {
        val drawable = runCatching {
            application.packageManager.getApplicationIcon(item.app.packageName)
        }.getOrElse {
            requireNotNull(application.applicationInfo.loadIcon(application.packageManager))
        }
        val bitmap = drawable.squareBitmap(ICON_SIZE)
        val canvas = Canvas(bitmap)
        val radius = ICON_SIZE * BADGE_RADIUS_RATIO
        val center = ICON_SIZE - radius
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_COLOR }
        canvas.drawCircle(center, center, radius, background)
        val foreground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = radius * 1.25f
            isFakeBoldText = true
        }
        val initial = item.groupName.trim().firstOrNull()?.toString().orEmpty()
        val baseline = center - (foreground.ascent() + foreground.descent()) / 2f
        canvas.drawText(initial, center, baseline, foreground)
        return bitmap
    }

    private fun Drawable.squareBitmap(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val previousBounds = Rect(bounds)
            setBounds(0, 0, size, size)
            draw(Canvas(bitmap))
            bounds = previousBounds
        }

    private companion object {
        const val MAX_SHORT_LABEL_LENGTH = 24
        const val ICON_SIZE = 192
        const val BADGE_RADIUS_RATIO = 0.22f
        const val BADGE_COLOR = 0xFF6750A4.toInt()
    }
}
