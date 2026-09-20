package com.example.janggiai.ui.board

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.example.janggiai.game.Board
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Piece
import com.example.janggiai.ui.theme.BoardPalette

/**
 * Draws one piece.  The default renders Hanja on a disc; drop-in replacement with bitmap/vector
 * assets is a matter of implementing this interface (e.g. an `ImagePieceRenderer` that loads
 * `assets/pieces/<cho|han>_<type>.png` into `ImageBitmap`s and draws them with `drawImage`).
 */
interface PieceRenderer {
    fun DrawScope.drawPiece(piece: Int, center: Offset, radius: Float, palette: BoardPalette)
}

class TextPieceRenderer(private val hanja: Boolean = true) : PieceRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }

    override fun DrawScope.drawPiece(piece: Int, center: Offset, radius: Float, palette: BoardPalette) {
        val cho = Piece.colorOf(piece) == Board.CHO
        val ink = if (cho) palette.cho else palette.han
        val isKing = Piece.typeOf(piece) == Piece.KING
        val r = if (isKing) radius * 1.08f else radius
        drawCircle(palette.pieceEdge, r + radius * 0.06f, center)
        drawCircle(palette.pieceFace, r, center)
        drawCircle(ink, r * 0.86f, center, style = Stroke(width = radius * 0.08f))
        val label = if (hanja) Notation.hanja(piece) else Notation.korean(piece)
        paint.color = ink.toArgb()
        paint.textSize = r * (if (label.length > 1) 0.9f else 1.15f)
        val fm = paint.fontMetrics
        val baseline = center.y - (fm.ascent + fm.descent) / 2f
        drawContext.canvas.nativeCanvas.drawText(label, center.x, baseline, paint)
    }
}
