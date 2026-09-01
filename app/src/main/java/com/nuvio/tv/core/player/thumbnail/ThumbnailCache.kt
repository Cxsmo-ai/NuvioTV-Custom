/*
 * NuvioTV-Fork - seek-thumbnail workstream (T-series)
 * Copyright (C) 2026 NuvioTV-Fork contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.nuvio.tv.core.player.thumbnail

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StatFs
import android.util.Log
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Disk + memory cache for seek thumbnails (rev5 S5 rules, v1):
 * - Key: sha1(titleKey|sNN|hNNN|durationMs|vN) - video identity, never the URL (debrid URLs
 *   rotate). Engine folds in spacing (sNN) + frame height (hNNN); vN is the store-version.
 *   v1 deviation, documented: rev5 asks for contentId+fileSize; title+durationMs is the
 *   identity available at the player layer today and survives URL rotation. Fold in
 *   contentId+size when the provider layer exposes them.
 * - Persistent LRU across sessions: 200 MB standard / 50 MB low tier, evict oldest by
 *   lastModified across all title dirs; free-space check before every write.
 * - In-RAM bitmap LRU: 64 thumbs standard / 16 low tier (holds the coarse lattice +
 *   scrub working set at 10 s spacing).
 * - Entries: one JPEG per 10 s bucket, 480x270 (worker downscales on the CPU via MMR
 *   getScaledFrameAtTime; the GL-effect path was dropped on Amlogic).
 */
class ThumbnailCache(context: Context, titleKey: String, durationMs: Long) {
    companion object {
        private const val TAG = "ThumbCache"
        private const val ROOT_DIR = "seek_thumbs"
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024

        // On-disk store-format version. Bump on ANY format change (frame size, filename
        // scheme, JPEG->sprite layout); folded into the cache key so a bump re-extracts once.
        private const val STORE_VERSION = 2

        /** Deletes the entire seek-thumbnail cache root (all titles). Runs on IO. */
        suspend fun clearAll(context: Context): Boolean = withContext(Dispatchers.IO) {
            val root = File(context.applicationContext.cacheDir, ROOT_DIR)
            val ok = runCatching {
                if (root.exists()) root.deleteRecursively() else true
            }.getOrDefault(false)
            Log.i(TAG, "clearAll: deleted=$ok root=${root.absolutePath}")
            ok
        }

        fun isLowTier(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return true
            if (am.isLowRamDevice) return true
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return info.totalMem < 2_500L * 1024 * 1024
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    private val appContext = context.applicationContext
    private val lowTier = isLowTier(appContext)
    private val diskBudgetBytes = if (lowTier) 50L * 1024 * 1024 else 200L * 1024 * 1024
    private val rootDir = File(appContext.cacheDir, ROOT_DIR)
    private val titleDir = File(rootDir, sha1("$titleKey|$durationMs|v$STORE_VERSION").take(24))
    // Mem cap sized for 10 s density: hold the coarse (stride-30) lattice + a scrub
    // working set so nearest-resident serving doesn't collapse to one frame across an
    // unfilled span. ~0.5 MB per 480x270 frame => 64 ~= 33 MB (AM9 Pro 4 GB); 16 ~= 8 MB.
    private val memCache = LruCache<Long, Bitmap>(if (lowTier) 16 else 64)

    fun getMem(bucket: Long): Bitmap? = memCache.get(bucket)

    fun putMem(bucket: Long, bitmap: Bitmap) {
        memCache.put(bucket, bitmap)
    }

    fun hasDisk(bucket: Long): Boolean = File(titleDir, "$bucket.jpg").exists()

    /** IO-thread only. */
    fun readDisk(bucket: Long): Bitmap? {
        val f = File(titleDir, "$bucket.jpg")
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** IO-thread only. Returns true when the entry was written. */
    fun writeDisk(bucket: Long, bitmap: Bitmap): Boolean {
        return runCatching {
            if (!titleDir.exists() && !titleDir.mkdirs()) return false
            val stat = StatFs(appContext.cacheDir.absolutePath)
            if (stat.availableBytes < MIN_FREE_BYTES) {
                Log.w(TAG, "skip write: low free space")
                return false
            }
            val f = File(titleDir, "$bucket.jpg")
            f.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) }
            enforceBudget()
            true
        }.getOrDefault(false)
    }

    /** IO-thread only. Evict oldest files across all title dirs until under budget. */
    private fun enforceBudget() {
        val files = rootDir.walkTopDown().filter { it.isFile }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= diskBudgetBytes) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= diskBudgetBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
