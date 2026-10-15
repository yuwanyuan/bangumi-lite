package com.bangumi.ywylite.data

import android.content.Context
import coil.imageLoader
import com.bangumi.ywylite.App
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

    /** 遍历目录统计大小属磁盘 IO：原先在主线程执行，目录大时会卡 UI */
    suspend fun getCacheInfo(): CacheInfo = withContext(Dispatchers.IO) {
        val imageCacheSize = calculateDirSize(getImageCacheDir())
        val networkCacheSize = calculateDirSize(getNetworkCacheDir())
        CacheInfo(
            totalSize = imageCacheSize + networkCacheSize,
            imageCacheSize = imageCacheSize,
            networkCacheSize = networkCacheSize
        )
    }

    suspend fun cleanAllCache(
        onProgress: (Float) -> Unit = {},
    ): CacheCleanResult {
        val before = getCacheInfo().totalSize
        withContext(Dispatchers.IO) {
            clearImageCaches()
            clearNetworkCache()
            onProgress(1.0f)
        }
        return CacheCleanResult(freedSize = freedSince(before))
    }

    suspend fun cleanImageCache(
        onProgress: (Float) -> Unit = {},
    ): CacheCleanResult {
        val before = getCacheInfo().imageCacheSize
        withContext(Dispatchers.IO) {
            clearImageCaches()
            onProgress(1.0f)
        }
        return CacheCleanResult(freedSize = freedSince(before))
    }

    suspend fun cleanNetworkCache(
        onProgress: (Float) -> Unit = {},
    ): CacheCleanResult {
        val before = getCacheInfo().networkCacheSize
        withContext(Dispatchers.IO) {
            clearNetworkCache()
            onProgress(1.0f)
        }
        return CacheCleanResult(freedSize = freedSince(before))
    }

    /**
     * 实际释放量 = 清理前大小 - 清理后大小。
     * 原先直接用「清理前的大小」当结果，删除被占用或部分失败时也会宣称全部清掉了。
     */
    private suspend fun freedSince(beforeSize: Long): Long =
        (beforeSize - getCacheInfo().totalSize).coerceAtLeast(0)

    /**
     * 图片缓存走 Coil 自己的 API：磁盘缓存有日志文件，从文件系统底下抽文件会让
     * 运行中的实例失效。内存缓存一并清，否则刚清完的图还在内存里，看着像没清掉。
     */
    private fun clearImageCaches() {
        val loader = context.imageLoader
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        // 旧版本用的是 Coil 默认目录（cacheDir/coil），残留一并清掉
        clearCoilDiskCache()
    }

    /**
     * 网络缓存走 OkHttp 的 evictAll：同一份 Cache 实例正被 API 客户端使用，
     * 直接删目录会让它写坏日志。
     */
    private fun clearNetworkCache() {
        App.INSTANCE.api.clearNetworkCache()
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

    /** 删除目录内的全部子项但保留根目录本身（OkHttp 缓存目录被整个删除后会静默失效） */
    private fun deleteDirContents(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) child.deleteRecursively() else child.delete()
        }
    }
}
