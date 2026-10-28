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

        val moves = if (onlyNext) p.moves.take(1) else p.moves
        // números de toque por cerdito (un cerdito puede tocarse más de una vez: "2·6")
        val steps = HashMap<Int, MutableList<Int>>()
        moves.forEachIndexed { k, m -> steps.getOrPut(m.pig) { ArrayList() }.add(k + 1) }
        val next = moves.firstOrNull()
        for ((pigIdx, nums) in steps) {
            if (pigIdx == next?.pig) continue
            val pig = pigs[pigIdx]
            val first = moves.first { it.pig == pigIdx }
            arrow(canvas, pig, Color.argb(190, 30, 90, 220), if (first.exits) null else first.dist)
            badge(canvas, pig, nums.joinToString("·"), Color.argb(220, 35, 35, 45), 0.36f, small = nums.size > 1)
        }
        if (next != null) { // el próximo toque, encima de todo y en naranjo
            val pig = pigs[next.pig]
            val orange = Color.rgb(255, 109, 0)
            arrow(canvas, pig, orange, if (next.exits) null else next.dist)
            val nums = steps[next.pig] ?: listOf(1)
            badge(canvas, pig, nums.joinToString("·"), orange, 0.48f, small = nums.size > 1)
        }
        if (onlyNext) { canvas.restore(); return }
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

    /** Flecha hacia donde corre. dist = null: sale (flecha larga); si no, avanza esa distancia. */
    private fun arrow(c: Canvas, pig: Pig, color: Int, dist: Float?) {
        val sx = pig.cx + pig.dx * pig.width * 0.45f
        val sy = pig.cy + pig.dy * pig.width * 0.45f
        val len = if (dist == null) pig.width * 1.4f else (pig.length / 2f + dist).coerceAtLeast(pig.width * 0.8f)
        if (dist != null) { // marca dónde se detiene
            stroke.color = color
            stroke.strokeWidth = pig.width * 0.06f
            val ex = pig.cx + pig.dx * len
            val ey = pig.cy + pig.dy * len
            val r = pig.width * 0.35f
            c.drawLine(ex - pig.dy * r, ey + pig.dx * r, ex + pig.dy * r, ey - pig.dx * r, stroke)
        }
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
