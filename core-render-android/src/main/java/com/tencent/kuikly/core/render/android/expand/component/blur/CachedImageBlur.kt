package com.tencent.kuikly.core.render.android.expand.component.blur

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache

/** Source-image blur, never a capture of the view tree. Called only on the image worker. */
internal object CachedImageBlur {
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Synchronized
    fun load(drawable: Drawable, context: Context, radius: Float): Drawable? {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return drawable
        if (bitmap.isRecycled) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val width = when {
            manager.isLowRamDevice || manager.memoryClass <= 128 -> 96
            Build.VERSION.SDK_INT < 31 || manager.memoryClass < 256 -> 150
            else -> 240
        }
        val key = "${bitmap.generationId}:${bitmap.width}:${bitmap.height}:$radius:$width"
        cache.get(key)?.let { return BitmapDrawable(context.resources, it) }
        val result = RenderScriptBlur.blurImage(drawable, context, radius, width) as? BitmapDrawable ?: return drawable
        cache.put(key, result.bitmap)
        // Eviction does not recycle: an attached view may still draw the shared bitmap.
        return BitmapDrawable(context.resources, result.bitmap)
    }
}
