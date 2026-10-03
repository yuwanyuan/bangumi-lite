# 章节交互改造设计（2026-10-04）

## 目标

条目详情页「章节」区的剧集方块交互改造：单击展开集名、双击标记看过、长按弹出操作菜单（看过 / 看到第 N 话 / 当集评论）。

## 交互定义

| 手势 | 行为 |
|------|------|
| 单击 | 展开/收起该集名字（保留现状） |
| 双击 | 切换看过/未看（toggle，与原「再次单击标记」语义一致） |
| 长按 | 底部弹层菜单：**看过/取消看过**（依当前状态）、**看到第 N 话**（批量）、**当集评论（N）** |

Compose 实现用 `combinedClickable`（单击会延迟一个双击判定窗口，属预期代价）。

## 数据与接口

- **看过（单集）**：沿用 `PUT /v0/users/-/collections/-/episodes/{id}`，type 2/0。
- **看到第 N 话**：legacy 接口 `POST /subject/{id}/update/watched_eps`（form: `watched_eps=N`，Bearer token），一次请求把第 1..N 话标为看过；N 取该集在正篇（type==0）中按 sort 排序的序号（处理多季度番 sort 不从 1 开始的问题，同 czy0729 方案）。失败时回退逐集调 v0 接口。
- **当集评论**：解析 `bgm.tv/ep/{id}` 吐槽箱 HTML（结构实测记录见 [bangumi-api.md](../bangumi-api.md) 4.2），只取主楼（`row row_reply`），展示楼层号、昵称、时间、正文（表情转 alt 文本、`<br>` 转换行）。
- **当集评论展示**：底部弹层（ModalBottomSheet），加载中/失败重试/空态均处理。

## 防呆

标记看过/看到前校验：未登录 → Snackbar「登录后才能标记进度」；条目未收藏 → Snackbar「收藏条目后才能标记进度」（v0 章节状态接口要求条目先收藏，原实现静默失败）。「看到」完成后重拉章节状态刷新整组方块，并 Snackbar 确认。

## 涉及文件

- `data/api/BangumiApi.kt`：新增 `markWatchedUpTo()`、`getEpisodeComments()` + HTML 解析。
- `data/model/models.kt`：新增 `EpisodeComment`。
- `ui/screen/subjectdetailscreen.kt`：`EpisodeBlockSection` 手势与弹层、Snackbar 提示、Screen 层回调。
