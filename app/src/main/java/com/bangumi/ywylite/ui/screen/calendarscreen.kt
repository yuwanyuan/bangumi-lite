package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.CalendarDay
import com.bangumi.ywylite.data.model.SubjectSmall
import com.bangumi.ywylite.ui.component.*

data class CalendarUiState(
    val loading: Boolean = true,
    val data: List<CalendarDay> = emptyList(),
    val error: String? = null,
    val selectedDay: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onSubjectClick: (Int) -> Unit,
    onSearchClick: () -> Unit = {}
) {
    val app = App.INSTANCE
    var uiState by remember { mutableStateOf(CalendarUiState()) }

    LaunchedEffect(Unit) {
        uiState = uiState.copy(loading = true, error = null)
        try {
            val calendar = app.api.getCalendar()
            val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            val todayIndex = when (today) {
                java.util.Calendar.MONDAY -> 0
                java.util.Calendar.TUESDAY -> 1
                java.util.Calendar.WEDNESDAY -> 2
                java.util.Calendar.THURSDAY -> 3
                java.util.Calendar.FRIDAY -> 4
                java.util.Calendar.SATURDAY -> 5
                java.util.Calendar.SUNDAY -> 6
                else -> 0
            }
            uiState = uiState.copy(loading = false, data = calendar, selectedDay = todayIndex)
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("每日放送") },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when {
            uiState.loading -> LoadingView(Modifier.padding(padding))
            uiState.error != null -> ErrorView(
                message = uiState.error ?: "加载失败",
                onRetry = {
                    uiState = uiState.copy(loading = true, error = null)
                },
                modifier = Modifier.padding(padding)
            )
            else -> CalendarContent(
                uiState = uiState,
                onDaySelected = { index -> uiState = uiState.copy(selectedDay = index) },
                onSubjectClick = onSubjectClick,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun CalendarContent(
    uiState: CalendarUiState,
    onDaySelected: (Int) -> Unit,
    onSubjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(
            edgePadding = 16.dp,
            selectedTabIndex = uiState.selectedDay,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            uiState.data.forEachIndexed { index, day ->
                Tab(
                    selected = uiState.selectedDay == index,
                    onClick = { onDaySelected(index) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.weekday.cn, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                )
            }
        }

        val currentDay = uiState.data.getOrNull(uiState.selectedDay)

        if (currentDay != null) {
            if (currentDay.items.isEmpty()) {
                EmptyView("今日没有放送")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    currentDay.items.forEach { subject ->
                        CalendarItem(
                            subject = subject,
                            onClick = { onSubjectClick(subject.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarItem(
    subject: SubjectSmall,
    onClick: () -> Unit
) {
    SubjectListItem(subject = subject, onClick = onClick)
}
