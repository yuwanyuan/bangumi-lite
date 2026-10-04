package com.bangumi.ywylite.ui.component

import kotlin.math.roundToInt

/**
 * 站龄推算：Bangumi 用户 ID 为自增，按 ID 阈值对照注册年份即可离线估算，
 * 改过用户名的用户从头像地址中提取原始 ID。
 *
 * ID 阈值数据来自 czy0729/Bangumi 的长期实测（src/utils/app/ages.ts）。
 */
object UserAges {

    /** x.0: 01-01, x.1: 02-06, x.2: 03-13 ... 每档约 45 天 */
    private val splits = listOf(
        2 to 2008.0,
        1859 to 2009.0,
        7338 to 2010.0,
        14072 to 2011.0,
        65402 to 2012.0,
        114438 to 2013.0,
        179065 to 2014.0,
        226840 to 2015.0,
        273082 to 2016.0,
        315562 to 2017.0,
        391386 to 2018.0,
        451922 to 2019.0,
        517357 to 2020.0,
        568529 to 2021.0,
        656637 to 2022.0,
        753039 to 2023.0,
        846679 to 2024.0,
        947430 to 2025.0,
        1195000 to 2026.0,
        1210000 to 2026.1,
        1225000 to 2026.2,
        1238500 to 2026.3,
        1251000 to 2026.4,
        1260500 to 2026.5,
        1270000 to 2026.6,
        1276000 to 2026.7,
        1282000 to 2026.8,
        1288000 to 2026.9
    )

    /** 估算站龄（年，含小数）；无法推算时返回 null */
    fun estimate(userId: Int, avatar: String? = null): Double? {
        val intId = userId.takeIf { it > 0 }
            ?: avatar?.let { extractIdFromAvatar(it) }
            ?: return null
        // 超出对照表的新用户按最后档年份估算
        val regYear = splits.lastOrNull { intId <= it.first }?.second ?: splits.last().second
        return currentDecimalYear() - regYear
    }

    /** 站龄显示：满 1 年显示「N 年」，不足 1 年显示「N 个月」 */
    fun format(age: Double): String {
        return if (age >= 1) {
            "${age.roundToInt()} 年"
        } else {
            "${(age * 12).roundToInt().coerceAtLeast(1)} 个月"
        }
    }

    private fun currentDecimalYear(): Double {
        val now = java.util.Calendar.getInstance()
        val year = now.get(java.util.Calendar.YEAR)
        val dayOfYear = now.get(java.util.Calendar.DAY_OF_YEAR)
        val daysInYear = now.getActualMaximum(java.util.Calendar.DAY_OF_YEAR).toDouble()
        return year + (dayOfYear / daysInYear)
    }

    /** 头像地址形如 //lain.bgm.tv/pic/user/l/000/50/78/507806.jpg，末段数字为原始 ID */
    private fun extractIdFromAvatar(avatar: String): Int? {
        if (!avatar.contains("pic/user")) return null
        val segment = avatar.substringBefore(".jpg").substringBefore("_")
        return Regex("""(\d+)(?=[^\d]*$)""").find(segment)?.groupValues?.get(1)?.toIntOrNull()
    }
}
