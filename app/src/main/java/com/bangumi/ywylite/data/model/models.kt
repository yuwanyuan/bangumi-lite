package com.bangumi.ywylite.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class Avatar(
    val medium: String = "",
    val large: String = "",
    val small: String = ""
)

@Serializable
data class User(
    val id: Int = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: Avatar? = null,
    val sign: String = ""
)

@Serializable
data class Weekday(
    val en: String = "",
    val cn: String = "",
    val ja: String = ""
)

@Serializable
data class CalendarDay(
    val weekday: Weekday,
    val items: List<SubjectSmall>
)

@Serializable
data class SubjectImages(
    val medium: String = "",
    val large: String = "",
    val common: String = "",
    val small: String = "",
    val grid: String = ""
)

@Serializable
data class RatingCount(
    val score1: Int = 0,
    val score2: Int = 0,
    val score3: Int = 0,
    val score4: Int = 0,
    val score5: Int = 0,
    val score6: Int = 0,
    val score7: Int = 0,
    val score8: Int = 0,
    val score9: Int = 0,
    val score10: Int = 0
)

@Serializable
data class Rating(
    val total: Int = 0,
    val score: Double = 0.0,
    val count: RatingCount = RatingCount()
)

@Serializable
data class TagInfo(
    val name: String = "",
    /** 标注人数：标签索引页给的原始数字，不是该标签下的条目数 */
    val count: Int = 0,
    /** 该标签下的真实条目数（与点进去看到的列表一致）；-1 = 尚未取到 */
    val subjectCount: Int = -1
)

@Serializable
data class SubjectSmall(
    val id: Int = 0,
    val type: Int = 0,
    val name: String = "",
    @SerialName("name_cn")
    val nameCn: String = "",
    val summary: String = "",
    val image: String = "",
    val images: SubjectImages? = null,
    val rating: Rating? = null,
    val tags: List<TagInfo> = emptyList(),
    val url: String = "",
    val eps: Int = 0
)

@Serializable
data class Subject(
    val id: Int = 0,
    val type: Int = 0,
    val name: String = "",
    @SerialName("name_cn")
    val nameCn: String = "",
    val summary: String = "",
    val image: String = "",
    val images: SubjectImages? = null,
    val rating: Rating? = null,
    val tags: List<TagInfo> = emptyList(),
    val url: String = "",
    val eps: Int = 0,
    // v0 接口的日期字段名是 date（airDate 是旧接口字段名，v0 下恒为空）
    @SerialName("date")
    val airDate: String = "",
    @SerialName("total_episodes")
    val totalEpisodes: Int = 0,
    val collection: CollectionCount? = null,
    val comment: Int = 0,
    val infobox: List<InfoboxItem> = emptyList()
)

/** 条目信息盒（infobox）条目；value 可能是字符串，也可能是 [{v: "..."}] 数组（多人/多项） */
@Serializable
data class InfoboxItem(
    val key: String = "",
    val value: JsonElement = JsonNull,
    /** 值内的超链接（网页版里人名/公司名可点进对应页面），text 为值文本中的原文 */
    val links: List<InfoboxLink> = emptyList()
)

@Serializable
data class InfoboxLink(
    val text: String = "",
    val href: String = ""
)

/** /v0/subjects/{id}/characters 的角色条目；v0 不提供 name_cn */
@Serializable
data class CharacterItem(
    val id: Int = 0,
    val name: String = "",
    val type: Int = 0,
    val relation: String = "",
    val summary: String = "",
    val images: SubjectImages? = null,
    val actors: List<CharacterActor> = emptyList()
)

@Serializable
data class CharacterActor(
    val id: Int = 0,
    val name: String = "",
    val images: PersonImages? = null
)

@Serializable
data class CollectionCount(
    val wish: Int = 0,
    val done: Int = 0,
    val doing: Int = 0,
    @SerialName("on_hold")
    val onHold: Int = 0,
    val dropped: Int = 0
)

