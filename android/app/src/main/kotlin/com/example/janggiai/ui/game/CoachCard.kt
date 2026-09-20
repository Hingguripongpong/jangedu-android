package com.example.janggiai.ui.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.janggiai.coach.CoachComment
import com.example.janggiai.coach.CoachGrade
import com.example.janggiai.game.WinRateFormat

fun gradeColor(grade: CoachGrade): Color = when (grade) {
    CoachGrade.BEST, CoachGrade.EXCELLENT -> Color(0xFF2E7D32)
    CoachGrade.GOOD -> Color(0xFF558B2F)
    CoachGrade.OK -> Color(0xFF6D8B74)
    CoachGrade.INACCURACY -> Color(0xFFEF6C00)
    CoachGrade.MISTAKE -> Color(0xFFD84315)
    CoachGrade.BLUNDER -> Color(0xFFC62828)
}

/**
 * "AI 훈수": the coach's review of the human's last move (or of the move picked in the record).
 * Pure presentation — every number comes from [CoachComment], which was computed from the engine's
 * full-width analysis of the pre-move position.
 */
@Composable
fun CoachCard(
    comment: CoachComment?,
    analyzing: Boolean,
    failed: Boolean,
    hasHumanMove: Boolean,
    previewing: Boolean,
    onTogglePreview: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().testTag("coach_card")) {
        Column(Modifier.padding(12.dp)) {
            Text("AI 훈수", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            when {
                analyzing && comment == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("분석 중…", style = MaterialTheme.typography.bodyMedium)
                }
                comment != null -> CoachBody(comment, analyzing, previewing, onTogglePreview)
                failed -> {
                    Text("이 수의 분석 결과를 얻지 못했습니다 (탐색이 중단되었거나 시간이 부족했습니다).", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text("다시 분석") }
                }
                !hasHumanMove -> Text("첫 수를 두면 AI가 그 수를 복기해 줍니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("AI 응답 후 방금 둔 수를 분석합니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CoachBody(c: CoachComment, analyzingNext: Boolean, previewing: Boolean, onTogglePreview: () -> Unit) {
    val loss = c.winRateLoss * 100
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.grade.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = gradeColor(c.grade), modifier = Modifier.testTag("coach_grade"))
        Spacer(Modifier.width(10.dp))
        Text("${c.ply + 1}수 · ${c.headline}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (analyzingNext) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp) }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("내 수", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.playedNotation, style = MaterialTheme.typography.bodyLarge)
            Text("이 수 승률 ${WinRateFormat.percent(c.playedWinRate)} · ${WinRateFormat.score(c.playedScoreCp, c.playedMateIn)} · D${c.depth}", style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.weight(1f)) {
            Text("AI 최선수", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.bestNotation ?: "—", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text("승률 ${WinRateFormat.percent(c.bestWinRate)} · ${WinRateFormat.score(c.bestScoreCp, c.bestMateIn)}", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(
        if (c.playedIsBest) "차이: 없음 (최선수)" else "차이: -${String.format(java.util.Locale.US, "%.1f", loss)}%p",
        style = MaterialTheme.typography.titleSmall, color = gradeColor(c.grade), modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(c.summary, style = MaterialTheme.typography.bodyMedium)
    for (r in c.reasons) Text("• $r", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    if (c.pvText.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text("예상 진행: " + c.pvText.take(6).joinToString(" → "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (c.bestMove != null && !c.playedIsBest) {
        TextButton(onClick = onTogglePreview, modifier = Modifier.testTag("coach_preview_button")) { Text(if (previewing) "미리보기 닫기" else "최선수 보기") }
    }
}
