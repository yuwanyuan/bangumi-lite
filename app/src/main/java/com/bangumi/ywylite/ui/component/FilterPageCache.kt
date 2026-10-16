package com.bangumi.ywylite.ui.component

import androidx.compose.runtime.mutableStateMapOf
import com.bangumi.ywylite.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** 一个筛选下的数据（首拉与预取共用） */
class FilterPage<T>(val data: List<T>, val total: Int)

/**
 * 列表页的「按筛选缓存 + 相邻筛选预取」。
 *
 * - 切回看过的筛选直接给缓存，不再发请求；下拉刷新/加载更多会把新数据写回缓存
 * - 落到某个筛选后就预取相邻筛选的第一页，横滑过去时数据已就绪，不再转圈
 * - 同一筛选的并发请求只发一次：首拉与预取撞车时共用同一个请求
 * - 账号切换（登录/登出）后整份作废——缓存里含该账号的收藏内容
 *
 * [entries] 是可观察的：翻页把相邻筛选的内容渲染在旁边，预取完成时那一页要自动填上，
 * 不能等下一次重组才显示。
 *
 * 请求跑在传入的 [scope]（屏幕的 composition scope）上：预取不会被某次首拉的取消带走，
 * 离开该屏幕时随之取消。
 */
class FilterPageCache<K : Any, T>(
    private val scope: CoroutineScope,
    private val load: suspend (K, Int) -> FilterPage<T>
) {
    class Entry<T>(val page: FilterPage<T>) {
        val offset: Int = page.data.size
        val hasMore: Boolean = page.data.size < page.total
    }

    private val entries = mutableStateMapOf<K, Entry<T>>()
    private val inFlight = mutableMapOf<K, Deferred<Entry<T>>>()
    private var generation = App.INSTANCE.api.sessionGeneration

    private fun ensureGeneration() {
        val current = App.INSTANCE.api.sessionGeneration
        if (current != generation) {
            generation = current
            entries.clear()
            inFlight.clear()
        }
    }

    fun peek(key: K): Entry<T>? {
        ensureGeneration()
        return entries[key]
    }

    fun store(key: K, entry: Entry<T>) {
        ensureGeneration()
        entries[key] = entry
    }

    fun store(key: K, page: FilterPage<T>) {
        store(key, Entry(page))
    }

    /** 第一页：命中缓存直接返回；同 key 并发只发一次请求 */
    suspend fun firstPage(key: K): Entry<T> {
        ensureGeneration()
        entries[key]?.let { return it }
        inFlight[key]?.let { return it.await() }
        val deferred = scope.async { Entry(load(key, 0)) }
        inFlight[key] = deferred
        return try {
            deferred.await().also { entries[key] = it }
        } finally {
            inFlight.remove(key)
        }
    }

    /** 后台预取：已有缓存或正在请求的跳过；失败静默，等真正滑过去时再按需拉 */
    fun prefetch(keys: List<K>) {
        ensureGeneration()
        keys.forEach { key ->
            if (entries.containsKey(key) || inFlight.containsKey(key)) return@forEach
            scope.launch {
                try {
                    firstPage(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }
}
