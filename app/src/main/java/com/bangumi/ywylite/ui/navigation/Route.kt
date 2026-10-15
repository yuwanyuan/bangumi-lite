package com.bangumi.ywylite.ui.navigation

import android.net.Uri

sealed class Route(val path: String) {
    data object Calendar : Route("calendar")
    data object Search : Route("search")
    data object Explore : Route("explore")
    data object Collection : Route("collection")
    data object Profile : Route("profile")
    data object SubjectDetail : Route("subject/{id}") {
        fun create(id: Int) = "subject/$id"
    }
    data object Settings : Route("settings")
    data object AccountSettings : Route("account_settings")
    data object CacheSettings : Route("cache_settings")
    data object ProxySettings : Route("proxy_settings")
    data object Tags : Route("tags/{typePath}") {
        fun create(typePath: String) = "tags/$typePath"
    }
    data object TagBrowse : Route("tag_browse?tagName={tagName}&tagSlug={tagSlug}&type={type}") {
        /**
         * 标签名走查询参数，必须用 [Uri.encode]（RFC 3986）而不是 URLEncoder：
         * 后者是表单编码，空格会编成 '+'，而导航取参时 '+' 不会还原成空格，
         * 「Fate Zero」这类带空格的标签会请求到不存在的标签。
         */
        fun create(tagName: String, tagSlug: String, type: Int) =
            "tag_browse?tagName=${Uri.encode(tagName)}&tagSlug=${Uri.encode(tagSlug)}&type=$type"
    }
}
