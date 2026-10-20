package com.bangumi.ywylite.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bangumi.ywylite.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 更新包下载与安装。
 *
 * 下载写到 cacheDir/updates 下的 `.part` 临时文件，校验通过才改名成 `.apk`：
 * 任何中断（网络失败、校验不过、用户取消、进程被杀）都只留下 `.part`，
 * 下次启动由 [cleanupStale] 清掉，不会把半截包当安装包用。
 *
 * 直链优先，连不上再依次试镜像前缀——GitHub 下载在国内网络经常超时，
 * 但只应在「连不上」时才走镜像，正常网络仍从官方源拿。
 */
class UpdateInstaller(private val context: Context) {

    private val dir: File get() = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    /**
     * 启动时清理：没下完的 `.part` 一律删；超过 [APK_KEEP_MILLIS] 的安装包也删。
     * 安装包不立刻删是因为系统安装器是异步读文件的，删早了会装不上。
     */
    fun cleanupStale() {
        val cutoff = System.currentTimeMillis() - APK_KEEP_MILLIS
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(PART_SUFFIX) || file.lastModified() < cutoff) file.delete()
        }
    }

    /**
     * 下载安装包。直链失败时逐个尝试镜像，全部失败才抛异常。
     * 每次失败或取消都会删掉残包；调用方取消协程即等于放弃本次下载。
     *
     * @param onProgress 百分比 0–100；-1 表示本轮失败或长度未知
     */
    suspend fun download(update: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val direct = update.downloadUrl
        if (!direct.startsWith("https://")) throw IOException("发布页未提供安装包")

        val target = File(dir, "bgm-lite-${sanitize(update.versionName)}.apk")
        val part = File(dir, target.name + PART_SUFFIX)
        // 开始新下载前清掉旧包：避免卸载/重装失败后留下多份不同版本的 APK
        dir.listFiles()?.forEach { if (it.name != part.name) it.delete() }

        var lastError: Exception? = null
        for (url in listOf(direct) + MIRROR_PREFIXES.map { it + direct }) {
            part.delete()
            try {
                fetch(url, part, onProgress)
                validate(part)
                if (target.exists() && !target.delete()) throw IOException("无法覆盖旧安装包")
                if (!part.renameTo(target)) throw IOException("无法保存安装包")
                return@withContext target
            } catch (e: CancellationException) {
                // 用户取消/页面关闭：残包必须删掉
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                onProgress(-1)
                lastError = e
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    /**
     * 唤起系统安装器。
     * @return false 表示没有「安装未知应用」权限，已跳到系统设置页；
     *         用户授权后需要再点一次更新（权限变更是异步的，无法自动续上）。
     */
    fun install(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.$AUTHORITY_SUFFIX", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return true
    }

    /** 放弃当前下载（保留已下完的包）：删掉所有 `.part` 临时文件 */
    fun discardPartial() {
        dir.listFiles()?.forEach { if (it.name.endsWith(PART_SUFFIX)) it.delete() }
    }

    private fun fetch(url: String, part: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()
        // 复用应用 OkHttp（与应用内代理设置一致），大文件走流式写盘，不进内存
        App.INSTANCE.api.appOkHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPercent = Int.MIN_VALUE
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (total > 0 && part.length() != total) throw IOException("下载不完整")
        }
    }

    /** 校验下载结果：非空、确实是 zip(APK)、包名与本应用一致 */
    private fun validate(file: File) {
        if (!file.exists() || file.length() < MIN_APK_BYTES) throw IOException("安装包为空")
        val head = ByteArray(2)
        file.inputStream().use { it.read(head) }
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            throw IOException("下载内容不是安装包")
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("安装包无法解析")
        if (info.packageName != context.packageName) throw IOException("安装包与本应用不匹配")
    }

    private fun sanitize(versionName: String): String =
        versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        private const val DIR_NAME = "updates"
        private const val PART_SUFFIX = ".part"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val AUTHORITY_SUFFIX = "updates"
        private const val MIN_APK_BYTES = 1024L
        private const val APK_KEEP_MILLIS = 24L * 60 * 60 * 1000

        /** GitHub 资源镜像：仅在直链连不上时依次兜底 */
        private val MIRROR_PREFIXES = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/"
        )
    }
}