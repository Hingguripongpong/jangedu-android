package com.example.janggiai.ui.board

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.example.janggiai.game.Board
import com.example.janggiai.game.BoardOrientation
import com.example.janggiai.game.Move
import com.example.janggiai.game.Notation
import com.example.janggiai.ui.theme.BoardPalette
import com.example.janggiai.ui.theme.LocalBoardPalette
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Board geometry shared by drawing and hit-testing: 9 files x 10 ranks, pieces sit on intersections.
 * All engine<->display square mapping goes through [BoardOrientation]; nothing else may flip coordinates.
 */
private class Layout(val width: Float, val flipped: Boolean) {
    val cell = width / Board.COLS
    val height = cell * Board.ROWS
    val margin = cell / 2f
    fun center(sq: Int): Offset {
        val (r, c) = BoardOrientation.displayRowCol(sq, flipped)
        return Offset(margin + c * cell, margin + r * cell)
    }
    fun squareAt(p: Offset): Int? {
        val c = ((p.x - margin) / cell).roundToInt(); val r = ((p.y - margin) / cell).roundToInt()
        val sq = BoardOrientation.squareAt(r, c, flipped) ?: return null
        val ctr = center(sq)
        if (hypot(p.x - ctr.x, p.y - ctr.y) > cell * 0.5f) return null
        return sq
    }
}

/**
 * The board.  Stateless: everything it shows comes from [board] and [overlay]; taps are reported
 * as square indices through [onSquareTap] and the caller decides what a tap means.
 */
@Composable
fun JanggiBoard(
    board: IntArray,
    overlay: BoardOverlay,
    flipped: Boolean,
    onSquareTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pieceRenderer: PieceRenderer = remember { TextPieceRenderer() },
    palette: BoardPalette = LocalBoardPalette.current,
    showCoordinates: Boolean = true,
) {
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) } }
    val coordPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(Board.COLS.toFloat() / Board.ROWS)
            .semantics { contentDescription = "장기판"; testTag = "janggi_board" }
            .pointerInput(flipped) {
                detectTapGestures { offset ->
                    val sq = Layout(size.width.toFloat(), flipped).squareAt(offset)
                    if (sq != null) onSquareTap(sq)
                }
            },
    ) {
        val lay = Layout(size.width, flipped)
        drawBoardBase(lay, palette, coordPaint, showCoordinates)
        drawLastMoveAndCheck(lay, overlay, palette)
        // pieces
        val radius = lay.cell * 0.42f
        for (sq in 0 until Board.NUM_SQUARES) {
            val p = board[sq]
            if (p != 0) with(pieceRenderer) { drawPiece(p, lay.center(sq), radius, palette) }
        }
        drawSelectionAndTargets(lay, overlay, board, palette)
        drawGhostMove(lay, overlay.ghostMove, palette)
        drawPvArrows(lay, overlay.pv, palette)
        drawCandidates(lay, overlay, palette, labelPaint)
    }
}

private fun DrawScope.drawBoardBase(lay: Layout, palette: BoardPalette, coordPaint: Paint, coords: Boolean) {
    drawRoundRect(palette.background, Offset.Zero, Size(lay.width, lay.height), CornerRadius(lay.cell * 0.15f))
    val stroke = lay.cell * 0.035f
    val x0 = lay.margin; val x1 = lay.margin + (Board.COLS - 1) * lay.cell
    val y0 = lay.margin; val y1 = lay.margin + (Board.ROWS - 1) * lay.cell
    for (c in 0 until Board.COLS) { val x = lay.margin + c * lay.cell; drawLine(palette.line, Offset(x, y0), Offset(x, y1), stroke) }
    for (r in 0 until Board.ROWS) { val y = lay.margin + r * lay.cell; drawLine(palette.line, Offset(x0, y), Offset(x1, y), stroke) }
    drawRect(palette.line, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(stroke * 1.8f))
    // palace diagonals (corner <-> centre): squares (0..2, 3..5) and (7..9, 3..5)
    for (top in listOf(0, 7)) {
        val a = lay.center(Board.sq(top, 3)); val b = lay.center(Board.sq(top + 2, 5))
        val c = lay.center(Board.sq(top, 5)); val d = lay.center(Board.sq(top + 2, 3))
        drawLine(palette.line, a, b, stroke); drawLine(palette.line, c, d, stroke)
    }
    if (coords) {
        coordPaint.color = palette.line.copy(alpha = 0.75f).toArgb()
        coordPaint.textSize = lay.cell * 0.22f
        for (c in 0 until Board.COLS) {
            val sq = Board.sq(Board.ROWS - 1, c)
            val ctr = lay.center(sq)
            drawContext.canvas.nativeCanvas.drawText("${c + 1}", ctr.x, lay.height - lay.cell * 0.06f, coordPaint)
        }
        for (r in 0 until Board.ROWS) {
            val ctr = lay.center(Board.sq(r, 0))
            drawContext.canvas.nativeCanvas.drawText("${(r + 1) % 10}", lay.cell * 0.12f, ctr.y + lay.cell * 0.08f, coordPaint)
        }
    }
}

