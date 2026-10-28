package com.deniscerri.ytdl.util

import android.content.Context
import android.icu.text.CompactDecimalFormat
import android.icu.text.RelativeDateTimeFormatter
import com.deniscerri.ytdl.R
import java.util.Locale

/** YTindir: "1.2M views" and "3 years ago" for the search results, in the app's language. */
object ResultMetaFormatter {

    private fun locale(): Locale = LocaleUtil.currentLocale()

    fun views(context: Context, count: Long): String {
        val number = runCatching {
            CompactDecimalFormat.getInstance(locale(), CompactDecimalFormat.CompactStyle.SHORT)
                .apply { maximumFractionDigits = 1 }
                .format(count)
        }.getOrElse { count.toString() }
        return context.resources.getQuantityString(R.plurals.result_views, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), number)
    }

    fun age(epochSeconds: Long): String? {
        val seconds = System.currentTimeMillis() / 1000 - epochSeconds
        if (seconds < 0) return null
        val formatter = runCatching { RelativeDateTimeFormatter.getInstance(locale()) }.getOrNull() ?: return null
        val (amount, unit) = when {
            seconds < 3600 -> (seconds / 60).coerceAtLeast(1) to RelativeDateTimeFormatter.RelativeUnit.MINUTES
            seconds < 86_400 -> seconds / 3600 to RelativeDateTimeFormatter.RelativeUnit.HOURS
            seconds < 7 * 86_400 -> seconds / 86_400 to RelativeDateTimeFormatter.RelativeUnit.DAYS
            seconds < 30 * 86_400 -> seconds / (7 * 86_400) to RelativeDateTimeFormatter.RelativeUnit.WEEKS
            seconds < 365 * 86_400 -> seconds / (30 * 86_400) to RelativeDateTimeFormatter.RelativeUnit.MONTHS
            else -> seconds / (365 * 86_400) to RelativeDateTimeFormatter.RelativeUnit.YEARS
        }
        return runCatching {
            formatter.format(amount.toDouble(), RelativeDateTimeFormatter.Direction.LAST, unit)
        }.getOrNull()
    }
}
