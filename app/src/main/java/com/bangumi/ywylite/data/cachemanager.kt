package com.bangumi.ywylite.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CacheInfo(
    val totalSize: Long = 0,
    val imageCacheSize: Long = 0,
    val networkCacheSize: Long = 0
)

data class CacheCleanResult(
    val freedSize: Long = 0
)

class CacheManager(private val context: Context) {

    companion object {
        private const val IMAGE_CACHE_DIR = "image_cache"
        private const val NETWORK_CACHE_DIR = "network_cache"
    }

    fun getCacheInfo(): CacheInfo {
        val imageCacheSize = calculateDirSize(getImageCacheDir())
        val networkCacheSize = calculateDirSize(getNetworkCacheDir())
        return CacheInfo(
            totalSize = imageCacheSize + networkCacheSize,
            imageCacheSize = imageCacheSize,
            networkCacheSize = networkCacheSize
        )
    }

    suspend fun cleanAllCache(
        onProgress: (Float) -> Unit = {},
    ): CacheCleanResult {
        val beforeSize = getCacheInfo().totalSize
        withContext(Dispatchers.IO) {
            clearCoilDiskCache()
            deleteDirContents(getImageCacheDir())
            deleteDirContents(getNetworkCacheDir())
            onProgress(1.0f)
        }
        return CacheCleanResult(freedSize = beforeSize)
    }

    suspend fun cleanImageCache(
        onProgress: (Float) -> Unit = {},
    ): CacheCleanResult {
        val beforeSize = getCacheInfo().imageCacheSize
        withContext(Dispatchers.IO) {
            clearCoilDiskCache()
            deleteDirContents(getImageCacheDir())
            onProgress(1.0f)
        }
        return CacheCleanResult(freedSize = beforeSize)
    }

    suspend fun cleanNetworkCache(
        onProgress: (Float) -> Unit = {},
    ): CacheCleanResult {
        val beforeSize = getCacheInfo().networkCacheSize
        withContext(Dispatchers.IO) {
            deleteDirContents(getNetworkCacheDir())
            onProgress(1.0f)
        }
        return CacheCleanResult(freedSize = beforeSize)
    }

    private fun getImageCacheDir(): File {
        return File(context.cacheDir, IMAGE_CACHE_DIR)
    }

    private fun getNetworkCacheDir(): File {
        return File(context.cacheDir, NETWORK_CACHE_DIR)
    }

    private fun clearCoilDiskCache() {
        val coilCacheDir = File(context.cacheDir, "coil")
        if (coilCacheDir.exists()) {
            deleteDirContents(coilCacheDir)
        }
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }

    private fun deleteDirContents(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }
}
