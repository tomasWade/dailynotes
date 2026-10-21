package com.dailynotes.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下月")
            }
        }
        MonthCalendar(month = month, dates = dates, onPick = onPick)
    }
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
