package com.example.janggiai.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.janggiai.game.GameEnd
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.Notation

/** A horizontal single-choice chip row (stable M3 API; used instead of the experimental segmented buttons). */
@Composable
fun <T> SelectableChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) {
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }, enabled = enabled)
        }
    }
}

@Composable
fun MoveNavigation(canBack: Boolean, canForward: Boolean, onFirst: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onLast: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onFirst, enabled = canBack) { Text("⏮ 처음") }
        TextButton(onClick = onPrevious, enabled = canBack) { Text("◀ 이전") }
        TextButton(onClick = onNext, enabled = canForward) { Text("다음 ▶") }
        TextButton(onClick = onLast, enabled = canForward) { Text("끝 ⏭") }
    }
}

/**
 * Scrollable move list; the displayed ply is highlighted and tapping reports the 1-based ply.
 * [labels] adds a short annotation per 0-based move index (e.g. the coach grade of a human move).
 */
@Composable
fun MoveStrip(moveTexts: List<String>, viewIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, labels: Map<Int, String> = emptyMap()) {
    val listState = rememberLazyListState()
    LaunchedEffect(viewIndex, moveTexts.size) { if (viewIndex > 0) listState.animateScrollToItem((viewIndex - 1).coerceAtLeast(0)) }
    LazyRow(state = listState, modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(moveTexts) { i, text ->
            val active = i == viewIndex - 1
            val label = labels[i]?.let { " · $it" } ?: ""
            Text(
                text = "${i + 1}. $text$label",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(i + 1) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** A labelled "[value ▼]" button that opens a menu of [options]. */
@Composable
fun <T> DropdownSelector(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text("$label: ${text(selected)} ▼") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false })
        }
    }
}

/** "초 차례 · 장군!" / "외통 — 한 승" etc. */
fun statusText(session: GameSession): String {
    val st = session.status
    val side = Notation.sideKorean(session.position.side)
    val winner = st.winner?.let { Notation.sideKorean(it) }
    return when (st.end) {
        GameEnd.ONGOING -> buildString {
            append("$side 차례")
            if (st.inCheck) append(" · 장군!")
            else if (st.bikjang) append(" · 빅장")
            if (session.position.passes == 1) append(" · 상대 한수쉼")
        }
        GameEnd.CHECKMATE -> "외통 — $winner 승"
        GameEnd.BIKJANG_POINTS -> "빅장 — 점수로 $winner 승"
        GameEnd.PASSES_POINTS -> "연속 한수쉼 — 점수로 $winner 승"
        GameEnd.DRAW_BIKJANG -> "빅장 — 무승부"
        GameEnd.DRAW_PASSES -> "연속 한수쉼 — 무승부"
        GameEnd.DRAW_REPETITION -> "동형 반복 — 무승부"
        GameEnd.DRAW_MOVE_LIMIT -> "수 제한 — 무승부"
    }
}

fun winRateColor(p: Double?): Color = when {
    p == null -> Color.Gray
    p >= 0.65 -> Color(0xFF2E7D32)
    p >= 0.55 -> Color(0xFF558B2F)
    p >= 0.45 -> Color(0xFF8D6E63)
    p >= 0.35 -> Color(0xFFEF6C00)
    else -> Color(0xFFC62828)
}

fun formatNodes(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1fG", n / 1e9)
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.0fk", n / 1e3)
    else -> n.toString()
}
