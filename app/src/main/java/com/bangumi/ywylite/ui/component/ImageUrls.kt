package com.bangumi.ywylite.ui.component

/**
 * 图片地址归一化与尺寸选择。
 *
 * Bangumi 图床 lain.bgm.tv 支持按宽度取图：`/r/{px}/pic/cover/l/...`。
 * 但各接口给的字段质量完全不统一：v0 的 medium=r/800、common=r/400，next 的 medium=r/200，
 * 网页解析出的还是 `http://` 或 `//` 协议相对地址、`/pic/cover/m/` 老格式。
 *
 * 如果直接把字段值丢给图片库，同一张图在不同界面会算出**不同 URL**，
 * 缓存（内存/磁盘都按 URL 作 key）全部落空——去过的页面再进还要重新下载一遍，
 * 列表里还会去下 800px 大图（36KB）而不是格子需要的小图（4KB）。
 *
 * 统一走这里：同一个尺寸档在全 App 得到同一个地址，缓存才能命中；
 * 尺寸按显示需要（列表 200 / 网格 400 / 全屏 800），不要把大图塞进小格子。
 *
 * 参考 czy0729/Bangumi 的 cover.ts 与 utils/image（它同样把 g|s|m|c|l 归一化到 /r/{size}/pic/cover/l/）。
 */
object ImageUrls {

    /** 列表小缩略图（40~64dp） */
    const val THUMB = 200

    /** 网格封面卡片（110dp 宽的格子，平板 2x 屏也够）与详情页头图 */
    const val GRID = 400

    /** 全屏查看器 */
    const val DETAIL = 800

    /**
     * 条目封面：统一归一到 `/r/{px}/pic/cover/l/`。
     * 非 lain 图床或非 cover 路径（角色 /pic/crt/、头像 /pic/user/）原样返回。
     */
    fun cover(raw: String?, px: Int = GRID): String {
        val src = normalize(raw) ?: return ""
        if (!src.contains("lain.bgm.tv") || !src.contains("/pic/cover/")) return src
        // 剥掉已有质量段与尺寸前缀：/pic/cover/m/xxx、/r/800/pic/cover/l/xxx 全部归一
        val rest = src.substringAfter("/pic/cover/").substringAfter('/')
        if (rest.isEmpty() || !rest.contains('.')) return src
        return "https://lain.bgm.tv/r/$px/pic/cover/l/$rest"
    }

    /**
     * 用户头像：图床对 user/icon 没有 /r/{n}/ 前缀（实测 400），统一取中图 `/pic/user/m/`，
     * 保证评论（24dp）、收藏列表、个人页（56dp）拿到的都是同一个地址。
     */
    fun avatar(raw: String?): String {
        val src = normalize(raw) ?: return ""
        if (!src.contains("lain.bgm.tv")) return src
        if (src.contains("/pic/user/")) {
            return src.replace(Regex("""/pic/user/(g|s|m|c|l)/"""), "/pic/user/m/")
        }
        if (src.contains("/pic/icon/")) {
            return src.replace(Regex("""/pic/icon/(g|s|m|c|l)/"""), "/pic/icon/m/")
        }
        return src
    }

    /**
     * 角色立绘：保持原图比例（方形格子顶部裁切露出头部），只按宽度取小图。
     * 原图常是 1.4MB 级别，取 r/200 约 17KB。
     */
    fun character(raw: String?, px: Int = THUMB): String {
        val src = normalize(raw) ?: return ""
        if (!src.contains("lain.bgm.tv") || !src.contains("/pic/crt/")) return src
        val rest = src.substringAfter("/pic/crt/").substringAfter('/')
        if (rest.isEmpty() || !rest.contains('.')) return src
        return "https://lain.bgm.tv/r/$px/pic/crt/l/$rest"
    }

    /** 补全协议并升级 https：接口里混着 http:// 与 // 开头的地址，App 禁明文流量 */
    private fun normalize(raw: String?): String? {
        val src = raw?.trim().orEmpty()
        if (src.isEmpty()) return null
        return when {
            src.startsWith("//") -> "https:$src"
            src.startsWith("http://") -> "https://" + src.removePrefix("http://")
            else -> src
        }
    }
}