@Serializable
data class UserCollection(
    @SerialName("subject_id")
    val subjectId: Int = 0,
    val subject: SubjectSmall? = null,
    val type: Int? = null,
    val comment: String = "",
    @SerialName("private")
    val private: Boolean = false,
    val rate: Int = 0,
    val tags: List<String> = emptyList()
)

@Serializable
data class PagedSubject(
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val data: List<SubjectSmall> = emptyList()
)

@Serializable
data class PagedUserCollection(
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val data: List<UserCollection> = emptyList()
)

@Serializable
data class Episode(
    val id: Int = 0,
    val type: Int = 0,
    val name: String = "",
    @SerialName("name_cn")
    val nameCn: String = "",
    val sort: Double = 0.0,
    val ep: Int? = null,
    val airdate: String = "",
    val comment: Int = 0,
    val duration: String = "",
    val desc: String = ""
)

@Serializable
data class PagedEpisode(
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val data: List<Episode> = emptyList()
)

@Serializable
data class EpisodeCollection(
    val episode: Episode? = null,
    val type: Int? = null
)

@Serializable
data class PagedEpisodeCollection(
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val data: List<EpisodeCollection> = emptyList()
)

@Serializable
data class EpisodeStatusUpdate(
    val type: Int = 2
)

@Serializable
data class SearchFilter(
    val type: List<Int>? = null,
    /** 用户标签，多值之间为「且」；标签索引据此统计标签下的真实条目数 */
    val tag: List<String>? = null,
    /** 播出/发售日期窗口，如 [">=2026-07-01"]；多值之间为「且」。encodeDefaults=false，null 时不出现在请求体 */
    val air_date: List<String>? = null
)

@Serializable
data class SearchRequest(
    val keyword: String,
    val sort: String = "match",
    val filter: SearchFilter
)

@Serializable
data class CollectionModifyPayload(
    val type: Int? = null,
    val comment: String? = null,
    val rate: Int? = null,
    val privacy: Boolean? = null,
    val tags: List<String>? = null
)

/** /oauth/access_token 返回 */
@Serializable
data class OAuthToken(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("user_id") val userId: Int = 0
)

@Serializable
data class RelatedSubject(
    val id: Int = 0,
    val type: Int = 0,
    val name: String = "",
    @SerialName("name_cn")
    val nameCn: String = "",
    val relation: String = "",
    val images: SubjectImages? = null,
    val url: String = ""
)

@Serializable
data class RelatedPerson(
    val id: Int = 0,
    val name: String = "",
    val relation: String = "",
    val images: PersonImages? = null
)

@Serializable
data class PersonImages(
    val medium: String = "",
    val large: String = "",
    val small: String = ""
)

@Serializable
data class CommentUser(
    val id: Int = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: Avatar? = null
)

@Serializable
data class CommentItem(
    val id: Int = 0,
    val user: CommentUser? = null,
    val comment: String = "",
    val rate: Int = 0,
    @SerialName("updated_at")
    val updatedAt: Long = 0
)

@Serializable
data class CommentResponse(
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val data: List<CommentItem> = emptyList()
)

/** 章节页（bgm.tv/ep/{id}）吐槽箱单条评论，由 HTML 解析得到 */
data class EpisodeComment(
    val id: Int = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val floor: Int = 0,
    val time: String = "",
    val content: String = ""
)

@Serializable
data class TimelineItem(
    val id: Int = 0,
    val type: String = "",
    val subjectId: Int = 0,
    val subjectName: String = "",
    val subjectNameCn: String = "",
    val subjectImage: String = "",
    val episodeUrl: String = "",
    val episodeInfo: String = "",
    val description: String = "",
    val time: String = "",
    val userRating: Int = 0,
    val userComment: String = "",
    val subjectScore: Double = 0.0,
    val subjectRank: Int = 0
)

/** 标签浏览页（网页版 /anime/tag/... HTML 解析），网页每页固定 24 条 */
data class TagBrowsePage(
    val subjects: List<SubjectSmall> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1
)
