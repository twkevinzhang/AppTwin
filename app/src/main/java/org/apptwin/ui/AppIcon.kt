package org.apptwin.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppIcon(
    packageName: String,
    size: Dp,
    grayscale: Boolean = false,
) {
    val appContext = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                appContext.packageManager.getApplicationIcon(packageName).toBitmap(144)
            }.getOrNull()
        }
    }
    val grayscaleFilter = remember(grayscale) {
        if (grayscale) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        } else {
            null
        }
    }
    val fallbackTint = MaterialTheme.colorScheme.primary.let { color ->
        if (grayscale) color.withSaturationZero() else color
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap == null) {
            Icon(
                Icons.Default.Android,
                contentDescription = null,
                modifier = Modifier.size(size * 0.58f),
                tint = fallbackTint,
            )
        } else {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Fit,
                colorFilter = grayscaleFilter,
            )
        }
    }
}

private fun Color.withSaturationZero(): Color {
    val luminance = red * 0.213f + green * 0.715f + blue * 0.072f
    return copy(red = luminance, green = luminance, blue = luminance)
}

private fun Drawable.toBitmap(edge: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val output = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    setBounds(0, 0, edge, edge)
    draw(canvas)
    return output
}
