package com.tunisianprayertimes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tunisianprayertimes.quran.assets.QuranAssetSource
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.open

// The visible scan and its two neighbours together use at most 30 MB in the bundled manifest (RGB_565).
// Keep room for all three, so a neighbour finishing last cannot evict the page the reader returns to.
// The font pages already have their own bounded cache, including their rendered bitmap.
private val scans = object : LruCache<String, Pair<QuranAssetSource, QuranPageArt.Scan>>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Pair<QuranAssetSource, QuranPageArt.Scan>): Int =
        value.second.image.asAndroidBitmap().allocationByteCount
}

/** Memory only: the last page can be drawn in the first frame of a return to the Quran tab. */
internal fun cachedQuranPageArt(assetPath: String): QuranPageArt? =
    cachedQuranFontPage(assetPath) ?: scans.get(assetPath)?.second

/** IO thread. Recheck the asset's source so removing or replacing a pack invalidates its cached scan. */
internal fun loadQuranPageArt(context: Context, assetPath: String): QuranPageArt? {
    loadQuranFontPage(context, assetPath)?.let { return it }
    val source = QuranAssets.resolve(context, assetPath)
    if (source == null) {
        scans.remove(assetPath)
        return null
    }
    scans.get(assetPath)?.takeIf { it.first == source }?.let { return it.second }
    val art = source.open(context).use { stream ->
        QuranPageArt.Scan(checkNotNull(BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        })).asImageBitmap())
    }
    scans.put(assetPath, source to art)
    return art
}
