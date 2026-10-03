# Bangumi API 参考文档

> 整理时间：2026-10-04。来源：官方 OpenAPI 规范（[bangumi/api](https://github.com/bangumi/api) 的 `open-api/v0.yaml`）、[czy0729/Bangumi](https://github.com/czy0729/Bangumi) 客户端的接口实践、bgm.tv Web 页面 HTML 实测。供 BGM Lite 开发参考。
>
> 鉴权列含义：**公开** = 无需 token；**可选** = 带 token 返回更多信息；**必填** = 必须带 token。

## 0. 域名与通用约定

| 域名 | 用途 |
|------|------|
| `api.bgm.tv` / `api.bgmapi.com` | 官方 REST API（v0 与 legacy，同一服务，互为备用） |
| `next.bgm.tv` | 私有接口（`/p1/...`，无公开文档） |
| `bgm.tv` | Web 端（HTML 页面 / 站内 Ajax），镜像 `bangumi.tv`、`chii.in` |
| `lain.bgm.tv` | 图片 CDN（HTML 中的图片地址常为 `//lain.bgm.tv/...`，需补 `https:`） |

- **User-Agent 必须有意义**（含项目名/联系方式），否则可能被服务端拒绝。本应用使用 `bangumi-lite/1.0 (https://github.com/yuwanyuan/bangumi-lite)`。
- 网络环境：`api.bgm.tv` 部分网络下直连不稳（TLS 握手失败/超时），需走代理；`bgm.tv` 一般可直连。应用内已支持 HTTP/SOCKS5 代理。
- 认证方式：OAuth 2.0，请求头 `Authorization: Bearer {access_token}`。Token 申请见 [bangumi.dev](https://bangumi.dev)（Authorization Code / Device Flow）。

---

## 1. v0 REST API 全量端点（`https://api.bgm.tv`）

> 共 55 个，提取自官方 `v0.yaml`（2026-10 最新）。`{subject_id}` 等为路径参数。

### 1.1 条目

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/v0/subjects` | 可选 | 浏览条目（支持 `type`/`cat`/`sort`/`year`/`month`/分页） |
| GET | `/v0/subjects/{subject_id}` | 可选 | 获取条目详情 |
| GET | `/v0/subjects/{subject_id}/image` | 可选 | 条目封面（`?type=small/medium/large/common/medium/grid`） |
| GET | `/v0/subjects/{subject_id}/persons` | 可选 | 制作人员 |
| GET | `/v0/subjects/{subject_id}/characters` | 可选 | 角色 |
| GET | `/v0/subjects/{subject_id}/subjects` | 可选 | 关联条目 |
| GET | `/v0/episodes?subject_id={id}` | 可选 | 章节列表（支持 `type`=0正篇/1SP/2OP/3ED/4预告等、分页） |
| GET | `/v0/episodes/{episode_id}` | 可选 | 单个章节详情 |

### 1.2 搜索

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/v0/search/subjects` | 公开 | 条目搜索（body: `{keyword, sort, filter{type,tag,air_date,rating,rank,nsfw}}`，`?limit=&offset=`） |
| POST | `/v0/search/characters` | 公开 | 角色搜索 |
| POST | `/v0/search/persons` | 公开 | 人物搜索 |

### 1.3 用户

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/v0/me` | 必填 | 当前用户 |
| GET | `/v0/users/{username}` | 公开 | 用户信息（username 或数字 id） |
| GET | `/v0/users/{username}/avatar` | 公开 | 用户头像（`?type=large`，直接 302 到 lain 地址） |

### 1.4 条目收藏

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/v0/users/{username}/collections` | 可选 | 用户收藏列表（`?subject_type=&type=&分页`） |
| GET | `/v0/users/{username}/collections/{subject_id}` | 可选 | 用户单个条目收藏（自己访问可含私密） |
| GET | `/v0/users/-/collections/{subject_id}` | 必填 | 当前用户对条目的收藏 |
| POST | `/v0/users/-/collections/{subject_id}` | 必填 | 新增/修改收藏（body: `{type, rate, comment, privacy, tags}`，type: 1想看 2看过 3在看 4搁置 5抛弃） |
| PATCH | `/v0/users/-/collections/{subject_id}` | 必填 | 修改收藏（部分更新） |
| DELETE | `/v0/users/-/collections/{subject_id}` | 必填 | 取消收藏 |
| GET | `/v0/users/-/collections/{subject_id}/episodes` | 必填 | 当前用户对该条目各章节的观看状态列表 |
| PATCH | `/v0/users/-/collections/{subject_id}/episodes` | 必填 | 批量更新章节状态（body: `{episode_id, type}`，单个章节） |

### 1.5 章节收藏（观看状态）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/v0/users/-/collections/-/episodes/{episode_id}` | 必填 | 章节收藏信息 |
| PUT | `/v0/users/-/collections/-/episodes/{episode_id}` | 必填 | 更新章节收藏信息（body: `{type}`，EpisodeCollectionType: **0 未看 / 1 想看 / 2 看过 / 3 抛弃**） |

> 注意：v0 **没有**"看到第 N 话"批量端点。批量标记用 legacy API（见 2.2），或循环调用本端点。

### 1.6 角色 / 人物

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/v0/characters/{character_id}` | 公开 | 角色详情 |
| GET | `/v0/characters/{character_id}/image` | 可选 | 角色图片 |
| GET | `/v0/characters/{character_id}/subjects` | 公开 | 角色关联条目 |
| GET | `/v0/characters/{character_id}/persons` | 公开 | 角色关联人物（CV） |
| POST/DELETE | `/v0/characters/{character_id}/collect` | 必填 | 收藏/取消收藏角色 |
| GET | `/v0/persons/{person_id}` | 公开 | 人物详情 |
| GET | `/v0/persons/{person_id}/image` | 可选 | 人物图片 |
| GET | `/v0/persons/{person_id}/subjects` | 公开 | 人物关联条目 |
| GET | `/v0/persons/{person_id}/characters` | 公开 | 人物关联角色 |
| POST/DELETE | `/v0/persons/{person_id}/collect` | 必填 | 收藏/取消收藏人物 |
| GET | `/v0/users/{username}/collections/-/characters` 等 | 公开 | 用户角色/人物收藏列表与单项 |

### 1.7 目录（索引）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/v0/indices` | 公开 | 目录列表（已废弃入口，仅留档） |
| GET | `/v0/indices/{index_id}` | 可选 | 目录详情 |
| GET | `/v0/indices/{index_id}/subjects` | 可选 | 目录内条目 |
| POST | `/v0/indices` | 必填 | 创建目录 |
| PUT | `/v0/indices/{index_id}` | 必填 | 编辑目录信息 |
| POST/PUT/DELETE | `/v0/indices/{index_id}/subjects...` | 必填 | 目录内增删改条目 |
| POST/DELETE | `/v0/indices/{index_id}/collect` | 必填 | 收藏/取消收藏目录 |

### 1.8 版本历史

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/v0/revisions/{subjects\|persons\|characters\|episodes}` | 公开 | 各类条目的修订列表 |
| GET | `/v0/revisions/{...}/{revision_id}` | 公开 | 指定修订内容 |

---

## 2. Legacy API（老版，`https://api.bgm.tv`，Bearer token 可用）

> 来自 czy0729/Bangumi 客户端长期实践，路径不带 `/v0` 前缀。参数多为 query 或 form-urlencoded。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/calendar` | 每日放送（公开） |
| GET | `/user/{id}` | 用户信息（`{id}` 可为 username/uid） |
| GET | `/user/{id}/collection` | 用户收藏（`?cat=watching/all_watching&ids=1,2,3&responseGroup=medium/small`） |
| GET | `/user/{id}/collections/{type}` | 用户收藏概览（`type`=anime/book/music/game/real，`?max_results=`） |
| GET | `/user/{id}/collections/status` | 用户收藏统计 |
| GET | `/user/{id}/progress` | 用户收视进度（`?subject_id=` 指定条目） |
| GET | `/subject/{id}` | 条目信息（`?responseGroup=small/medium/large`） |
| GET | `/subject/{id}/ep` | 条目章节数据 |
| GET/POST | `/ep/{id}/status/{status}` | 更新收视进度。GET 单集；**POST 时 `ep_id` 参数支持逗号分隔批量**（如 `3697,3698,3699`）。`status` ∈ `watched`/`queue`/`drop`/`unset`？ |
| POST | `/subject/{id}/update/watched_eps` | **「看到第 N 话」**：form 参数 `watched_eps={N}`（书籍另支持 `watched_vols`），把第 1..N 话全部标为看过 |
| GET | `/collection/{id}` | 获取条目收藏信息 |
| GET/POST | `/collection/{id}/{action}` | 管理收藏。`action` ∈ `create`/`update`/`watched`/`queue`/`drop`/`remove`；参数 `status`(同上)/`tags`(空格分隔)/`comment`/`rating`(1-10)/`privacy`(0公开 1私密) |
| POST | `/oauth/access_token` | OAuth 换取 token（grant_type=authorization_code/refresh_token） |

---

## 3. next.bgm.tv 私有接口（`https://next.bgm.tv/p1`）

> 非官方公开文档，无鉴权，字段随时可能变化，做好容错。

| 方法 | 路径 | 说明 | 本应用使用 |
|------|------|------|:---:|
| GET | `/p1/subjects/{subject_id}/comments` | 条目评论（`?limit=&offset=`，返回 `{total, data:[{user, rate, comment}]}`） | ✅ |
| GET | `/p1/trending/subjects` | 热门条目（`?type=&limit=&offset=`） | ✅ |
| GET | `/p1/users/{id}/timeline` | 用户时间线 | |

---

## 4. Web 页面端点（`https://bgm.tv`，HTML 解析）

> REST API 未覆盖的数据走 HTML 解析。本应用已用：标签页、标签浏览页、用户时间线。

### 4.1 页面一览

| 路径 | 用途 | 本应用使用 |
|------|------|:---:|
| `/{type}/tag`（type∈book/anime/music/game/real） | 标签云 | ✅ |
| `/{type}/tag/{tag}?page=N` | 标签下条目列表 | ✅ |
| `/user/{username}/timeline?type=&page=N` | 用户时间线 | ✅ |
| `/subject/{id}` | 条目页 | |
| `/subject/{id}/ep` | 章节列表页 | |
| `/subject/{id}/comments?page=N` | 条目吐槽页（web 版） | |
| **`/ep/{episode_id}`** | **章节详情页（含吐槽箱，见 4.2）** | 待用 |
| `/update/user/say?ajax=1` | POST 发表吐槽（需网页 cookie/formhash） | ✅ |

### 4.2 章节页 `/ep/{id}` 吐槽箱结构（2026-10 实测）

```
<div id="comment_list" class="commentList comment-list borderNeue">
  <div id="post_{评论id}" class="… row row_reply clearit" data-item-user="{username}">   ← 主楼
    <div class="post_actions re_info">
      <div class="action"><small><a href="#post_…" class="floor-anchor">#1</a> - 2026-9-16 23:26</small></div>
    </div>
    <a href="/user/{username}" class="avatar">
      <span class="avatarNeue avatarReSize40 ll" style="background-image:url('//lain.bgm.tv/pic/user/l/….jpg')"></span>
    </a>
    <div class="inner">
      <strong><a href="/user/{username}" class="l">{昵称}</a></strong>
      <div class="reply_content">
        <div class="message clearit">评论正文（可能含 <img class="smile" alt="(bgm38)"> 表情）</div>
        <div class="likes_grid" id="likes_grid_{id}">…</div>
      </div>
    </div>
    <div id="post_…" class="sub_reply_bg clearit" …>…</div>   ← 楼中楼，嵌套在主楼内
  </div>
</div>
```

解析要点：
- 主楼匹配 `id="post_(\d+)"` 且 class 含 `row row_reply`；楼中楼 class 为 `sub_reply_bg`（如需可单独匹配，其嵌套在主楼 div 内部，注意正则贪婪匹配边界）。
- 头像：`background-image:url('…')`，取自 `span.avatarNeue` 的 style，`//lain.bgm.tv` 需补协议。
- 楼层/时间：`class="floor-anchor">#N</a> - YYYY-M-D HH:mm`。
- 正文：`<div class="message clearit">…</div>`，去 HTML 标签、解码实体；表情 `<img … alt="(bgm38)">` 用 alt 文本替代。
- 章节页 `<title>` 格式：`ep.{sort} {章节名} / {条目名}`，可用于获取章节名；`#headerSubject h1 a` 为条目名。
- 评论很多的章节页有标准 bgm.tv 分页 `?page=N`（`.page_inner` 导航）。

---

## 5. 枚举速查

| 枚举 | 值 |
|------|------|
| SubjectType 条目类型 | 1 书籍 / 2 动画 / 3 音乐 / 4 游戏 / 6 三次元 |
| 收藏类型 SubjectCollectionType | 1 想看 / 2 看过 / 3 在看 / 4 搁置 / 5 抛弃 |
| **章节收藏类型 EpisodeCollectionType** | **0 未看 / 1 想看 / 2 看过 / 3 抛弃** |
| 章节 Episode.type | 0 本篇 / 1 SP / 2 OP / 3 ED / 4 预告/广告 / 5 MAD / 6 其他 |
| legacy 章节状态 | `watched` 看过 / `queue` 想看 / `drop` 抛弃 |
| legacy 收藏 action | `create` / `update` / `watched` / `queue` / `drop` / `remove` |

## 6. 本应用（BGM Lite）当前调用清单

| 用途 | 端点 |
|------|------|
| 日历 | `GET /calendar` |
| 条目/章节/关联 | `GET /v0/subjects/{id}`、`GET /v0/episodes?subject_id=`、`GET /v0/subjects/{id}/related`（= `/subjects` 关联） |
| 搜索/热门/浏览 | `POST /v0/search/subjects`、`GET /p1/trending/subjects`、`GET /v0/subjects` |
| 收藏 | `POST/PATCH/DELETE /v0/users/-/collections/{id}`、`GET /v0/users/-/collections/{id}`、`GET /v0/users/{name}/collections` |
| 章节 watch | `PUT /v0/users/-/collections/-/episodes/{ep_id}`（type 2/0 切换） |
| 评论 | `GET /p1/subjects/{id}/comments` |
| 时间线/标签 | `GET /user/{name}/timeline`、`GET /{type}/tag`、`GET /{type}/tag/{tag}` |
