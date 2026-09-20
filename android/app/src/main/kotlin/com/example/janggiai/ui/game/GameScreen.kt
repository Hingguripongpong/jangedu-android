package com.example.janggiai.ui.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.janggiai.JanggiApplication
import com.example.janggiai.game.Difficulty
import com.example.janggiai.game.GameConfig
import com.example.janggiai.game.Move
import com.example.janggiai.game.Notation
import com.example.janggiai.game.SetupChoice
import com.example.janggiai.game.SideChoice
import com.example.janggiai.game.WinRateFormat
import com.example.janggiai.ui.board.BoardOverlay
import com.example.janggiai.ui.board.JanggiBoard
import com.example.janggiai.ui.common.DropdownSelector
import com.example.janggiai.ui.common.MoveStrip
import com.example.janggiai.ui.common.SelectableChips
import com.example.janggiai.ui.common.formatNodes
import com.example.janggiai.ui.common.statusText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(mode: GameMode, humanSide: Int, configure: Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as JanggiApplication
    val vm: GameViewModel = viewModel(factory = viewModelFactory {
        initializer { GameViewModel(createSavedStateHandle(), app.container, mode, humanSide, configure) }
    })
    val state by vm.state.collectAsState()
    val settings by app.container.settings.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var recordExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }

    val session = state.session
    val pos = session.position
    // "최선수 보기": the board temporarily shows the position BEFORE the reviewed move; game state is untouched.
    val preview = state.previewComment
    val shownPosition = if (preview != null) session.goTo(preview.ply).position else pos
    val overlay = if (preview != null) BoardOverlay(ghostMove = preview.playedMove, pv = preview.pv.take(3))
    else BoardOverlay(
        selected = state.selected,
        legalTargets = state.legalTargets,
        lastMove = session.lastMove,
        checkSquare = if (session.status.inCheck) pos.kingSq[pos.side] else null,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.mode.label) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
                actions = {
                    TextButton(onClick = vm::toggleFlip, modifier = Modifier.testTag("flip_button")) { Text("판 뒤집기") }
                    IconButton(onClick = vm::openNewGame) { Icon(Icons.Filled.Refresh, contentDescription = "새 대국") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            // 1. status + board
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(statusText(session), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.mode == GameMode.HUMAN_VS_AI) Text("나: ${Notation.sideKorean(state.humanSide)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.thinking) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    val info = state.thinkingInfo
                    Text(if (info != null) "AI 생각 중 · d${info.depth} · ${formatNodes(info.nodes)}n" else "AI 생각 중…",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            JanggiBoard(board = shownPosition.board, overlay = overlay, flipped = state.flipped, onSquareTap = vm::onSquareTap)
            if (preview != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${preview.ply + 1}수 직전 국면 · 점선 = 내 수, 화살표 = AI 최선수", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::closePreview) { Text("닫기") }
                }
            } else Spacer(Modifier.height(8.dp))

            // 2. essential controls
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::undo, enabled = session.canUndo && (state.humanTurn || state.mode == GameMode.AI_VS_AI), modifier = Modifier.weight(1f)) { Text("무르기") }
                if (state.mode == GameMode.HUMAN_VS_AI)
                    OutlinedButton(onClick = vm::pass, enabled = state.humanTurn && session.legalMoves.contains(Move.PASS), modifier = Modifier.weight(1f)) { Text("한수쉼") }
                else
                    OutlinedButton(onClick = vm::togglePause, modifier = Modifier.weight(1f)) { Text(if (state.paused) "재생" else "일시정지") }
            }
            Spacer(Modifier.height(10.dp))

            // 3. coach
            if (state.coachActive) {
                val shown = state.coachComment
                val latestHuman = state.lastHumanPly
                CoachCard(
                    comment = shown,
                    analyzing = state.coach.analyzingPly != null,
                    failed = state.coach.failedPly != null && state.coach.failedPly == latestHuman,
                    hasHumanMove = latestHuman != null,
                    previewing = preview != null,
                    onTogglePreview = vm::togglePreview,
                    onRetry = vm::retryCoach,
                )
                Spacer(Modifier.height(10.dp))
            }

            // 4. record / details (collapsible)
            Row(Modifier.fillMaxWidth().clickable { recordExpanded = !recordExpanded }.testTag("game_record"), verticalAlignment = Alignment.CenterVertically) {
                Text("대국 기록", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (recordExpanded) "▲" else "▼", style = MaterialTheme.typography.titleMedium)
            }
            if (recordExpanded) {
                val labels = if (state.coachActive) state.coach.comments.mapValues { it.value.grade.label } else emptyMap()
                MoveStrip(moveTexts = session.moveTexts(), viewIndex = session.viewIndex, onSelect = { vm.viewCoach(it - 1) }, labels = labels, modifier = Modifier.padding(vertical = 6.dp))
                val last = state.lastEngineMove
                if (last?.move != null && session.lastMove == last.move) {
                    val wr = last.winRate?.let { if (last.aborted) null else it }
                    Text(
                        "AI: ${last.notation} · 예상 승률 ${WinRateFormat.percent(wr)} (AI 기준) · ${WinRateFormat.score(last.scoreCp, last.mateIn)} · d${last.depth} · ${last.timeMs} ms",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.mode == GameMode.HUMAN_VS_AI) {
                    Text("내 진영 ${Notation.sideKorean(state.humanSide)} · 내 배치 ${state.humanSetup} · AI 배치 ${state.aiSetup} · 훈수 ${if (state.coachEnabled) "켬" else "끔"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("난이도", style = MaterialTheme.typography.labelLarge)
                SelectableChips(Difficulty.entries, state.difficulty, { it.label }, vm::setDifficulty, Modifier.padding(top = 4.dp))
                if (state.difficulty == Difficulty.MAXIMUM)
                    Text("최강: MultiPV=1, 스킬 20, 사고 시간 ${settings.maxThinkMs / 1000.0}초 (설정에서 변경)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (state.showGameOver) {
        AlertDialog(
            onDismissRequest = vm::dismissGameOver,
            title = { Text("대국 종료") },
            text = { Text(vm.resultText()) },
            confirmButton = { TextButton(onClick = { vm.dismissGameOver(); vm.openNewGame() }) { Text("새 대국") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { scope.launch { val t = vm.saveRecord(); snackbar.showSnackbar("저장됨: $t") }; vm.dismissGameOver() }) { Text("기보 저장") }
                    TextButton(onClick = vm::dismissGameOver) { Text("닫기") }
                }
            },
        )
    }

    if (state.showNewGame) {
        NewGameDialog(
            initialMode = state.mode, initial = vm.currentConfig(),
            onStart = { m, cfg -> vm.startNewGame(m, cfg) },
            onDismiss = vm::dismissNewGame,
        )
    }
}

/**
 * "새 대국": side (초/한/랜덤), each side's horse/elephant setup (4 standard + 랜덤), difficulty, coach on/off.
 * Choices are independent of each other; RANDOM is resolved once when the game starts.
 */
@Composable
fun NewGameDialog(initialMode: GameMode, initial: GameConfig, onStart: (GameMode, GameConfig) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(initialMode) }
    var side by remember { mutableStateOf(initial.humanSide) }
    var humanSetup by remember { mutableStateOf(initial.humanSetup) }
    var aiSetup by remember { mutableStateOf(initial.aiSetup) }
    var difficulty by remember { mutableStateOf(initial.difficulty) }
    var coach by remember { mutableStateOf(initial.coachEnabled) }
    val hva = mode == GameMode.HUMAN_VS_AI
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 대국") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("모드", style = MaterialTheme.typography.labelLarge)
                SelectableChips(GameMode.entries, mode, { it.label }, { mode = it })
                if (hva) {
                    Text("내 진영", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for (c in SideChoice.entries) {
                            RadioButton(selected = side == c, onClick = { side = c }, modifier = Modifier.testTag("side_${c.id}"))
                            Text(c.label)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                DropdownSelector(if (hva) "내 배치" else "초 배치", SetupChoice.entries, humanSetup, { it.label }, { humanSetup = it }, Modifier.testTag("human_setup"))
                DropdownSelector(if (hva) "AI 배치" else "한 배치", SetupChoice.entries, aiSetup, { it.label }, { aiSetup = it }, Modifier.testTag("ai_setup"))
                Text("AI 난이도", style = MaterialTheme.typography.labelLarge)
                SelectableChips(Difficulty.entries, difficulty, { it.label }, { difficulty = it })
                if (hva) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 훈수 (내 수를 복기)", modifier = Modifier.weight(1f))
                    Switch(checked = coach, onCheckedChange = { coach = it }, modifier = Modifier.testTag("coach_switch"))
                }
                Text("배치 이름은 자기 진영의 왼쪽에서 오른쪽 순서입니다. 랜덤은 네 가지 표준 배치 중 하나로 정해집니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(mode, GameConfig(side, humanSetup, aiSetup, difficulty, coach)) }, modifier = Modifier.testTag("start_game")) { Text("대국 시작") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Short label of the side a human plays, used by the home → game navigation. */
fun sideLabel(side: Int): String = Notation.sideKorean(side)
