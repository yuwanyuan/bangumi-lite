package com.bangumi.ywylite.ui.navigation

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
        fun create(tagName: String, tagSlug: String, type: Int) = "tag_browse?tagName=${java.net.URLEncoder.encode(tagName, "UTF-8")}&tagSlug=${java.net.URLEncoder.encode(tagSlug, "UTF-8")}&type=$type"
    }
}
