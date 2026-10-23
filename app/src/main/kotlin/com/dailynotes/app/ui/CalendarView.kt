package com.dailynotes.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil

/** 简单月历：有日记的日期实心标记，今天描边；点任意日期回调（空白日期点击 = 补记） */
@Composable
fun MonthCalendar(
    month: YearMonth,
    dates: Set<LocalDate>,
    today: LocalDate = LocalDate.now(),
    onPick: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WeekHeader()
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value - 1   // 周一开头
        val cells = offset + month.lengthOfMonth()
        val rows = ceil(cells / 7.0).toInt()
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val day = r * 7 + c - offset + 1
                    if (day in 1..month.lengthOfMonth()) {
                        val date = month.atDay(day)
                        DayCell(
                            date = date,
                            hasDiary = date in dates,
                            isToday = date == today,
                            onClick = { onPick(date) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(Modifier.weight(1f).height(44.dp))
                    }
                }
            }
        }
    }
}

/** 月份导航外壳（月标题 + 左右切换 + 日历本体），月份状态由调用方持有 */
@Composable
fun CalendarPanel(
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    dates: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
    onTitleClick: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上月")
            }
            Text(
                "${month.year}年${month.monthValue}月",
                style = MaterialTheme.typography.titleMedium,
                modifier = if (onTitleClick != null) {
                    Modifier
                        .clickable(onClick = onTitleClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                } else Modifier,
            )
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下月")
            }
        }
        MonthCalendar(month = month, dates = dates, onPick = onPick)
    }
}

/** 年月快速跳转选择器（日历标题点击 / 列表「跳年」共用）：年份横滑条 + 月份宫格，
 *  点任意月份立即回调。年份倒序（最近在前）。 */
@Composable
fun YearMonthPickerDialog(
    initial: YearMonth,
    years: IntRange,
    onPick: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
) {
    var selYear by remember { mutableStateOf(initial.year.coerceIn(years.first, years.last)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到") },
        text = {
            Column {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    items((years.last downTo years.first).toList()) { y ->
                        FilterChip(
                            selected = y == selYear,
                            onClick = { selYear = y },
                            label = { Text("$y") },
                        )
                    }
                }
                (1..12).chunked(3).forEach { rowMonths ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    ) {
                        rowMonths.forEach { m ->
                            OutlinedButton(
                                onClick = { onPick(YearMonth.of(selYear, m)) },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                            ) { Text("${m}月") }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun WeekHeader() {
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
            Text(
                it,
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    hasDiary: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.height(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        val hasColor = MaterialTheme.colorScheme.primaryContainer
        val todayBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        Box(
            Modifier
                .size(34.dp)
                .then(if (hasDiary) Modifier.background(hasColor, CircleShape) else Modifier)
                .then(if (isToday) Modifier.border(todayBorder, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.dayOfMonth}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasDiary) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
