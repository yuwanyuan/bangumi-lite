package com.bangumi.ywylite.data

import com.bangumi.ywylite.BuildConfig
import com.bangumi.ywylite.data.api.BangumiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 一次「发现新版本」的信息。
 *
 * 标识与比较一律用 [versionName]（正式版版本号，如 `1.2.6`）：
 * versionCode 是 CI run 号，同一版本重构建会变，不能当「这个版本」的身份。
 */
data class UpdateInfo(
    val versionName: String,
    val releaseUrl: String,
    /** 安装包直链（GitHub 资产），下载失败才走镜像 */
    val downloadUrl: String
) {
    /** 跳过/稍后提醒用的版本标识：就是版本号 */
    val key: String get() = versionName
}

/**
 * 检查更新。版本源是 GitHub 的**正式 Release**（latest，不含预发布/开发版）：
 * 装了开发版（versionName 带 -dev.N）也只报 base 更高的正式版，
 * 同 base 的开发版不会被「降级」提示到对应正式版。
 */
class UpdateChecker(
    private val settings: Settings,
    private val api: BangumiApi
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 查询最新正式版。
     *
     * 并发尝试多条通道，谁先成功用谁（串行会在连不上的通道上白等）：
     * 1. GitHub REST API（`api.github.com`）——信息最全，但国内网络常连不上
     * 2. API 镜像（`gh-proxy.com`）——同一条 REST 请求套镜像前缀
     * 3. 网页 302 兜底——请求 `github.com/.../releases/latest` 只读跳转地址，
     *    从中取 tag 名拼出下载直链。这条路不依赖 API 域名，只要 github.com 通就行
     */
    suspend fun checkLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val remote = fetchLatestFromAnyChannel()
        if (isNewer(remote)) remote else null
    }

    private suspend fun fetchLatestFromAnyChannel(): UpdateInfo = coroutineScope {
        // 并发跑所有通道，先成功者胜出：串行会在连不上的通道上白等，
        // 而国内网络里 api.github.com / github.com / 各镜像的快慢完全不固定
        val candidates: List<Pair<String, () -> UpdateInfo>> = buildList {
            add("api" to { fetchRelease(API_RELEASE_URL).toUpdateInfo() })
            API_MIRROR_PREFIXES.forEach { prefix ->
                add("api@$prefix" to { fetchRelease(prefix + API_RELEASE_URL).toUpdateInfo() })
            }
            (listOf("") + REDIRECT_MIRROR_PREFIXES).forEach { prefix ->
                add("redirect@$prefix" to { fetchLatestByRedirect(prefix) })
            }
        }

        val results = Channel<Pair<String, Result<UpdateInfo>>>(Channel.UNLIMITED)
        val jobs = candidates.map { (name, load) ->
            launch(Dispatchers.IO) {
                val result = try {
                    Result.success(load())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                // 已被别处取消时 trySend 会失败，属正常收尾
                results.trySend(name to result)
            }
        }

        var lastError: Exception? = null
        try {
            repeat(candidates.size) {
                val (name, result) = results.receive()
                result.onSuccess {
                    // 拿到结果就取消其余通道，别让它们继续占网络
                    jobs.forEach { job -> job.cancel() }
                    return@coroutineScope it
                }
                val error = result.exceptionOrNull() as? Exception
                // 通道失败是常态（国内常连不上 api.github.com），只记 warn 便于排障，不打扰用户
                android.util.Log.w("UpdateChecker", "channel '$name' failed: ${error?.message}")
                lastError = error ?: lastError
            }
        } finally {
            jobs.forEach { it.cancel() }
        }
        throw lastError ?: IOException("检查更新失败")
    }

    /**
     * 不依赖 API 的兜底：`/releases/latest` 会 302 到 `/releases/tag/vX.Y.Z`，
     * 从中取出 tag 名。安装包直链按 GitHub 固定规则拼接（资产名 app-release.apk 与 CI 一致）。
     */
    private fun fetchLatestByRedirect(prefix: String): UpdateInfo {
        val request = Request.Builder()
            .url(prefix + "https://github.com/$REPO/releases/latest")
            .header("Cache-Control", "no-cache")
            .build()
        api.appOkHttp.newBuilder().callTimeout(CHANNEL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build().newCall(request).execute().use { response ->
                // 跟随重定向后最终地址就是 tag 页；未跟随的 302 则读 Location
                val finalUrl = response.request.url.toString()
                val location = response.header("Location")
                val tagUrl = sequenceOf(finalUrl, location)
                    .filterNotNull()
                    .firstOrNull { it.contains("/releases/tag/") }
                    ?: throw IOException("HTTP ${response.code}，未跳转到版本页")
                val tag = tagUrl.substringAfter("/releases/tag/").substringBefore('?').substringBefore('/')
                if (tag.isBlank()) throw IOException("无法从跳转地址解析版本号")
                return UpdateInfo(
                    versionName = tag.removePrefix("v"),
                    releaseUrl = "https://github.com/$REPO/releases/tag/$tag",
                    downloadUrl = "https://github.com/$REPO/releases/download/$tag/$RELEASE_APK_NAME"
                )
            }
    }

    /**
     * 自动检查的额外门槛：自动开关打开、该版本没被跳过、且不在同一版本的 24 小时静默期内。
     * 手动检查不调用它（用户主动点击时无视这些状态，直接报结果）。
     */
    suspend fun shouldAutoPrompt(update: UpdateInfo): Boolean {
        if (!settings.autoCheckUpdate.first()) return false
        if (normalizeLegacyKey(settings.skippedUpdateKey.first()) == update.key) return false
        val snoozeKey = normalizeLegacyKey(settings.updateSnoozeKey.first())
        if (snoozeKey == update.key && System.currentTimeMillis() < settings.updateSnoozeUntil.first()) {
            return false
        }
        return true
    }

    /**
     * 早期版本按 versionCode 记「跳过/稍后提醒」，存的是纯数字。
     * 现在按版本号记，旧值无法对应任何版本号——当作没有记录，让它再提醒一次，
     * 而不是让一条永远匹配不上的记录留在本地。
     */
    private fun normalizeLegacyKey(key: String?): String? =
        key?.takeIf { !it.all { ch -> ch.isDigit() } }

    /** 稍后提醒：同一版本 24 小时内不再弹（换新版本时照常提示） */
    suspend fun snooze(update: UpdateInfo) {
        settings.saveUpdateSnooze(update.key, System.currentTimeMillis() + SNOOZE_MILLIS)
    }

    /** 跳过本次更新：记住该版本号，自动检查永不再提 */
    suspend fun skip(update: UpdateInfo) {
        settings.saveSkippedUpdateKey(update.key)
    }

    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        settings.saveAutoCheckUpdate(enabled)
    }

    // ---------- 网络 ----------

    private fun fetchRelease(url: String): GithubRelease {
        // no-cache：Release 元数据会随发版变化，共享 OkHttp 缓存里可能还是旧响应
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("Cache-Control", "no-cache")
            .build()
        // 单通道限时：多条通道串行兜底，不能让一条卡住把整体拖到几十秒
        api.appOkHttp.newBuilder().callTimeout(CHANNEL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub API HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("GitHub API 返回为空")
                return json.decodeFromString<GithubRelease>(body)
            }
    }

    private fun GithubRelease.toUpdateInfo(): UpdateInfo {
        // 已标记为预发布的一律不当正式版（latest 端点本不该返回它，这里只作兜底）
        if (prerelease) throw IOException("release 是预发布")
        // 版本号取 tag（v1.2.6 → 1.2.6）：GitHub Release 里只有它代表「这个正式版」
        val versionName = tagName.removePrefix("v").takeIf { it.isNotBlank() }
            ?: throw IOException("release 没有版本号")
        // 只认 CI 发布的正式安装包；没有它就没法「点击即下载」
        val apkUrl = assets.firstOrNull { it.name == RELEASE_APK_NAME }?.downloadUrl
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("release 没有安装包")

        return UpdateInfo(
            versionName = versionName,
            releaseUrl = htmlUrl,
            downloadUrl = apkUrl
        )
    }

    // ---------- 版本比较 ----------

    private fun isNewer(remote: UpdateInfo): Boolean {
        // 只比 base：开发版 1.2.5-dev.4 已包含 1.2.5 的内容，不该被提示「升级」到同 base 的正式版；
        // 正式版 1.2.6 出来时 base 更高，照常提示。
        val currentBase = BuildConfig.VERSION_NAME.substringBefore("-dev.").removePrefix("v")
        return compareVersionNames(remote.versionName.substringBefore("-dev."), currentBase) > 0
    }

    private fun compareVersionNames(a: String, b: String): Int {
        fun parts(v: String): Pair<List<Int>, List<Int>?> {
            val cleaned = v.trim().removePrefix("v")
            val core = cleaned.substringBefore('-')
            val nums = core.split('.').map { it.toIntOrNull() ?: 0 }
            val pre = cleaned.substringAfter('-', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() }
                ?.split('.')
                ?.map { it.toIntOrNull() ?: 0 }
            return nums to pre
        }

        val (aCore, aPre) = parts(a)
        val (bCore, bPre) = parts(b)
        for (i in 0 until maxOf(aCore.size, bCore.size)) {
            val diff = (aCore.getOrNull(i) ?: 0).compareTo(bCore.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        // 主版本相同：正式版 > 预发布（1.2.5 > 1.2.5-dev.3）
        if (aPre == null && bPre != null) return 1
        if (aPre != null && bPre == null) return -1
        if (aPre == null && bPre == null) return 0
        for (i in 0 until maxOf(aPre!!.size, bPre!!.size)) {
            val diff = (aPre.getOrNull(i) ?: 0).compareTo(bPre.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val prerelease: Boolean = false,
        val assets: List<GithubAsset> = emptyList()
    )

    @Serializable
    private data class GithubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = ""
    )

    companion object {
        private const val REPO = "yuwanyuan/bangumi-lite"
        private const val RELEASE_APK_NAME = "app-release.apk"
        private const val API_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val SNOOZE_MILLIS = 24L * 60 * 60 * 1000

        /** REST API 镜像：仅在 api.github.com 连不上时依次兜底（实测 gh-proxy 可代理 API） */
        private val API_MIRROR_PREFIXES = listOf("https://gh-proxy.com/")

        /** 网页 302 兜底用的镜像前缀 */
        private val REDIRECT_MIRROR_PREFIXES = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/"
        )

        /** 检查更新的整体超时：三条通道串行，不能让首页等到天荒地老 */
        const val CHECK_TIMEOUT_MILLIS = 20_000L

        /** 单条通道的限时（连接+读取），超时就放弃这条 */
        private const val CHANNEL_TIMEOUT_MILLIS = 12_000L
    }
}