package com.bangumi.ywylite.data.model

import kotlin.math.abs

/**
 * 把网页时间线里「同一次操作」的多条动态合成一行。
 *
 * bgm 网页上一个动作往往落成相邻两条动态：完成最后一话的进度（完成了 X 12 of 12 话）
 * 与随之而来的收藏（看过 X ★9），或者一次勾选产生的多条进度（完成了 6 of 12 / 8 of 12 话）。
 * 逐条渲染时一个动作要占好几行，这里按「同一条目 + 同一时段 + 相邻」合并。
 *
 * 一次操作涉及多个条目的动态（看过 A、B、C 3 部番组）不参与合并：它本身表达的就是一批条目，
 * 并进任何单条目行都会丢掉别的条目。
 */
fun mergeTimeline(items: List<TimelineItem>): List<TimelineEntry> {
    val entries = mutableListOf<MutableList<TimelineItem>>()
    items.forEach { item ->
        val last = entries.lastOrNull()
        if (last != null && mergesWith(last, item)) {
            last.add(item)
        } else {
            entries.add(mutableListOf(item))
        }
    }
    return entries.map { group -> buildEntry(group) }
}

private fun mergesWith(group: MutableList<TimelineItem>, item: TimelineItem): Boolean {
    if (group.last().mergeKey() != item.mergeKey()) return false
    if (!sameBucket(group.last(), item)) return false
    // 两次收藏状态变更（先想看、后抛弃）是两件事，各自成行；
    // 只有「进度 + 收藏」这种一个动作在网页上拆成两条的才合并
    val collectStates = TimelineItem.COLLECT_STATES
    if (item.stateKind in collectStates && group.any { it.stateKind in collectStates }) return false
    return true
}

/** 合并身份：同一条目才算同一次操作 */
private fun TimelineItem.mergeKey(): String {
    if (batchSuffix.isNotEmpty()) return "batch:$id"
    if (subjectId > 0) return "subject:$subjectId"
    val name = subjectNameCn.ifEmpty { subjectName }.trim()
    return if (name.isNotEmpty()) "name:$name" else "own:$id"
}

/** 合并时段：网页表头同一天即可；没有表头时退回两条动态的绝对时间差（2 小时内） */
private fun sameBucket(a: TimelineItem, b: TimelineItem): Boolean {
    if (a.dateLabel.isNotEmpty() && b.dateLabel.isNotEmpty()) return a.dateLabel == b.dateLabel
    if (a.timestamp > 0 && b.timestamp > 0) return abs(a.timestamp - b.timestamp) <= 2 * 3600
    return false
}

private fun buildEntry(group: List<TimelineItem>): TimelineEntry {
    val primary = group.first()
    // 收藏状态优先于进度：一次操作里「在看 / 看过」这类收藏状态才是这一行要表达的状态
    val state = group.firstOrNull { it.stateKind in TimelineItem.COLLECT_STATES } ?: primary
    val named = group.firstOrNull { it.subjectNameCn.isNotEmpty() }
        ?: group.firstOrNull { it.subjectName.isNotEmpty() }
        ?: primary
    val nameCn = named.subjectNameCn.ifEmpty { named.subjectName }
    val image = group.firstOrNull { it.subjectImage.isNotEmpty() }?.subjectImage.orEmpty()

    return TimelineEntry(
        items = group,
        subjectId = group.firstOrNull { it.subjectId > 0 }?.subjectId ?: 0,
        subjectNameCn = nameCn,
        subjectName = named.subjectName.ifEmpty { nameCn },
        subjectImage = image,
        stateKind = state.stateKind,
        stateLabel = state.actionLabel.ifEmpty { "动态" },
        progressLabels = group.asReversed().mapNotNull { it.episodeInfo.ifEmpty { null } }.distinct(),
        userRating = group.maxOf { it.userRating },
        userComment = group.mapNotNull { it.userComment.ifEmpty { null } }.distinct().joinToString(" "),
        subjectScore = group.firstOrNull { it.subjectScore > 0 }?.subjectScore ?: 0.0,
        subjectRank = group.firstOrNull { it.subjectRank > 0 }?.subjectRank ?: 0,
        time = primary.time,
        dateLabel = group.firstOrNull { it.dateLabel.isNotEmpty() }?.dateLabel.orEmpty(),
        batchSuffix = primary.batchSuffix,
        isStatus = primary.isStatus,
        description = primary.description
    )
}
