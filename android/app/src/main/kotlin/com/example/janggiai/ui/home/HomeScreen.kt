package com.example.janggiai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.janggiai.BuildConfig

@Composable
fun HomeScreen(onPlay: () -> Unit, onAnalyze: () -> Unit, onRecords: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Janggi AI", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("오프라인 장기 분석 엔진", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(40.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPlay, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("AI와 대국", style = MaterialTheme.typography.titleMedium) }
                Button(onClick = onAnalyze, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("분석하기", style = MaterialTheme.typography.titleMedium) }
                OutlinedButton(onClick = onRecords, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("기보 보기") }
                OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("설정") }
            }
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onAbout) { Text("정보 · 오픈소스 라이선스") }
        Text("v${BuildConfig.VERSION_NAME} · 모든 승률은 기기 안에서 실행되는 Fairy-Stockfish 평가에서 계산됩니다",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
