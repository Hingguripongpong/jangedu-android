package com.example.janggiai.ui.settings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.janggiai.JanggiApplication
import com.example.janggiai.data.ThemeMode
import com.example.janggiai.game.AnalysisStrength
import com.example.janggiai.game.BikjangRule
import com.example.janggiai.game.BoardCandidatesMode
import com.example.janggiai.game.CoachBudget
import com.example.janggiai.game.Difficulty
import com.example.janggiai.game.RepetitionRule
import com.example.janggiai.game.WinRatePerspective
import com.example.janggiai.ui.common.SelectableChips

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onBenchmark: () -> Unit, onAbout: () -> Unit) {
    val app = LocalContext.current.applicationContext as JanggiApplication
    val repo = app.container.settings
    val s by repo.settings.collectAsState()
    val cores = Runtime.getRuntime().availableProcessors()

    Scaffold(topBar = {
        TopAppBar(title = { Text("설정") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("분석")
            Label("분석 강도 (한 국면당 시간)")
            SelectableChips(AnalysisStrength.entries, s.analysisStrength, { "${it.label}${it.timeMs?.let { t -> " ${t / 1000.0}s" } ?: " ∞"}" }, { v -> repo.update { it.copy(analysisStrength = v) } })
            SwitchRow("고급 정보 표시 (깊이·노드·nps·PV)", s.advancedAnalysis) { v -> repo.update { it.copy(advancedAnalysis = v) } }
            SwitchRow("장기판 위에 승률 표시", s.showWinRateOnBoard) { v -> repo.update { it.copy(showWinRateOnBoard = v) } }
            Label("장기판에 표시할 후보수")
            SelectableChips(BoardCandidatesMode.entries, s.boardCandidates, { it.label }, { v -> repo.update { it.copy(boardCandidates = v) } })
            Text("분석 자체는 항상 모든 합법수(MultiPV = 합법수 개수)입니다. 이 옵션은 장기판에 그리는 표식만 줄입니다. 기물을 선택하면 그 기물의 후보만 표시됩니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Label("승률 기준")
            SelectableChips(WinRatePerspective.entries, s.perspective, { it.label }, { v -> repo.update { it.copy(perspective = v) } })

            Section("대국")
            SwitchRow("AI 훈수 (내 수를 최선수와 비교해 복기)", s.coachEnabled) { v -> repo.update { it.copy(coachEnabled = v) } }
            Label("훈수 분석 시간 (AI 대국 사고 시간과 별개)")
            SelectableChips(CoachBudget.entries, s.coachBudget, { "${it.label} ${it.timeMs} ms" }, { v -> repo.update { it.copy(coachBudget = v) } })
            Label("기본 난이도")
            SelectableChips(Difficulty.entries, s.difficulty, { it.label }, { v -> repo.update { it.copy(difficulty = v) } })
            Label("최강 난이도 사고 시간: ${s.maxThinkMs / 1000.0}초")
            Slider(value = (s.maxThinkMs / 500).toFloat(), onValueChange = { v -> repo.update { it.copy(maxThinkMs = (v.toLong() * 500).coerceAtLeast(500)) } }, valueRange = 1f..20f, steps = 18)

            Section("규칙")
            Label("빅장 (마주보는 궁)")
            SelectableChips(listOf(BikjangRule.OFF, BikjangRule.FORCED), s.bikjang, { if (it == BikjangRule.OFF) "없음 (온라인 장기)" else "전통 (점수 판정)" }, { v -> repo.update { it.copy(bikjang = v) } })
            Text(if (s.bikjang == BikjangRule.OFF) "카카오장기 등 현대 온라인 장기 방식. 엔진 변형: janggicasual" else "빅장을 받은 쪽은 풀거나 한수쉼만 가능하며 점수(차13 포7 마5 상3 사3 졸2, 한 +1.5)로 승패를 가립니다. 엔진 변형: janggi",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Label("동형 반복")
            SelectableChips(RepetitionRule.entries, s.repetition, { if (it == RepetitionRule.DRAW) "3회 반복 무승부" else "제한 없음" }, { v -> repo.update { it.copy(repetition = v) } })
            Text("규칙 변경은 새 대국/새 국면부터 적용됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section("엔진")
            Label("스레드: ${s.threads} (기기 코어 $cores)")
            Slider(value = s.threads.toFloat(), onValueChange = { v -> repo.update { it.copy(threads = v.toInt().coerceIn(1, cores.coerceAtLeast(1))) } }, valueRange = 1f..cores.coerceAtLeast(1).toFloat(), steps = (cores - 2).coerceAtLeast(0))
            Label("해시 메모리: ${s.hashMb} MB")
            SelectableChips(listOf(16, 32, 64, 128), s.hashMb, { "$it" }, { v -> repo.update { it.copy(hashMb = v) } })
            OutlinedButton(onClick = onBenchmark, modifier = Modifier.padding(top = 8.dp)) { Text("엔진 벤치마크") }

            Section("화면")
            Label("테마")
            SelectableChips(ThemeMode.entries, s.theme, { it.label }, { v -> repo.update { it.copy(theme = v) } })
            SwitchRow("장기판 뒤집기 (한이 아래)", s.flipBoard) { v -> repo.update { it.copy(flipBoard = v) } }

            Section("정보")
            OutlinedButton(onClick = onAbout) { Text("앱 정보 · 오픈소스 라이선스") }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable private fun Section(title: String) {
    Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); HorizontalDivider(Modifier.padding(vertical = 6.dp))
}
@Composable private fun Label(text: String) { Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
@Composable private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
