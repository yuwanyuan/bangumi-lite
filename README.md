<p align="center">
  <a href="https://github.com/yuwanyuan/bangumi-lite/actions/workflows/build.yml"><img src="https://github.com/yuwanyuan/bangumi-lite/actions/workflows/build.yml/badge.svg" alt="Build Status"></a>
  <a href="https://github.com/yuwanyuan/bangumi-lite/releases/latest"><img src="https://img.shields.io/github/v/release/yuwanyuan/bangumi-lite?color=blue&label=Release" alt="Release"></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-purple.svg" alt="Kotlin">
  <img src="https://img.shields.io/badge/Compose-Material3-blue.svg" alt="Compose">
  <img src="https://img.shields.io/badge/MinSDK-26-orange.svg" alt="MinSDK">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License"></a>
</p>

<h1 align="center">BGM Lite</h1>

<p align="center">
  <strong>轻量级 Bangumi 第三方 Android 客户端</strong><br>
  基于 Jetpack Compose 构建，Material 3 设计语言
</p>

---

## 📱 功能概览

### 浏览与发现
- **热门排行** — 按动画、书籍、游戏、音乐、三次元分类浏览热门条目
- **番组日历** — 按星期查看当季新番，快速了解每日放送
- **标签系统** — 按类型浏览标签云，按标签筛选条目，显示剧集数量
- **搜索** — 关键词搜索，支持按条目类型过滤

### 条目详情
- **条目信息** — 名称、译名、简介、评分、排名、标签、关联条目
- **高斯模糊背景** — 海报封面作为详情页模糊背景
- **章节管理** — 横向方块显示集数：单击展开集名，双击标记看过，长按弹出菜单（看过 / 看到第 N 话 / 当集评论）
- **自动折叠** — 章节超过两行默认折叠
- **用户评论** — 查看条目下的用户评论

### 收藏管理
- **五种收藏状态** — 想看 / 看过 / 在看 / 搁置 / 抛弃，各状态独立配色
- **评分评论** — 为收藏的条目打分、写评论
- **收藏筛选** — 按条目类型和收藏状态筛选

### 个人中心
- **用户时间线** — 查看个人动态，显示剧集观看信息（如 ep.13、12/26话）
- **时间线评分** — 10 星评分显示，用户评论框高亮
- **中文译名优先** — 时间线默认显示中文译名，原名在下方

### 系统功能
- **深色模式** — 跟随系统 / 手动浅色 / 手动深色，Android 12+ 动态取色
- **代理支持** — HTTP / SOCKS5 代理，支持认证
- **缓存管理** — 查看缓存大小，一键清理
- **内嵌浏览器** — 应用内打开 Bangumi 网页
- **Token 自动检测** — 登录状态自动验证，失效自动清除

---

## 🖼️ 应用截图

| 浏览 | 收藏 | 详情 |
|:---:|:---:|:---:|
| 浏览页 | 收藏页 | 条目详情页 |

| 时间线 | 标签 | 设置 |
|:---:|:---:|:---:|
| 个人时间线 | 标签云 | 设置页 |

---

## 🏗️ 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.0.21 |
| UI 框架 | Jetpack Compose + Material 3 | BOM 2024.12.01 |
| 导航 | Navigation Compose | 2.8.5 |
| 网络 | Ktor (OkHttp 引擎) | 2.3.7 |
| 序列化 | Kotlin Serialization | 1.7.3 |
| 图片加载 | Coil Compose | 2.5.0 |
| 数据持久化 | DataStore Preferences | 1.1.1 |
| 构建 | Gradle + AGP | 8.7.3 |
| 最低 SDK | Android 8.0 (API 26) | — |
| 目标 SDK | Android 15 (API 36) | — |

---

## 📂 项目结构

