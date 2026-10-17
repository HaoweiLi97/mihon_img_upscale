package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.sin

internal class BookPageTurnDrawing {
    private val camera = Camera()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    fun draw(canvas: Canvas, width: Int, height: Int, turn: BookPageTurn, content: (Canvas) -> Unit) {
        val extent = if (turn.vertical) height.toFloat() else width.toFloat()
        val count = if (turn.spread) 2 else 1
        // Draw the fixed leaf first. The other spread supplies the turning leaf's reverse face.
        val leaves = (0 until count).sortedBy { turn.leaf(it).turning }
        for (index in leaves) {
            val leaf = turn.leaf(index)
            if (!leaf.visible) continue
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            turn.bounds?.let { bounds.set(it.left, it.top, it.right, it.bottom) }
            if (turn.spread) {
                val left = bounds.left
                val half = bounds.width() / 2f
                bounds.left = left + half * index
                bounds.right = left + half * (index + 1)
            }
            val hinge = if (leaf.hingeAtStart) {
                if (turn.vertical) bounds.top else bounds.left
            } else {
                if (turn.vertical) bounds.bottom else bounds.right
            }
            val save = canvas.save()
            val fraction = if (turn.current) turn.progress else 1f - turn.progress
            val scale = 1f + (turn.targetScale - 1f) * fraction
            if (turn.layoutTransition) {
                val shift = if (turn.current) turn.progress else turn.progress - 1f
                canvas.translate(turn.hingeShift * shift, turn.crossShift * shift)
                canvas.scale(scale, scale, hinge, bounds.centerY())
            }
            if (leaf.turning) {
                camera.save()
                camera.setLocation(0f, 0f, -maxOf(1600f, extent * 4f) / (72f * scale))
                if (turn.vertical) camera.rotateX(-leaf.degrees) else camera.rotateY(leaf.degrees)
                camera.getMatrix(matrix)
                camera.restore()
                val pivotX = if (turn.vertical) width / 2f else hinge
                val pivotY = if (turn.vertical) hinge else height / 2f
                matrix.preTranslate(-pivotX, -pivotY)
                matrix.postTranslate(pivotX, pivotY)
                canvas.concat(matrix)
            }
            canvas.clipRect(bounds)
            content(canvas)
            val shade = sin(Math.PI * turn.progress).toFloat().coerceAtLeast(0f)
            if (shade > 0f) {
                val shadowSize = extent * if (turn.spread) 0.09f else 0.18f
                val edge = hinge + if (leaf.hingeAtStart) shadowSize else -shadowSize
                val color = Color.argb((shade * if (leaf.turning) 90 else 45).toInt(), 0, 0, 0)
                paint.alpha = 255
                paint.shader = if (turn.vertical) {
                    LinearGradient(0f, hinge, 0f, edge, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(hinge, 0f, edge, 0f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                }
                canvas.drawRect(bounds, paint)
                paint.shader = null
                if (leaf.turning && abs(leaf.degrees) > 65f) {
                    paint.color = Color.argb((shade * 100).toInt(), 240, 235, 224)
                    paint.strokeWidth = 1.5f
                    if (turn.vertical) {
                        canvas.drawLine(bounds.left, hinge, bounds.right, hinge, paint)
                    } else {
                        canvas.drawLine(hinge, bounds.top, hinge, bounds.bottom, paint)
                    }
                }
            }
            canvas.restoreToCount(save)
        }
    }
}
