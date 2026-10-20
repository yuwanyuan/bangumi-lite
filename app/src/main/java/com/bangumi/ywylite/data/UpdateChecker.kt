package com.bangumi.ywylite.data

import com.bangumi.ywylite.BuildConfig
import com.bangumi.ywylite.data.api.BangumiApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.IOException

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
     * @return 有新版时返回 [UpdateInfo]；已是最新返回 null；网络/解析失败抛异常。
     *         自动检查的调用方应静默吞掉异常，手动检查才提示「暂时没查到新版本」。
     */
    suspend fun checkLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val release = fetchRelease("https://api.github.com/repos/$REPO/releases/latest")
        val remote = release.toUpdateInfo()
        if (isNewer(remote)) remote else null
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
        api.appOkHttp.newCall(request).execute().use { response ->
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
        private const val SNOOZE_MILLIS = 24L * 60 * 60 * 1000
    }
}