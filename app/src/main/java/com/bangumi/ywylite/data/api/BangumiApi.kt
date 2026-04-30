package com.bangumi.ywylite.data.api

import com.bangumi.ywylite.data.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Route as OkRoute
import java.net.InetSocketAddress
import java.net.Proxy

class BangumiApi {

    private val typePaths = mapOf(
        1 to "book",
        2 to "anime",
        3 to "music",
        4 to "game",
        6 to "real"
    )

    var accessToken: String? = null
    var onTokenInvalid: (() -> Unit)? = null

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private var currentProxyEnabled = false
    private var currentProxyType = "HTTP"
    private var currentProxyHost = ""
    private var currentProxyPort = 7890
    private var currentProxyUsername = ""
    private var currentProxyPassword = ""

    private var _client: HttpClient = createClient("https://api.bgm.tv")
    private var _nextClient: HttpClient = createClient("https://next.bgm.tv")
    private var _webClient: HttpClient = createClient("https://bgm.tv")

    val client: HttpClient get() = _client
    val nextClient: HttpClient get() = _nextClient
    val webClient: HttpClient get() = _webClient

    private fun createClient(baseUrl: String): HttpClient {
        val proxyConfig = buildProxyConfig()
        val okHttpBuilder = OkHttpClient.Builder()
        if (proxyConfig != null) {
            okHttpBuilder.proxy(proxyConfig.proxy)
            val auth = proxyConfig.authenticator
            if (auth != null) {
                okHttpBuilder.proxyAuthenticator(auth)
            }
        }
        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpBuilder.build()
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(DefaultRequest) {
                url(baseUrl)
            }
            install(HttpCallValidator) {
                handleResponseException { exception ->
                    val response = (exception as? ClientRequestException)?.response
                    if (response?.status == HttpStatusCode.Unauthorized) {
                        onTokenInvalid?.invoke()
                    }
                    throw exception
                }
            }
        }
    }

    private data class ProxyConfig(
        val proxy: Proxy,
        val authenticator: Authenticator? = null
    )

    private fun buildProxyConfig(): ProxyConfig? {
        if (!currentProxyEnabled || currentProxyHost.isEmpty()) return null
        val proxyType = when (currentProxyType.uppercase()) {
            "SOCKS" -> java.net.Proxy.Type.SOCKS
            else -> java.net.Proxy.Type.HTTP
        }
        val proxy = Proxy(proxyType, InetSocketAddress(currentProxyHost, currentProxyPort))
        val authenticator = if (currentProxyUsername.isNotEmpty()) {
            ProxyAuthenticator(currentProxyUsername, currentProxyPassword)
        } else null
        return ProxyConfig(proxy, authenticator)
    }

    fun updateProxy(enabled: Boolean, type: String, host: String, port: Int, username: String, password: String) {
        currentProxyEnabled = enabled
        currentProxyType = type
        currentProxyHost = host
        currentProxyPort = port
        currentProxyUsername = username
        currentProxyPassword = password
        _client.close()
        _nextClient.close()
        _webClient.close()
        _client = createClient("https://api.bgm.tv")
        _nextClient = createClient("https://next.bgm.tv")
        _webClient = createClient("https://bgm.tv")
    }

    fun updateToken(token: String?) {
        accessToken = token
    }

    private fun HttpRequestBuilder.withAuth() {
        accessToken?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    suspend fun getCalendar(): List<CalendarDay> {
        return client.get("/calendar").body()
    }

    suspend fun searchSubjects(
        keyword: String,
        type: List<Int>? = null,
        sort: String = "match",
        offset: Int = 0,
        limit: Int = 20
    ): PagedSubject {
        return nextClient.post("/v0/search/subjects") {
            url {
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }
            contentType(ContentType.Application.Json)
            setBody(SearchRequest(keyword, sort, SearchFilter(type)))
        }.body()
    }

    suspend fun getSubject(id: Int): Subject {
        return client.get("/v0/subjects/${id}").body()
    }

    suspend fun getEpisodes(
        subjectId: Int,
        type: Int? = null,
        offset: Int = 0,
        limit: Int = 100
    ): PagedEpisode {
        return client.get("/v0/episodes") {
            url {
                parameters.append("subject_id", subjectId.toString())
                type?.let { parameters.append("type", it.toString()) }
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }
        }.body()
    }

    suspend fun getMe(): User {
        return client.get("/v0/me") {
            withAuth()
        }.body()
    }

    suspend fun getUserCollections(
        username: String,
        subjectType: Int? = null,
        collectionType: Int? = null,
        offset: Int = 0,
        limit: Int = 30
    ): PagedUserCollection {
        return client.get("/v0/users/${username}/collections") {
            url {
                subjectType?.let { parameters.append("subject_type", it.toString()) }
                collectionType?.let { parameters.append("type", it.toString()) }
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }
            withAuth()
        }.body()
    }

    suspend fun collectSubject(
        subjectId: Int,
        type: Int,
        rate: Int? = null,
        comment: String? = null
    ) {
        if (type == 0) {
            client.delete("/v0/users/-/collections/${subjectId}") {
                withAuth()
            }
        } else {
            client.post("/v0/users/-/collections/${subjectId}") {
                withAuth()
                contentType(ContentType.Application.Json)
                setBody(CollectionModifyPayload(type = type, comment = comment, rate = rate))
            }
        }
    }

    suspend fun updateCollection(
        subjectId: Int,
        type: Int? = null,
        comment: String? = null,
        private: Boolean? = null
    ) {
        client.patch("/v0/users/-/collections/${subjectId}") {
            withAuth()
            contentType(ContentType.Application.Json)
            setBody(CollectionModifyPayload(type = type, comment = comment, privacy = private))
        }
    }

    suspend fun getSubjectCollection(subjectId: Int, accessToken: String? = null): UserCollection {
        return client.get("/v0/users/-/collections/${subjectId}") {
            if (accessToken != null) {
                header("Authorization", "Bearer $accessToken")
            }
        }.body()
    }

    suspend fun getComments(
        subjectId: Int,
        offset: Int = 0,
        limit: Int = 20
    ): CommentResponse {
        return nextClient.get("/p1/subjects/${subjectId}/comments") {
            url {
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }
        }.body()
    }

    suspend fun getRelatedSubjects(subjectId: Int): List<RelatedSubject> {
        return client.get("/v0/subjects/${subjectId}/related").body()
    }

    suspend fun getEpisodeCollection(
        subjectId: Int,
        offset: Int = 0,
        limit: Int = 200
    ): List<EpisodeCollection> {
        val response = client.get("/v0/users/-/collections/${subjectId}/episodes") {
            withAuth()
            url {
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }
        }
        val paged = response.body<PagedEpisodeCollection>()
        return paged.data
    }

    suspend fun updateEpisodeStatus(
        episodeId: Int,
        type: Int = 2
    ) {
        client.put("/v0/users/-/collections/-/episodes/${episodeId}") {
            withAuth()
            contentType(ContentType.Application.Json)
            setBody(EpisodeStatusUpdate(type = type))
        }
    }

    suspend fun browseByTag(
        type: Int = 2,
        tag: String = "",
        sort: String = "",
        offset: Int = 0,
        limit: Int = 30
    ): PagedSubject {
        val typePath = typePaths[type] ?: "anime"
        val encodedTag = java.net.URLEncoder.encode(tag, "UTF-8")
        val response = webClient.get("/${typePath}/tag/${encodedTag}") {
            url {
                if (sort.isNotEmpty()) parameters.append("sort", sort)
                parameters.append("page", ((offset / limit) + 1).toString())
            }
        }
        return parseSubjectsFromHtml(response.bodyAsText(), offset, limit)
    }

    private fun parseSubjectsFromHtml(html: String, offset: Int, limit: Int): PagedSubject {
        val subjects = mutableListOf<SubjectSmall>()

        val blockRegex = Regex("""<li id="item_(\d+)"[^>]*>([\s\S]*?)</li>""")
        blockRegex.findAll(html).forEach { blockMatch ->
            val id = blockMatch.groupValues[1].toIntOrNull() ?: return@forEach
            val block = blockMatch.groupValues[2]

            val image = Regex("""<img[^>]+src="([^"]*)"""").find(block)?.groupValues?.get(1)?.let { img ->
                if (img.startsWith("//")) "https:$img" else img
            } ?: ""

            val nameCn = Regex("""<h3>\s*<a[^>]*>([^<]+)</a>""").find(block)?.groupValues?.get(1)?.trim() ?: ""

            val name = Regex("""<small[^>]*>([^<]+)</small>""").find(block)?.groupValues?.get(1)?.trim()?.ifEmpty { nameCn } ?: nameCn

            val info = Regex("""<p class="info tip">\s*([^<]+)</p>""").find(block)?.groupValues?.get(1)?.trim() ?: ""

            val eps = Regex("""(\d+)\s*话""").find(info)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\s*集""").find(info)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0

            val ratingScore = Regex("""<small class="fade">([\d.]+)</small>""").find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

            val ratingTotal = Regex("""\((\d+)人评分\)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val rating = if (ratingScore > 0) Rating(score = ratingScore, total = ratingTotal) else null

            subjects.add(SubjectSmall(
                id = id,
                name = name,
                nameCn = nameCn,
                summary = info,
                image = image,
                images = SubjectImages(medium = image),
                rating = rating,
                eps = eps
            ))
        }

        val totalPages = Regex("""<span class="p_edge">\(&nbsp;\d+&nbsp;/&nbsp;(\d+)&nbsp;\)</span>""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val total = totalPages * subjects.size.coerceAtLeast(1)

        return PagedSubject(total = total, offset = offset, limit = limit, data = subjects)
    }

    suspend fun getUserTimeline(
        username: String,
        type: String? = null,
        page: Int = 1
    ): List<TimelineItem> {
        val url = "/user/${username}/timeline"
        val response = webClient.get(url) {
            url {
                type?.let { parameters.append("type", it) }
                parameters.append("page", page.toString())
            }
        }
        return parseTimelineFromHtml(response.bodyAsText())
    }

    private fun parseTimelineFromHtml(html: String): List<TimelineItem> {
        val items = mutableListOf<TimelineItem>()

        val itemRegex = Regex("""id="tml_(\d+)"[^>]*class="clearit tml_item"[\s\S]*?</li>""")
        itemRegex.findAll(html).forEach { match ->
            val tmlId = match.groupValues[1].toIntOrNull() ?: return@forEach
            val block = match.groupValues[0]

            val desc = Regex("""class="info_full[^"]*"[^>]*>([\s\S]*?)<div class="card""").find(block)?.groupValues?.get(1)?.let {
                Regex("""<[^>]+>""").replace(it, "").trim()
            } ?: Regex("""class="info_full[^"]*"[^>]*>([\s\S]*?)</span>""").find(block)?.groupValues?.get(1)?.let {
                Regex("""<[^>]+>""").replace(it, "").trim()
            } ?: ""

            val subjectLink = Regex("""href="https://bgm\.tv/subject/(\d+)"""").find(block)
            val subjectId = subjectLink?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val dataNameCn = Regex("""data-subject-name-cn="([^"]*)"""").find(block)?.groupValues?.get(1)?.trim() ?: ""
            val dataName = Regex("""data-subject-name="([^"]*)"""").find(block)?.groupValues?.get(1)?.trim() ?: ""
            val smallName = Regex("""<small class="subtitle grey">([^<]*)</small>""").find(block)?.groupValues?.get(1)?.trim() ?: ""
            val linkName = Regex("""<p class="title"><a[^>]*>([\s\S]*?)</a>""").find(block)?.groupValues?.get(1)?.let {
                Regex("""<[^>]+>""").replace(it, "").trim()
            } ?: ""

            val subjectNameCn = dataNameCn.ifEmpty { if (smallName.isNotEmpty()) linkName else "" }
            val subjectName = dataName.ifEmpty { smallName.ifEmpty { linkName } }

            val image = Regex("""src="(//lain\.bgm\.tv/[^"]*cover[^"]*)"""").find(block)?.groupValues?.get(1)?.let {
                if (it.startsWith("//")) "https:$it" else it
            } ?: ""

            val epLink = Regex("""href="(https://bgm\.tv/subject/ep/\d+)"""").find(block)?.groupValues?.get(1) ?: ""

            val episodeInfo = Regex("""href="https://bgm\.tv/subject/ep/\d+[^>]*>([^<]+)""").find(block)?.groupValues?.get(1)?.trim() ?: ""

            val progressInfo = Regex("""(\d+)\s*of\s*(\d+)\s*话""").find(desc)
            val episodeDisplay = when {
                episodeInfo.isNotEmpty() -> episodeInfo
                progressInfo != null -> "${progressInfo.groupValues[1]}/${progressInfo.groupValues[2]}话"
                else -> ""
            }

            val time = Regex("""class="titleTip"[^>]*>([^<]*)</span>""").find(block)?.groupValues?.get(1)?.trim() ?: ""

            val userRating = Regex("""class="starlight stars(\d+)"""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val userComment = Regex("""class="comment">[\s\S]*?<span class="starstop[^"]*">[\s\S]*?</span>\s*([^<]+)""").find(block)?.groupValues?.get(1)?.trim() ?: ""

            val subjectScore = Regex("""<small class="fade">([\d.]+)</small>""").find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

            val subjectRank = Regex("""<span class="rank">#(\d+)</span>""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val type = when {
                desc.contains("看过") -> "watched"
                desc.contains("完成") -> "completed"
                desc.contains("在看") || desc.contains("看到") -> "watching"
                desc.contains("想看") -> "wish"
                desc.contains("搁置") -> "on_hold"
                desc.contains("抛弃") -> "dropped"
                desc.contains("吐槽") -> "comment"
                else -> "other"
            }

            items.add(TimelineItem(
                id = tmlId,
                type = type,
                subjectId = subjectId,
                subjectName = subjectName,
                subjectNameCn = subjectNameCn,
                subjectImage = image,
                episodeUrl = epLink,
                episodeInfo = episodeDisplay,
                description = desc,
                time = time,
                userRating = userRating,
                userComment = userComment,
                subjectScore = subjectScore,
                subjectRank = subjectRank
            ))
        }

        return items
    }

    suspend fun browseSubjects(
        type: Int = 2,
        cat: String = "",
        sort: String? = null,
        year: Int? = null,
        month: Int? = null,
        offset: Int = 0,
        limit: Int = 30
    ): PagedSubject {
        return client.get("/v0/subjects") {
            url {
                parameters.append("type", type.toString())
                if (cat.isNotEmpty()) parameters.append("cat", cat)
                sort?.let { parameters.append("sort", it) }
                year?.let { parameters.append("year", it.toString()) }
                month?.let { parameters.append("month", it.toString()) }
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }
        }.body()
    }

    suspend fun searchSubjectsByHeat(
        type: Int = 2,
        offset: Int = 0,
        limit: Int = 30
    ): PagedSubject {
        val response = nextClient.get("/p1/trending/subjects") {
            url {
                parameters.append("type", type.toString())
                parameters.append("offset", offset.toString())
                parameters.append("limit", limit.toString())
            }
        }
        val trendingResponse = json.decodeFromString<TrendingResponse>(response.bodyAsText())
        val subjects = trendingResponse.data.mapNotNull { item ->
            item.subject?.let { subject ->
                SubjectSmall(
                    id = subject.id,
                    type = subject.type,
                    name = subject.name,
                    nameCn = subject.nameCn,
                    image = subject.images?.medium ?: "",
                    images = subject.images?.let { img ->
                        SubjectImages(
                            medium = img.medium,
                            large = img.large,
                            common = img.common,
                            small = img.small,
                            grid = ""
                        )
                    },
                    rating = subject.rating?.let { rating ->
                        Rating(score = rating.score, total = rating.total)
                    }
                )
            }
        }
        return PagedSubject(
            total = trendingResponse.total,
            offset = offset,
            limit = limit,
            data = subjects
        )
    }

    suspend fun postSubjectComment(
        subjectId: Int,
        content: String
    ): Boolean {
        return try {
            val html = webClient.get("/subject/${subjectId}") {
                withAuth()
            }.bodyAsText()
            val formhash = Regex("""name="formhash"\s+value="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: Regex(""""formhash":"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: return false
            webClient.post("/update/user/say?ajax=1") {
                withAuth()
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(buildString {
                    append("say_input=")
                    append(java.net.URLEncoder.encode(content, "UTF-8"))
                    append("&formhash=")
                    append(formhash)
                    append("&submit=submit")
                })
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getTags(
        typePath: String = "anime",
    ): List<TagInfo> {
        val response = webClient.get("/${typePath}/tag")
        return parseTagsFromHtml(response.bodyAsText())
    }

    suspend fun getTagEpisodeCount(typePath: String, tagName: String): Int {
        val encodedTag = java.net.URLEncoder.encode(tagName, "UTF-8")
        val response = webClient.get("/${typePath}/tag/${encodedTag}") {
            url {
                parameters.append("page", "1")
            }
        }
        val result = parseSubjectsFromHtml(response.bodyAsText(), 0, 24)

        if (result.data.isEmpty()) return 0

        val totalSubjects = result.total
        val subjectsOnFirstPage = result.data.size
        val episodesOnFirstPage = result.data.sumOf { it.eps }

        if (subjectsOnFirstPage == 0 || episodesOnFirstPage == 0) return 0

        val avgEpisodesPerSubject = episodesOnFirstPage.toDouble() / subjectsOnFirstPage
        return (avgEpisodesPerSubject * totalSubjects).toInt()
    }

    private fun parseTagsFromHtml(html: String): List<TagInfo> {
        val tags = mutableListOf<TagInfo>()
        val tagRegex = Regex("""<a[^>]*href="[^"]*/tag/[^"]*"[^>]*>([^<]+)</a>\s*<small[^>]*>\((\d+)\)</small>""")
        tagRegex.findAll(html).forEach { match ->
            val name = match.groupValues[1].trim()
            val count = match.groupValues[2].toIntOrNull() ?: 0
            if (name.isNotEmpty()) {
                tags.add(TagInfo(name = name, count = count))
            }
        }
        return tags
    }
}

private class ProxyAuthenticator(
    private val username: String,
    private val password: String
) : Authenticator {
    override fun authenticate(route: OkRoute?, response: okhttp3.Response): Request? {
        val challenge = response.challenges().firstOrNull() ?: return null
        val credential = Credentials.basic(username, password, charset("UTF-8"))
        return response.request.newBuilder()
            .header("Proxy-Authorization", credential)
            .build()
    }
}
