package com.example.janggiai.ui.analysis

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.janggiai.JanggiApplication
import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.game.AnalysisCandidates
import com.example.janggiai.game.AnalysisStrength
import com.example.janggiai.game.CandidateMarker
import com.example.janggiai.game.Move
import com.example.janggiai.game.SetupChoice
import com.example.janggiai.game.WinRateFormat
import com.example.janggiai.game.WinRatePerspective
import com.example.janggiai.ui.board.BoardCandidate
import com.example.janggiai.ui.board.BoardOverlay
import com.example.janggiai.ui.board.JanggiBoard
import com.example.janggiai.ui.common.DropdownSelector
import com.example.janggiai.ui.common.MoveNavigation
import com.example.janggiai.ui.common.MoveStrip
import com.example.janggiai.ui.common.SelectableChips
import com.example.janggiai.ui.common.formatNodes
import com.example.janggiai.ui.common.statusText
import com.example.janggiai.ui.common.winRateColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(recordId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as JanggiApplication
    val vm: AnalysisViewModel = viewModel(factory = viewModelFactory {
        initializer { AnalysisViewModel(createSavedStateHandle(), app.container, recordId) }
    })
    val state by vm.state.collectAsState()
    val settings by app.container.settings.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var fenDialog by remember { mutableStateOf(false) }
    var newPositionDialog by remember(recordId) {
        mutableStateOf(recordId == null)
    }

    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    val session = state.session
    val pos = session.position
    val snap = state.currentSnapshot
    val perspective = settings.perspective
    val focused = state.focused

    val flipped = state.flipOverride ?: settings.flipBoard
    // Every legal move is in the model (a 31-move position yields 31 entries); the board may narrow to Top 5 / the selected piece.
    val allCandidates = if (snap != null) AnalysisCandidates.fromSnapshot(snap, perspective) else emptyList()
    val boardCandidates = if (settings.showWinRateOnBoard && snap != null)
        AnalysisCandidates.forBoard(allCandidates, settings.boardCandidates, state.selected, focused?.move) else emptyList()
    val emphasised = AnalysisCandidates.emphasised(boardCandidates, state.selected)
    val candidates = boardCandidates.map { c -> BoardCandidate(c.move, c.rank, c.label(c.move in emphasised), c.winRate) }

    val overlay = BoardOverlay(
        selected = state.selected,
        legalTargets = state.legalTargets,
        lastMove = session.lastMove,
        checkSquare = if (session.status.inCheck) pos.kingSq[pos.side] else null,
        candidates = candidates,
        pv = focused?.pv ?: emptyList(),
        emphasised = emphasised,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("분석") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
                actions = {
                    TextButton(onClick = { vm.toggleFlip(settings.flipBoard) }, modifier = Modifier.testTag("flip_button")) { Text(if (flipped) "한 시점" else "초 시점") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "메뉴") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("새 국면") },
                            onClick = {
                                menuOpen = false
                                newPositionDialog = true
                            }
                        )
                        DropdownMenuItem(text = { Text("FEN 불러오기") }, onClick = { menuOpen = false; fenDialog = true })
                        DropdownMenuItem(text = { Text("기보 저장") }, onClick = { menuOpen = false; vm.saveRecord() })
                        DropdownMenuItem(text = { Text("다시 분석 (캐시 무시)") }, onClick = { menuOpen = false; vm.reanalyze() })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // One lazy list for the whole screen: the board and controls are header items, the candidates are
        // individual items, so 20–40 moves scroll like any list (no nested same-direction scrolling).
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp).testTag("analysis_list")) {
            item(key = "status") {
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(statusText(session), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    EvalBadge(snap, perspective, settings.advancedAnalysis || app.container.winModel.calibrated)
                }
            }
            item(key = "board") {
                Column {
                    JanggiBoard(board = pos.board, overlay = overlay, flipped = flipped, onSquareTap = vm::onSquareTap)
                    if (state.analysis.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    else Spacer(Modifier.height(8.dp))
                }
            }
            item(key = "controls") {
                // testTag only: this item sits below the board and may be off screen on phones, so tests scroll to it first.
                Column(Modifier.testTag("analysis_controls")) {
                    AnalysisHeader(state, snap, settings.advancedAnalysis, vm::toggleAnalysis)
                    MoveStrip(moveTexts = session.moveTexts(), viewIndex = session.viewIndex, onSelect = vm::goTo, modifier = Modifier.padding(vertical = 4.dp),
                        labels = state.recordCoach.mapValues { it.value.grade.label })
                    state.recordCoach[session.viewIndex - 1]?.let { c ->
                        Text("훈수 (${c.ply + 1}수): ${c.grade.label} · ${c.headline} · 최선수 ${c.bestNotation ?: "—"} ${WinRateFormat.percent(c.bestWinRate)} vs 내 수 ${WinRateFormat.percent(c.playedWinRate)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MoveNavigation(canBack = session.viewIndex > 0, canForward = !session.atTip, onFirst = vm::first, onPrevious = vm::previous, onNext = vm::next, onLast = vm::last)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::undo, enabled = session.canUndo, modifier = Modifier.weight(1f)) { Text("무르기") }
                        OutlinedButton(onClick = vm::pass, enabled = session.legalMoves.contains(Move.PASS), modifier = Modifier.weight(1f)) { Text("한수쉼") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("분석 강도", style = MaterialTheme.typography.labelLarge)
                    SelectableChips(AnalysisStrength.entries, state.strength, { it.label }, vm::setStrength, Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (snap != null) {
                candidateItems(
                    snapshot = snap, candidates = allCandidates, focusedMove = state.focusedMove, advanced = settings.advancedAnalysis,
                    showAll = state.showAllCandidates, onToggleShowAll = { vm.setShowAllCandidates(!state.showAllCandidates) },
                    onFocus = vm::focusCandidate, onPlay = vm::playMove,
                )
            } else item(key = "no_analysis") {
                if (session.status.isOver) Text("대국이 끝난 국면입니다.", style = MaterialTheme.typography.bodyMedium)
                else if (!state.autoAnalyze) Text("분석이 꺼져 있습니다. ▶ 버튼으로 다시 시작하세요.", style = MaterialTheme.typography.bodyMedium)
            }
            item(key = "footer") {
                Column {
                    state.analysis.error?.let { Text("엔진 오류: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (newPositionDialog) {
        var choSetup by remember { mutableStateOf(SetupChoice.MSSM) }
        var hanSetup by remember { mutableStateOf(SetupChoice.MSSM) }

        AlertDialog(
            onDismissRequest = { newPositionDialog = false },
            title = { Text("새 국면") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownSelector(
                        label = "초 배치",
                        options = SetupChoice.entries,
                        selected = choSetup,
                        text = { it.label },
                        onSelect = { choSetup = it },
                    )

                    DropdownSelector(
                        label = "한 배치",
                        options = SetupChoice.entries,
                        selected = hanSetup,
                        text = { it.label },
                        onSelect = { hanSetup = it },
                    )

                    Text(
                        "배치 이름은 자기 진영의 왼쪽에서 오른쪽 순서입니다. 랜덤은 네 가지 표준 배치 중 하나로 정해집니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.newGame(choSetup, hanSetup)
                        newPositionDialog = false
                    }
                ) {
                    Text("시작")
                }
            },
            dismissButton = {
                TextButton(onClick = { newPositionDialog = false }) {
                    Text("취소")
                }
            },
        )
    }


    if (fenDialog) {
        var text by remember { mutableStateOf(pos.toFen()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { fenDialog = false },
            title = { Text("FEN 불러오기") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Fairy-Stockfish janggi FEN") })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { val e = vm.loadFen(text); if (e == null) fenDialog = false else error = e }) { Text("불러오기") } },
            dismissButton = { TextButton(onClick = { fenDialog = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun EvalBadge(snap: AnalysisSnapshot?, perspective: WinRatePerspective, showScore: Boolean) {
    val best = snap?.best ?: return
    val (wr, cp) = WinRateFormat.fromPerspective(best.winRate, best.scoreCp, snap.sideToMove, perspective)
    val who = when (perspective) { WinRatePerspective.SIDE_TO_MOVE -> if (snap.sideToMove == 0) "초" else "한"; WinRatePerspective.CHO -> "초"; WinRatePerspective.HAN -> "한" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$who 승률 ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(WinRateFormat.percent(wr), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = winRateColor(wr))
        if (showScore) Text("  ${WinRateFormat.score(cp, best.mateIn?.let { if (wr != best.winRate) -it else it })}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AnalysisHeader(state: AnalysisScreenState, snap: AnalysisSnapshot?, advanced: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val running = state.analysis.running
            val title = when {
                snap == null && running -> "분석 준비 중…"
                snap == null -> ""
                running -> "분석 중 · 깊이 ${snap.depth}${if (snap.maxDepth > snap.depth) "/${snap.maxDepth}" else ""} · ${snap.moves.count { it.scored }}/${snap.legalMoveCount}수"
                else -> "분석 완료 · 깊이 ${snap.depth} · ${snap.legalMoveCount}수 · ${snap.timeMs} ms"
            }
            // Tests read analysis progress here. NOTE: this lives in the "controls" lazy item below the board, so it is only
            // composed once that item is (scrolled) into view — tests must scroll to analysis_controls before polling it.
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("analysis_status"))
            if (advanced && snap != null) {
                Text("${snap.engineName} · MultiPV ${snap.multiPv} · ${formatNodes(snap.nodes)} nodes · ${formatNodes(snap.nps)} nps · ${snap.timeMs} ms",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onToggle) { Text(if (state.autoAnalyze) "■ 정지" else "▶ 분석") }
    }
}

/**
 * Candidate header + one item per move.  Never truncates the model: with [showAll] (default) every legal move
 * of the position is an item; the toggle only switches to the top [AnalysisCandidates.TOP_N].
 */
fun LazyListScope.candidateItems(
    snapshot: AnalysisSnapshot, candidates: List<CandidateMarker>, focusedMove: Int?, advanced: Boolean,
    showAll: Boolean, onToggleShowAll: () -> Unit, onFocus: (Int) -> Unit, onPlay: (Int) -> Unit,
) {
    val scoredCount = candidates.count { it.scored }
    val shown = AnalysisCandidates.forList(candidates, showAll)
    item(key = "candidate_header") {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("후보수 (${scoredCount}/${snapshot.legalMoveCount})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggleShowAll, modifier = Modifier.testTag("candidate_toggle")) { Text(if (showAll) "Top ${AnalysisCandidates.TOP_N}만" else "전체 (${candidates.size}수)") }
            }
            HorizontalDivider()
        }
    }
    items(shown, key = { "candidate_${it.move}" }) { c ->
        CandidateRow(c, snapshot, c.move == focusedMove, advanced, onFocus, onPlay)
    }
    val focusedAnalysis = focusedMove?.let { AnalysisCandidates.analysisOf(snapshot, it) }
    if (focusedMove != null && focusedAnalysis != null) {
        item(key = "candidate_focus") {
            Column {
                Spacer(Modifier.height(6.dp))
                Text("예상 진행: " + focusedAnalysis.pvText.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { onPlay(focusedMove) }) { Text("이 수 두기") }
                    TextButton(onClick = { onFocus(focusedMove) }) { Text("선택 해제") }
                }
            }
        }
    }
}

/** Standalone lazy candidate list (same items as the analysis screen); used by the UI test and available for reuse. */
@Composable
fun CandidateList(
    snapshot: AnalysisSnapshot, candidates: List<CandidateMarker>, focusedMove: Int?, advanced: Boolean,
    showAll: Boolean, onToggleShowAll: () -> Unit, onFocus: (Int) -> Unit, onPlay: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth().testTag("candidate_list")) {
        candidateItems(snapshot, candidates, focusedMove, advanced, showAll, onToggleShowAll, onFocus, onPlay)
    }
}

@Composable
private fun CandidateRow(c: CandidateMarker, snapshot: AnalysisSnapshot, focused: Boolean, advanced: Boolean, onFocus: (Int) -> Unit, onPlay: (Int) -> Unit) {
    val ma = AnalysisCandidates.analysisOf(snapshot, c.move)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { onFocus(c.move) }.padding(horizontal = 6.dp, vertical = 8.dp).testTag("candidate_${c.rank}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${c.rank}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(c.notation + (if (ma?.givesCheck == true) " 장군" else ""), style = MaterialTheme.typography.bodyLarge, fontWeight = if (c.rank == 1) FontWeight.Bold else FontWeight.Normal)
            Text(
                if (c.scored) "${WinRateFormat.score(c.scoreCp, c.mateIn)} · D${c.depth}" + (if (advanced && ma != null) "/${ma.seldepth}${ma.bound?.let { " · $it" } ?: ""}" else "") else "분석 대기",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (c.scored) Text(WinRateFormat.percent(c.winRate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = winRateColor(c.winRate))
        else Text("분석 중", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = { onPlay(c.move) }) { Text("두기") }
    }
}
