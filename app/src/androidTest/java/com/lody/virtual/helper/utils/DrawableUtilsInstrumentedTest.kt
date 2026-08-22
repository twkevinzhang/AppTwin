package com.lody.virtual.helper.utils

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawableUtilsInstrumentedTest {
    @Test
    fun adaptiveIconWithInvalidIntrinsicSizeRendersWithoutChangingBounds() {
        val originalBounds = Rect(11, 13, 17, 19)
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.BLUE),
            ColorDrawable(Color.MAGENTA),
        ).apply { bounds = originalBounds }

        val bitmap = DrawableUtils.drawableToBitMap(drawable)

        assertNotNull(bitmap)
        requireNotNull(bitmap).also {
            assertEquals(192, it.width)
            assertEquals(192, it.height)
            assertEquals(Color.MAGENTA, it.getPixel(it.width / 2, it.height / 2))
        }
        assertEquals(originalBounds, drawable.bounds)
    }
}
