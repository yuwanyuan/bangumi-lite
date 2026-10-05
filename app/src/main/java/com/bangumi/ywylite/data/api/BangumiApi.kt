package com.bangumi.ywylite.data.api

import com.bangumi.ywylite.data.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Route as OkRoute
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

class BangumiApi {

    private val typePaths = mapOf(
        1 to "book",
        2 to "anime",
        3 to "music",
        4 to "game",
        6 to "real"
    )

    var accessToken: String? = null

    /** 直接登录保存的 refresh_token，供 access_token 过期后自动续期 */
    var refreshToken: String? = null

    /** 触发 401 的请求所携带的失效 token（用于去重并发续期） */
    var onTokenInvalid: ((String?) -> Unit)? = null

    /** 自动续期成功后回调（持久化新 token；refresh_token 会轮换） */
    var onTokensRefreshed: ((OAuthToken) -> Unit)? = null

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

    // Web 端会话 Cookie（登录流程用，进程内保存）。
    // 注意：必须声明在 _webClient/_oauthClient 之前——字段初始化时 createClient 会引用它们
    private val webCookieStore = ConcurrentHashMap<String, Cookie>()
    private val webCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { c -> webCookieStore["${c.domain}|${c.name}"] = c }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            webCookieStore.values.filter { it.matches(url) }.toList()
    }

    // 官方 API 与 Web 端域名，可由设置切换（见 updateApiHost）
    private var apiBaseUrl = "https://api.bgm.tv"
    private var webBaseUrl = "https://bgm.tv"
    private var _client: HttpClient = createClient(apiBaseUrl, throwOnError = true)
    private var _nextClient: HttpClient = createClient("https://next.bgm.tv", throwOnError = true)
    private var _webClient: HttpClient = createClient(webBaseUrl, withCookies = true)
    // OAuth 授权步骤需要读取 302 Location，单独建一个不跟随重定向、共享 Cookie 的客户端
    private var _oauthClient: HttpClient = createClient(webBaseUrl, withCookies = true, followRedirects = false)

    val client: HttpClient get() = _client
    val nextClient: HttpClient get() = _nextClient
    val webClient: HttpClient get() = _webClient

    // 直接登录用（OAuth authorization_code 授权码模式，官方不支持 password 直换）
    private val oauthAppId = "bgm72386ac2893460ce3"
    private val oauthAppSecret = "fbb6ceba1289a8854d3a88af87ac6dfd"
    private val oauthRedirectUri = "https://bgm.tv/dev/app"

    private fun createClient(
        baseUrl: String,
        withCookies: Boolean = false,
        followRedirects: Boolean = true,
        throwOnError: Boolean = false
    ): HttpClient {
        val proxyConfig = buildProxyConfig()
        val okHttpBuilder = OkHttpClient.Builder()
        if (withCookies) {
            okHttpBuilder.cookieJar(webCookieJar)
        }
        if (!followRedirects) {
            okHttpBuilder.followRedirects(false).followSslRedirects(false)
        }
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
            // 非 2xx 必须抛异常：否则错误体会被当成正常数据解析出空对象（收藏状态假“未收藏”等）
            expectSuccess = throwOnError
            install(ContentNegotiation) {
                json(json)
            }
            // 直连 bgm 链路偶发连接被对端中断，GET 幂等请求自动重试，避免偶发 connection closed 直接抛到界面
            install(HttpRequestRetry) {
                maxRetries = 2
                retryOnExceptionIf { request, _ -> request.method == HttpMethod.Get }
                retryIf { request, response -> request.method == HttpMethod.Get && response.status.value in 500..599 }
                constantDelay(millis = 500)
            }
            install(DefaultRequest) {
                url(baseUrl)
            }
            install(HttpCallValidator) {
                handleResponseException { exception ->
                    val response = (exception as? ClientRequestException)?.response
                    if (response?.status == HttpStatusCode.Unauthorized) {
                        onTokenInvalid?.invoke(
                            response.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                        )
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
        rebuildClients()
    }

    /**
     * 切换官方 API / Web 端域名并全局重建客户端（保留当前代理配置）。
     * 空白值表示不修改对应域名。
     */
    fun updateApiHost(apiHost: String? = null, webHost: String? = null) {
        var changed = false
        apiHost?.takeIf { it.isNotBlank() && it != apiBaseUrl }?.let { apiBaseUrl = it; changed = true }
        webHost?.takeIf { it.isNotBlank() && it != webBaseUrl }?.let { webBaseUrl = it; changed = true }
        if (changed) rebuildClients()
    }

    private fun rebuildClients() {
        _client.close()
        _nextClient.close()
        _webClient.close()
        _oauthClient.close()
        _client = createClient(apiBaseUrl, throwOnError = true)
        _nextClient = createClient("https://next.bgm.tv", throwOnError = true)
        _webClient = createClient(webBaseUrl, withCookies = true)
        _oauthClient = createClient(webBaseUrl, withCookies = true, followRedirects = false)
    }

    fun updateToken(token: String?) {
        accessToken = token
    }

    /**
     * 连通性测试：向目标域名发一个短超时 GET，服务端有任何 HTTP 响应（含 4xx）即视为通。
     * 用于设置页 API / Web 地址的绿/红状态显示。
     */
    suspend fun pingHost(url: String): Boolean = withTimeout(6000) {
        runCatching {
            webClient.get(url).status.value < 500
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // 直接登录（网页会话 → OAuth 授权码 → access_token，7 天有效可刷新续期）
    // ------------------------------------------------------------------

    /** 取登录页 formhash（顺带建立会话 Cookie） */
    suspend fun getWebLoginFormHash(): String {
        val html = webClient.get("/login").bodyAsText()
        return Regex("""<input type="hidden" name="formhash" value="(.+?)">""").find(html)
            ?.groupValues?.get(1)
            ?: throw IllegalStateException("登录页解析失败，请稍后重试")
    }

    /** 登录验证码图片 */
    suspend fun getWebLoginCaptcha(): ByteArray {
        return webClient.get("/signup/captcha?${System.currentTimeMillis()}1").body()
    }

    /** 直接登录：邮箱 + 密码 + 验证码 → OAuth Token */
    suspend fun loginWithWebAccount(email: String, password: String, captcha: String): OAuthToken {
        val formhash = getWebLoginFormHash()

        // 网页登录（bgm 直接返回 200 + Set-Cookie，不走 302）
        val loginHtml = webClient.post("/FollowTheRabbit") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(parameters {
                append("formhash", formhash)
                append("referer", "")
                append("dreferer", "")
                append("email", email)
                append("password", password)
                append("captcha_challenge_field", captcha)
                append("loginsubmit", "登录")
            }))
        }.bodyAsText()

        if (loginHtml.contains("分钟内您将不能登录本站")) {
            throw IllegalStateException("累计 5 次错误尝试，15 分钟内将不能登录，请稍后再试")
        }
        val hasSession = webCookieStore.values.any { it.name == "chii_auth" && it.value.isNotBlank() }
        if (!hasSession) {
            throw IllegalStateException("登录失败：邮箱、密码或验证码可能不正确")
        }

        val code = authorizeAndGetCode(formhash)
        return exchangeOAuthCode(code)
    }

    /** 请求授权并从 302 回调地址中提取 code；已授权过的会直接 302 */
    private suspend fun authorizeAndGetCode(loginFormHash: String): String {
        val getResponse = _oauthClient.get("/oauth/authorize") {
            url {
                parameters.append("client_id", oauthAppId)
                parameters.append("response_type", "code")
                parameters.append("redirect_uri", oauthRedirectUri)
            }
        }
        extractCodeFromUrl(getResponse.headers["Location"])?.let { return it }
        val consentHtml = getResponse.bodyAsText()
        val authorizeHash = Regex("""name="formhash" value="(.+?)"""").find(consentHtml)
            ?.groupValues?.get(1) ?: loginFormHash

        val postResponse = _oauthClient.post("/oauth/authorize") {
            url {
                parameters.append("client_id", oauthAppId)
                parameters.append("response_type", "code")
                parameters.append("redirect_uri", oauthRedirectUri)
            }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(parameters {
                append("formhash", authorizeHash)
                append("redirect_uri", "")
                append("client_id", oauthAppId)
                append("submit", "授权")
            }))
        }
        extractCodeFromUrl(postResponse.headers["Location"])?.let { return it }
        throw IllegalStateException("授权失败：未获取到授权码（${postResponse.status.value}）")
    }

    private fun extractCodeFromUrl(url: String?): String? {
        if (url == null) return null
        return Regex("""[?&]code=([0-9a-zA-Z]+)""").find(url)?.groupValues?.get(1)
    }

    private suspend fun exchangeOAuthCode(code: String): OAuthToken {
        return webClient.post("/oauth/access_token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(parameters {
                append("grant_type", "authorization_code")
                append("client_id", oauthAppId)
                append("client_secret", oauthAppSecret)
                append("code", code)
                append("redirect_uri", oauthRedirectUri)
            }))
        }.body()
    }

    /** 用 refresh_token 换新的 access_token（每次刷新同时轮换 refresh_token） */
    suspend fun refreshAccessToken(refreshToken: String): OAuthToken {
        return webClient.post("/oauth/access_token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(parameters {
                append("grant_type", "refresh_token")
                append("client_id", oauthAppId)
                append("client_secret", oauthAppSecret)
                append("refresh_token", refreshToken)
            }))
        }.body()
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
            withAuth()
        }.body()
    }

    suspend fun getSubject(id: Int): Subject {
        return client.get("/v0/subjects/${id}") {
            withAuth()
        }.body()
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
            withAuth()
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
        comment: String? = null,
        tags: List<String>? = null
    ) {
        if (type == 0) {
            client.delete("/v0/users/-/collections/${subjectId}") {
                withAuth()
            }
        } else {
            client.post("/v0/users/-/collections/${subjectId}") {
                withAuth()
                contentType(ContentType.Application.Json)
                setBody(CollectionModifyPayload(type = type, comment = comment, rate = rate, tags = tags))
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

    /**
     * 获取用户对指定条目的收藏。
     * 注意：v0 的读取端点必须带 username（`-` 路径只有 POST/PATCH，没有 GET）；
     * 带 token 查询自己时可读私密收藏；返回 null 表示未收藏（404）。
     */
    suspend fun getSubjectCollection(username: String, subjectId: Int, accessToken: String? = null): UserCollection? {
        return try {
            client.get("/v0/users/${username}/collections/${subjectId}") {
                if (!accessToken.isNullOrBlank()) {
                    header("Authorization", "Bearer $accessToken")
                }
            }.body()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) null else throw e
        }
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
        return client.get("/v0/subjects/${subjectId}/subjects") {
            withAuth()
        }.body()
    }

    suspend fun getSubjectCharacters(subjectId: Int): List<CharacterItem> {
        return client.get("/v0/subjects/${subjectId}/characters") {
            withAuth()
        }.body()
    }

    suspend fun getEpisodeCollection(
        subjectId: Int,
        offset: Int = 0,
        limit: Int = 200,
        accessToken: String? = null
    ): List<EpisodeCollection> {
        val response = client.get("/v0/users/-/collections/${subjectId}/episodes") {
            val bearer = accessToken ?: this@BangumiApi.accessToken
            if (bearer != null) {
                header("Authorization", "Bearer $bearer")
            }
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

    /** 「看到第 eps 话」：把第 1..eps 话全部标为看过（legacy 接口，token 认证） */
    suspend fun markWatchedUpTo(subjectId: Int, eps: Int) {
        client.post("/subject/${subjectId}/update/watched_eps") {
            withAuth()
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(parameters { append("watched_eps", eps.toString()) }))
        }
    }

    /** 章节页（bgm.tv/ep/{id}）吐槽箱评论，HTML 解析，只取主楼 */
    suspend fun getEpisodeComments(episodeId: Int): List<EpisodeComment> {
        val response = webClient.get("/ep/${episodeId}")
        return parseEpisodeCommentsFromHtml(response.bodyAsText())
    }

    private fun parseEpisodeCommentsFromHtml(html: String): List<EpisodeComment> {
        val start = html.indexOf("<div id=\"comment_list\"")
        if (start < 0) return emptyList()
        val scope = html.substring(start)

        val comments = mutableListOf<EpisodeComment>()
        val mainRowRegex = Regex("""^\d+"[^>]*class="[^"]*row row_reply""")
        val chunks = scope.split("<div id=\"post_").drop(1)
        chunks.forEach { chunk ->
            if (!mainRowRegex.containsMatchIn(chunk.take(300))) return@forEach

            val id = chunk.substringBefore('"').toIntOrNull() ?: return@forEach
            val username = Regex("""data-item-user="([^"]*)"""").find(chunk)?.groupValues?.get(1) ?: ""
            val nickname = Regex("""<strong><a href="/user/[^"]*"[^>]*>([^<]+)</a>""").find(chunk)
                ?.groupValues?.get(1)?.trim()?.ifEmpty { username } ?: username
            val avatar = Regex("""background-image:url\('([^']+)'\)""").find(chunk)
                ?.groupValues?.get(1)?.let { if (it.startsWith("//")) "https:$it" else it } ?: ""
            val floorTime = Regex("""floor-anchor">#(\d+)</a>\s*-\s*([^<]+)""").find(chunk)
            val floor = floorTime?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val time = floorTime?.groupValues?.get(2)?.trim() ?: ""
            val content = Regex("""<div class="message[^"]*">([\s\S]*?)</div>""").find(chunk)
                ?.groupValues?.get(1)?.let { htmlToText(it) } ?: ""

            comments.add(EpisodeComment(id, username, nickname, avatar, floor, time, content))
        }
        return comments
    }

    private fun htmlToText(html: String): String {
        return html
            .replace(Regex("""<img[^>]*alt="([^"]*)"[^>]*>"""), "$1")
            .replace(Regex("""<br\s*/?>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
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
            withAuth()
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