```
app/src/main/java/com/bangumi/ywylite/
├── App.kt                              # Application 类，初始化 API 和设置
├── data/
│   ├── api/
│   │   └── BangumiApi.kt               # API 客户端（REST + HTML 解析）
│   ├── model/
│   │   └── models.kt                   # 全部数据模型（30+ 类）
│   ├── Settings.kt                     # DataStore 设置持久化
│   └── CacheManager.kt                 # 缓存管理器
├── ui/
│   ├── BangumiApp.kt                   # 主导航框架 + 底部导航
│   ├── MainActivity.kt                 # 入口 Activity
│   ├── component/
│   │   └── Components.kt               # 可复用 UI 组件
│   ├── navigation/
│   │   └── Route.kt                    # 路由定义（13 个路由）
│   ├── screen/
│   │   ├── ExploreScreen.kt            # 浏览发现页
│   │   ├── CollectionScreen.kt         # 收藏管理页
│   │   ├── ProfileScreen.kt            # 个人主页 + 时间线
│   │   ├── CalendarScreen.kt           # 番组日历页
│   │   ├── SearchScreen.kt             # 搜索页
│   │   ├── SubjectDetailScreen.kt      # 条目详情页
│   │   ├── TagsScreen.kt              # 标签列表页
│   │   ├── TagBrowseScreen.kt          # 标签浏览页
│   │   ├── WebViewScreen.kt            # 内嵌浏览器页
│   │   ├── SettingsScreen.kt           # 设置总页
│   │   ├── AccountSettingsScreen.kt    # 账号设置页
│   │   ├── CacheSettingsScreen.kt      # 缓存设置页
│   │   └── ProxySettingsScreen.kt      # 代理设置页
│   └── theme/
│       └── Theme.kt                    # 主题（粉色系 + 深色模式 + 动态取色）
```

---

## 🔌 API 说明

BGM Lite 使用三种数据源：

### 1. Bangumi REST API (`api.bgm.tv` / `next.bgm.tv`)

| 方法 | 端点 | 认证 | 说明 |
|------|------|:----:|------|
| `getCalendar()` | `GET /calendar` | ❌ | 番组日历 |
| `searchSubjects()` | `POST /v0/search/subjects` | ❌ | 搜索条目 |
| `getSubject()` | `GET /v0/subjects/{id}` | ❌ | 条目详情 |
| `getEpisodes()` | `GET /v0/episodes` | ❌ | 章节列表 |
| `getMe()` | `GET /v0/me` | ✅ | 当前用户 |
| `getUserCollections()` | `GET /v0/users/{username}/collections` | ✅ | 用户收藏 |
| `collectSubject()` | `POST /v0/users/-/collections/{id}` | ✅ | 收藏条目 |
| `updateCollection()` | `PATCH /v0/users/-/collections/{id}` | ✅ | 更新收藏 |
| `getSubjectCollection()` | `GET /v0/users/-/collections/{id}` | ✅ | 收藏状态 |
| `getComments()` | `GET /v0/subjects/{id}/comments` | ✅ | 条目评论 |
| `getRelatedSubjects()` | `GET /v0/subjects/{id}/related` | ❌ | 关联条目 |
| `getEpisodeCollection()` | `GET /v0/users/-/episodes` | ✅ | 章节观看状态 |
| `updateEpisodeStatus()` | `PUT /v0/users/-/episodes` | ✅ | 更新观看状态 |
| `browseSubjects()` | `GET /v0/subjects` | ❌ | 按类型浏览 |
| `searchSubjectsByHeat()` | `GET /p1/trending/subjects` | ❌ | 热门条目 |

### 2. Bangumi Web 端 (`bgm.tv`)

通过 HTML 解析获取 REST API 未提供的数据：

| 方法 | 端点 | 说明 |
|------|------|------|
| `getTags()` | `/{typePath}/tag` | 标签列表 |
| `getTagEpisodeCount()` | `/{typePath}/tag/{tag}` | 标签下剧集数量估算 |
| `browseByTag()` | `/{typePath}/tag/{tag}` | 按标签浏览条目 |
| `getUserTimeline()` | `/user/{username}/timeline` | 用户时间线 |
| `postSubjectComment()` | `/subject/{id}/comment` | 发表评论 |

### 3. 代理支持

- 支持 HTTP 和 SOCKS5 代理
- 支持代理认证（用户名 + 密码）
- 运行时动态切换，自动重建 HTTP 客户端

---

## 📊 数据模型