private fun DrawScope.drawLastMoveAndCheck(lay: Layout, overlay: BoardOverlay, palette: BoardPalette) {
    val lm = overlay.lastMove
    if (lm != null && lm != Move.PASS) {
        val half = lay.cell * 0.48f
        for (sq in intArrayOf(Move.from(lm), Move.to(lm))) {
            val c = lay.center(sq)
            drawRoundRect(palette.lastMove, Offset(c.x - half, c.y - half), Size(half * 2, half * 2), CornerRadius(lay.cell * 0.12f))
        }
    }
    overlay.checkSquare?.let { sq ->
        val c = lay.center(sq)
        drawCircle(palette.check.copy(alpha = 0.35f), lay.cell * 0.55f, c)
        drawCircle(palette.check, lay.cell * 0.55f, c, style = Stroke(lay.cell * 0.05f))
    }
}

private fun DrawScope.drawSelectionAndTargets(lay: Layout, overlay: BoardOverlay, board: IntArray, palette: BoardPalette) {
    overlay.selected?.let { sq ->
        drawCircle(palette.selected, lay.cell * 0.5f, lay.center(sq), style = Stroke(lay.cell * 0.06f))
    }
    for (sq in overlay.legalTargets) {
        val c = lay.center(sq)
        if (board[sq] != 0) drawCircle(palette.selected, lay.cell * 0.5f, c, style = Stroke(lay.cell * 0.06f))   // capture ring
        else drawCircle(palette.legal, lay.cell * 0.13f, c)
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color, width: Float, headLen: Float) {
    val ang = atan2(to.y - from.y, to.x - from.x)
    val end = Offset(to.x - cos(ang) * headLen * 0.6f, to.y - sin(ang) * headLen * 0.6f)
    drawLine(color, from, end, width, cap = StrokeCap.Round)
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - cos(ang - 0.5f) * headLen, to.y - sin(ang - 0.5f) * headLen)
        lineTo(to.x - cos(ang + 0.5f) * headLen, to.y - sin(ang + 0.5f) * headLen)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawPvArrows(lay: Layout, pv: List<Int>, palette: BoardPalette) {
    val shown = pv.filter { it != Move.PASS }.take(6)
    shown.forEachIndexed { i, m ->
        val alpha = (1f - i * 0.14f).coerceAtLeast(0.3f)
        val from = lay.center(Move.from(m)); val to = lay.center(Move.to(m))
        drawArrow(from, to, palette.arrow.copy(alpha = palette.arrow.alpha * alpha), lay.cell * (if (i == 0) 0.11f else 0.07f), lay.cell * 0.35f)
    }
}

private fun DrawScope.drawCandidates(lay: Layout, overlay: BoardOverlay, palette: BoardPalette, paint: Paint) {
    if (overlay.candidates.isEmpty()) return
    val stackIndex = HashMap<Int, Int>()          // several pieces can move to the same square: stack their badges
    for (cand in overlay.candidates) {
        val m = cand.move
        if (m == Move.PASS) continue
        val color = when (cand.rank) { 1 -> palette.candidateBest; 2, 3 -> palette.candidateGood; else -> palette.candidateOther }
        val from = lay.center(Move.from(m)); val to = lay.center(Move.to(m))
        val emphasised = m in overlay.emphasised
        if (cand.rank == 1 && overlay.pv.isEmpty()) drawArrow(from, to, color.copy(alpha = 0.55f), lay.cell * 0.08f, lay.cell * 0.3f)
        val badgeH = if (emphasised) lay.cell * 0.36f else lay.cell * 0.28f
        val badgeW = if (emphasised) lay.cell * 0.9f else lay.cell * 0.42f
        val n = stackIndex.getOrDefault(Move.to(m), 0)
        stackIndex[Move.to(m)] = n + 1
        val top = to.y - lay.cell * 0.5f - badgeH * 0.15f + n * badgeH * 0.95f
        val left = to.x - badgeW / 2f
        drawRoundRect(color, Offset(left, top), Size(badgeW, badgeH), CornerRadius(badgeH / 2f))
        paint.color = palette.candidateText.toArgb()
        paint.textSize = badgeH * 0.62f
        val fm = paint.fontMetrics
        drawContext.canvas.nativeCanvas.drawText(cand.label, to.x, top + badgeH / 2f - (fm.ascent + fm.descent) / 2f, paint)
    }
}

/** The move actually played, drawn faintly with a dashed line and hollow rings (played-vs-best preview). */
private fun DrawScope.drawGhostMove(lay: Layout, move: Int?, palette: BoardPalette) {
    if (move == null || move == Move.PASS) return
    val from = lay.center(Move.from(move)); val to = lay.center(Move.to(move))
    val color = palette.candidateOther.copy(alpha = 0.9f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(lay.cell * 0.18f, lay.cell * 0.12f))
    drawLine(color, from, to, lay.cell * 0.07f, cap = StrokeCap.Round, pathEffect = dash)
    drawCircle(color, lay.cell * 0.46f, from, style = Stroke(lay.cell * 0.05f, pathEffect = dash))
    drawCircle(color, lay.cell * 0.46f, to, style = Stroke(lay.cell * 0.07f))
}

/** Accessible text for a move (used in content descriptions and lists). */
fun moveLabel(board: IntArray, move: Int): String = Notation.describe(board, move)
