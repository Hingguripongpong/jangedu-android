package com.example.janggiai.ui.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.janggiai.JanggiApplication
import com.example.janggiai.data.GameRecord
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as JanggiApplication
    var records by remember { mutableStateOf<List<GameRecord>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { records = app.container.records.list() }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("기보") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } })
    }) { padding ->
        val list = records
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("불러오는 중…") }
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("저장된 기보가 없습니다.\n대국이 끝난 뒤 또는 분석 화면 메뉴에서 저장할 수 있습니다.", style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.id }) { rec ->
                    Column {
                        ListItem(
                            headlineContent = { Text(rec.title) },
                            supportingContent = { Text("${fmt.format(Date(rec.createdAt))} · ${rec.moves.size}수 · ${rec.result}") },
                            trailingContent = {
                                IconButton(onClick = { scope.launch { app.container.records.delete(rec.id); records = app.container.records.list() } }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "삭제")
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(rec.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