### 条目相关
| 模型 | 说明 |
|------|------|
| `Subject` | 条目详情（名称、评分、章节、收藏统计等） |
| `SubjectSmall` | 条目摘要（列表/卡片展示用） |
| `Rating` | 评分（总分、人数、分布） |
| `TagInfo` | 标签（名称、使用次数、剧集数量） |
| `Episode` | 章节（名称、译名、集数、时长） |
| `RelatedSubject` | 关联条目 |

### 用户相关
| 模型 | 说明 |
|------|------|
| `User` | 用户信息 |
| `UserCollection` | 用户收藏（状态、评分、评论） |
| `EpisodeCollection` | 章节观看状态 |
| `TimelineItem` | 时间线条目（动态、评分、评论、剧集信息） |
| `CommentItem` | 评论 |

### 收藏状态
| 状态 | 值 | 颜色 |
|------|---|------|
| 想看 | 1 | 蓝色 #2196F3 |
| 看过 | 2 | 绿色 #4CAF50 |
| 在看 | 3 | 橙色 #FF9800 |
| 搁置 | 4 | 灰色 #9E9E9E |
| 抛弃 | 5 | 红色 #F44336 |

---

## 🚀 下载与构建

### 下载 APK

无需自行编译，直接前往 [GitHub Releases](https://github.com/yuwanyuan/bangumi-lite/releases/latest) 下载最新签名版 `app-release.apk`。开发版 APK 可在 [Actions](https://github.com/yuwanyuan/bangumi-lite/actions) 页面每次构建的 Artifacts 中获取。

### 环境要求

- Android Studio Ladybug | 2024.2.1+
- JDK 17
- Android SDK 36
- Gradle 8.9（项目自带 wrapper）

### 构建步骤

```bash
# 克隆仓库
git clone https://github.com/yuwanyuan/bangumi-lite.git
cd bangumi-lite

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK（启用混淆和资源压缩）
./gradlew assembleRelease

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 输出路径

```
app/build/outputs/apk/
├── debug/
│   └── app-debug.apk            # Debug 版本
└── release/
    └── app-release.apk          # Release 版本（CI 通过 Secrets 自动签名；
                                  #   本地未配置签名环境变量时为 unsigned）
```

---

## ⚙️ 设置项

| 设置 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| 深色模式 | 选择 | 跟随系统 | 跟随系统 / 浅色 / 深色 |
| 代理开关 | 开关 | 关闭 | 启用网络代理 |
| 代理类型 | 选择 | HTTP | HTTP / SOCKS5 |
| 代理地址 | 文本 | 空 | 代理服务器地址 |
| 代理端口 | 数字 | 7890 | 代理服务器端口 |
| 代理认证 | 文本 | 空 | 代理用户名和密码 |

---

## 🎨 主题设计

- **主色调**: 粉色系（Pink #E91E63），致敬 Bangumi 品牌色
- **Material 3**: 完整的 Material You 设计语言
- **动态取色**: Android 12+ 自动从壁纸提取配色
- **深色模式**: 三种模式可选，全局适配

---

## 📝 开发笔记

### HTML 解析策略

Bangumi 的部分数据（标签、时间线、标签浏览）没有提供 REST API，因此通过解析 `bgm.tv` 的 HTML 页面获取。关键解析逻辑：

- **标签列表**: 正则匹配 `<a href="/tag/...">标签名</a> <small>(数量)</small>`
- **标签浏览**: 正则匹配 `<li id="item_{id}">` 块，提取封面、名称、评分、话数
- **时间线**: 正则匹配 `id="tml_{id}"` 块，提取条目信息、评分、评论、剧集信息
- **剧集数量**: 通过首页条目的平均话数 × 总条目数估算

### Token 认证流程

1. 用户在设置页输入 Bangumi AccessToken
2. Token 存储在 DataStore 中
3. API 请求自动附加 `Authorization: Bearer {token}` 头
4. 收到 401 响应时自动清除 Token，通知 UI 更新

---

## 📄 许可证

MIT License

---

## 🙏 致谢

- [Bangumi](https://bgm.tv) — 番组计划，提供数据和 API
- [Jetpack Compose](https://developer.android.com/compose) — 现代 Android UI 框架
- [Ktor](https://ktor.io) — Kotlin HTTP 客户端
- [Coil](https://coil-kt.github.io/coil/) — Kotlin 图片加载库

---

<p align="center">
  Made with ❤️ for Bangumi users
</p>
