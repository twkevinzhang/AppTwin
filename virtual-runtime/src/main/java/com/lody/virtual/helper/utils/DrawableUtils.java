package com.lody.virtual.helper.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

public class DrawableUtils {
    private static final int DEFAULT_ICON_EDGE_PX = 192;
    private static final int MAX_ICON_EDGE_PX = 512;

    public static Bitmap drawableToBitMap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = ((BitmapDrawable) drawable);
            Bitmap bitmap = bitmapDrawable.getBitmap();
            if (isUsable(bitmap)) {
                return scaleDownIfNeeded(bitmap);
            }
        }

        int[] dimensions = renderDimensions(drawable);
        Rect previousBounds = new Rect(drawable.getBounds());
        try {
            Bitmap bitmap = Bitmap.createBitmap(
                    dimensions[0],
                    dimensions[1],
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        } finally {
            drawable.setBounds(previousBounds);
        }
    }

    private static boolean isUsable(Bitmap bitmap) {
        return bitmap != null
                && !bitmap.isRecycled()
                && bitmap.getWidth() > 0
                && bitmap.getHeight() > 0;
    }

    private static Bitmap scaleDownIfNeeded(Bitmap bitmap) {
        int largestEdge = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (largestEdge <= MAX_ICON_EDGE_PX) {
            return bitmap;
        }
        float scale = (float) MAX_ICON_EDGE_PX / largestEdge;
        int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    private static int[] renderDimensions(Drawable drawable) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            return new int[] { DEFAULT_ICON_EDGE_PX, DEFAULT_ICON_EDGE_PX };
        }
        int largestEdge = Math.max(width, height);
        if (largestEdge <= MAX_ICON_EDGE_PX) {
            return new int[] { width, height };
        }
        float scale = (float) MAX_ICON_EDGE_PX / largestEdge;
        return new int[] {
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale))
        };
    }
}
