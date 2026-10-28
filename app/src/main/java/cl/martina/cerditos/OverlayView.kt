package cl.martina.cerditos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Capa transparente sobre el juego: números con el orden, flechas y marcas de atasco. */
class OverlayView(context: Context) : View(context) {
    private var pigs: List<Pig> = emptyList()
    private var plan: Plan? = null
    private var onlyNext = false
    private val loc = IntArray(2)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun show(pigs: List<Pig>, plan: Plan?, onlyNext: Boolean = false) {
        this.pigs = pigs
        this.plan = plan
        this.onlyNext = onlyNext
        invalidate()
    }

    fun clear() = show(emptyList(), null)

    override fun onDraw(canvas: Canvas) {
        val p = plan ?: return
        // las coordenadas son de pantalla completa; compensamos por si la ventana no parte en (0,0)
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        val order = if (onlyNext) p.order.take(1) else p.order
        // del último al primero, para que el #1 quede encima
        for (k in order.indices.reversed()) {
            val pig = pigs[order[k]]
            val first = k == 0
            val color = if (first) Color.rgb(255, 109, 0) else Color.argb(220, 35, 35, 45)
            arrow(canvas, pig, if (first) Color.rgb(255, 109, 0) else Color.argb(200, 30, 90, 220))
            badge(canvas, pig, (k + 1).toString(), color, if (first) 0.48f else 0.36f)
        }
        for (i in p.stuck) {
            val pig = pigs[i]
            val isUfo = i == p.ufo
            stroke.color = if (isUfo) Color.rgb(255, 196, 0) else Color.rgb(220, 30, 30)
            stroke.strokeWidth = pig.width * if (isUfo) 0.12f else 0.08f
            canvas.drawCircle(pig.cx, pig.cy, pig.width * 0.45f, stroke)
            if (isUfo) badge(canvas, pig, "OVNI", Color.rgb(200, 140, 0), 0.5f, small = true)
            else {
                val r = pig.width * 0.2f
                canvas.drawLine(pig.cx - r, pig.cy - r, pig.cx + r, pig.cy + r, stroke)
                canvas.drawLine(pig.cx - r, pig.cy + r, pig.cx + r, pig.cy - r, stroke)
            }
        }
        canvas.restore()
    }

    private fun badge(c: Canvas, pig: Pig, label: String, color: Int, rel: Float, small: Boolean = false) {
        val r = pig.width * rel
        fill.color = color
        c.drawCircle(pig.cx, pig.cy, r, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = r * 0.12f
        c.drawCircle(pig.cx, pig.cy, r, stroke)
        text.textSize = r * if (small) 0.62f else if (label.length > 1) 0.95f else 1.15f
        c.drawText(label, pig.cx, pig.cy - (text.descent() + text.ascent()) / 2, text)
    }

    private fun arrow(c: Canvas, pig: Pig, color: Int) {
        val len = pig.width * 1.25f
        val sx = pig.cx + pig.dx * pig.width * 0.45f
        val sy = pig.cy + pig.dy * pig.width * 0.45f
        val ex = pig.cx + pig.dx * len
        val ey = pig.cy + pig.dy * len
        stroke.color = color
        stroke.strokeWidth = pig.width * 0.1f
        val head = pig.width * 0.3f
        val nx = -pig.dy
        val ny = pig.dx
        val path = Path().apply {
            moveTo(sx, sy); lineTo(ex, ey)
            moveTo(ex - pig.dx * head + nx * head * 0.7f, ey - pig.dy * head + ny * head * 0.7f)
            lineTo(ex, ey)
            lineTo(ex - pig.dx * head - nx * head * 0.7f, ey - pig.dy * head - ny * head * 0.7f)
        }
        c.drawPath(path, stroke)
    }
}
