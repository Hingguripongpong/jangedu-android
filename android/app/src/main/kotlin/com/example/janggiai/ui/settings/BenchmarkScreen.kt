package com.example.janggiai.ui.settings

import android.os.Debug
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.janggiai.JanggiApplication
import com.example.janggiai.benchmark.BenchmarkRow
import com.example.janggiai.benchmark.EngineBenchmark
import com.example.janggiai.ui.common.formatNodes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as JanggiApplication
    val settings by app.container.settings.settings.collectAsState()
    val rows = remember { mutableStateListOf<BenchmarkRow>() }
    var running by remember { mutableStateOf(false) }
    var memInfo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(title = { Text("엔진 벤치마크") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("시작 국면에서 분석 모드(MultiPV=합법수 개수)와 대국 모드(MultiPV=1)를 300ms / 1s / 3s 동안 실행합니다. 스레드 ${settings.threads}, 해시 ${settings.hashMb} MB.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(enabled = !running, onClick = {
                rows.clear(); error = null; running = true
                scope.launch {
                    try {
                        EngineBenchmark(app.container.engines.get()).run(settings.threads, settings.hashMb) { rows += it }
                        val rt = Runtime.getRuntime()
                        memInfo = "네이티브 힙 ${Debug.getNativeHeapAllocatedSize() / 1048576} MB · JVM ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB · 코어 ${rt.availableProcessors()}"
                    } catch (e: Exception) { error = e.message ?: e.toString() } finally { running = false }
                }
            }) { Text(if (running) "실행 중…" else "벤치마크 실행") }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            if (rows.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    Cell("모드", 2f); Cell("예산", 1f); Cell("깊이", 1f); Cell("노드", 1f); Cell("nps", 1f); Cell("실제(ms)", 1f)
                }
                HorizontalDivider()
                for (r in rows) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Cell(r.mode, 2f); Cell("${r.budgetMs}", 1f); Cell("${r.depth}", 1f); Cell(formatNodes(r.nodes), 1f); Cell(formatNodes(r.nps), 1f); Cell("${r.elapsedMs}", 1f)
                }
            }
            if (memInfo.isNotEmpty()) Text(memInfo, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            error?.let { Text("오류: $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float) {
    Text(text, modifier = Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}
