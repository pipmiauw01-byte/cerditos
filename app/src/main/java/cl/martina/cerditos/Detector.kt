package cl.martina.cerditos

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Un cerdito detectado en pantalla.
 * (cx, cy): centro en píxeles de pantalla.
 * (dx, dy): vector unitario hacia donde mira la cabeza (= hacia donde corre al tocarlo).
 * width, length: ancho y largo del cuerpo en píxeles.
 */
data class Pig(
    val cx: Float, val cy: Float,
    val dx: Float, val dy: Float,
    val width: Float, val length: Float
)

/**
 * Detección por color, pensada para el juego de cerditos rosados sobre pasto verde.
 *  1. Píxel "de cerdito" = rosado (R > G y B parecido a G) o contorno café oscuro.
 *     El pasto tiene G >> R; las monedas y el trofeo son dorados (B mucho menor que G).
 *  2. Se agrupan en manchas conectadas. Una mancha es cerdito solo si es mayormente rosada,
 *     alargada y tiene ojos cerca de un extremo (así se descartan monedas, frasco, trofeo, foto).
 *  3. El eje largo de cada mancha (PCA) da la orientación del cuerpo.
 *  4. Los ojos (puntos casi negros dentro del cuerpo) dicen en qué extremo está la cabeza.
 */
/**
 * Lo que se ve en pantalla.
 * obstructed: hay algo tapando el campo (ventana, cofre, aviso): no hay que tocar cerditos.
 * closeX/closeY: dónde está la X blanca para cerrar esa ventana (arriba a la derecha), si se ve.
 */
data class Scene(
    val pigs: List<Pig>,
    val obstructed: Boolean,
    val closeX: Float? = null,
    val closeY: Float? = null
)

object Detector {
    private const val S = 2 // se analiza a media resolución: más rápido y suficiente

    fun detect(bmp: Bitmap): Scene {
        val w0 = bmp.width
        val h0 = bmp.height
        val full = IntArray(w0 * h0)
        bmp.getPixels(full, 0, w0, 0, 0, w0, h0)
        return detect(full, w0, h0)
    }

    fun detect(full: IntArray, w0: Int, h0: Int): Scene {
        val w = w0 / S
        val h = h0 / S
        val n = w * h
        val px = IntArray(n)
        for (y in 0 until h) {
            val row = y * S * w0
            for (x in 0 until w) px[y * w + x] = full[row + x * S]
        }

        // 1. máscara (se ignoran la barra superior del juego y los botones de abajo)
        val isPig = BooleanArray(n)
        val isPink = BooleanArray(n)
        val top = (h * 0.10).toInt()
        val bot = (h * 0.88).toInt()
        var grassN = 0
        var foreignN = 0
        var pigColorN = 0
        for (y in top until bot) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val pink = r > g + 8 && g - b < 30
                isPink[i] = pink
                isPig[i] = pink || (g < 120 && r < 140 && b < 140)
                val grass = g > r + 10 && g > b + 25 && g > 140
                if (grass) grassN++ else if (isPig[i]) pigColorN++ else foreignN++
            }
        }
        val fieldN = maxOf(1, (bot - top) * w)
        val grassFrac = grassN.toFloat() / fieldN
        val foreignFrac = foreignN.toFloat() / fieldN

        // 2. componentes conectadas (relleno por pila, 4 vecinos)
        val visited = BooleanArray(n)
        val members = IntArray(n)
        var mCount = 0
        val stack = IntArray(n)
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        for (seed in 0 until n) {
            if (!isPig[seed] || visited[seed]) continue
            val start = mCount
            var sp = 0
            stack[sp++] = seed
            visited[seed] = true
            while (sp > 0) {
                val p = stack[--sp]
                members[mCount++] = p
                val x = p % w
                if (x + 1 < w) { val q = p + 1; if (isPig[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
                if (x > 0) { val q = p - 1; if (isPig[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
                if (p + w < n) { val q = p + w; if (isPig[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
                if (p - w >= 0) { val q = p - w; if (isPig[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
            }
            starts.add(start)
            ends.add(mCount)
        }

        val sizes = IntArray(starts.size) { ends[it] - starts[it] }
        val cands = ArrayList<Pair<Pig, Int>>()
        for (k in starts.indices) {
            val sz = sizes[k]
            if (sz < 0.0003 * n) continue
            var pinkCount = 0
            for (m in starts[k] until ends[k]) if (isPink[members[m]]) pinkCount++
            if (pinkCount < 0.55 * sz) continue // dorado, gris, foto: no es cerdito
            var sx = 0.0
            var sy = 0.0
            var minX = w
            var maxX = 0
            for (m in starts[k] until ends[k]) {
                val p = members[m]
                val x = p % w
                val y = p / w
                sx += x; sy += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
            }
            if (minX <= 0 || maxX >= w - 1) continue // pegado al borde: no es un cerdito
            val mx = sx / sz
            val my = sy / sz

            // 3. orientación por PCA
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (m in starts[k] until ends[k]) {
                val p = members[m]
                val ddx = p % w - mx
                val ddy = p / w - my
                sxx += ddx * ddx; syy += ddy * ddy; sxy += ddx * ddy
            }
            sxx /= sz; syy /= sz; sxy /= sz
            val th = 0.5 * atan2(2 * sxy, sxx - syy)
            val ax = cos(th)
            val ay = sin(th)
            val tr = sxx + syy
            val det = sxx * syy - sxy * sxy
            val disc = sqrt(max(tr * tr / 4 - det, 0.0))
            val l1 = tr / 2 + disc
            val l2 = max(tr / 2 - disc, 1e-6)
            val elong = sqrt(l1 / l2)
            if (elong < 1.4 || elong > 2.8) continue

            val proj = DoubleArray(sz)
            val lat = DoubleArray(sz)
            for ((idx, m) in (starts[k] until ends[k]).withIndex()) {
                val p = members[m]
                val ddx = p % w - mx
                val ddy = p / w - my
                proj[idx] = ddx * ax + ddy * ay
                lat[idx] = -ddx * ay + ddy * ax
            }
            val hw = percentileAbs(lat, 0.95)
            val hl = percentileAbs(proj, 0.98)

            // 4. ojos: píxeles casi negros, lejos del contorno y de la colita
            var eSum = 0.0
            var eN = 0
            for ((idx, m) in (starts[k] until ends[k]).withIndex()) {
                if (abs(lat[idx]) >= hw * 0.75 || abs(proj[idx]) >= hl * 0.92) continue
                val c = px[members[m]]
                val lum = ((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff)
                if (lum < 110) { eSum += proj[idx]; eN++ }
            }
            if (eN < 2) continue                         // sin ojos: no es cerdito
            val eyePos = eSum / eN
            if (abs(eyePos) < 0.3 * hl) continue         // "ojos" al centro: no es cerdito
            val s = if (eyePos < 0) -1.0 else 1.0

            cands.add(
                Pig(
                    cx = ((mx + 0.5) * S).toFloat(),
                    cy = ((my + 0.5) * S).toFloat(),
                    dx = (ax * s).toFloat(),
                    dy = (ay * s).toFloat(),
                    width = (2 * hw * S).toFloat(),
                    length = (2 * hl * S).toFloat()
                ) to sz
            )
        }
        // todos los cerditos son del mismo tamaño: se descarta lo que se aleje mucho de la mediana
        val med = if (cands.isEmpty()) 0 else cands.map { it.second }.sorted()[cands.size / 2]
        val kept = cands.filter { it.second >= 0.5 * med && it.second <= 1.6 * med }
        val explained = kept.sumOf { it.second }

        // ¿Hay algo encima del juego?
        //  - pantalla oscurecida (casi no se ve pasto verde claro)  → ventana / cofre
        //  - muchos píxeles que no son ni pasto ni cerdito          → cartel claro encima
        //  - mucha superficie "rosada/oscura" que no es un cerdito  → cartel rosado o franja oscura
        val unexplained = pigColorN - explained
        val obstructed = grassFrac < 0.35f ||
            foreignFrac > 0.06f ||
            (kept.isNotEmpty() && unexplained > 6 * med) ||
            (kept.isEmpty() && pigColorN > 0.02f * fieldN)

        var cx: Float? = null
        var cy: Float? = null
        if (obstructed) {
            findCloseButton(px, w, h)?.let { (x, y) -> cx = (x + 0.5f) * S; cy = (y + 0.5f) * S }
        }
        return Scene(if (obstructed) emptyList() else kept.map { it.first }, obstructed, cx, cy)
    }

    /**
     * Busca la X blanca de cerrar: una mancha blanca más o menos cuadrada, de tamaño de botón,
     * en la esquina superior derecha (bajo la barra de estado). Devuelve su centro (media resolución).
     */
    private fun findCloseButton(px: IntArray, w: Int, h: Int): Pair<Float, Float>? {
        val x0 = (w * 0.70).toInt()
        val y0 = (h * 0.04).toInt()
        val y1 = (h * 0.25).toInt()
        fun white(i: Int): Boolean {
            val c = px[i]
            return ((c shr 16) and 0xff) > 225 && ((c shr 8) and 0xff) > 225 && (c and 0xff) > 225
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray((y1 - y0 + 1) * (w - x0 + 1))
        var best: Pair<Float, Float>? = null
        var bestArea = 0
        for (y in y0 until y1) for (x in x0 until w) {
            val seed = y * w + x
            if (seen[seed] || !white(seed)) continue
            var sp = 0; stack[sp++] = seed; seen[seed] = true
            var area = 0; var sx = 0L; var sy = 0L
            var minX = x; var maxX = x; var minY = y; var maxY = y
            while (sp > 0) {
                val p = stack[--sp]
                val qx = p % w; val qy = p / w
                area++; sx += qx; sy += qy
                if (qx < minX) minX = qx; if (qx > maxX) maxX = qx
                if (qy < minY) minY = qy; if (qy > maxY) maxY = qy
                val nb = intArrayOf(p + 1, p - 1, p + w, p - w)
                for (q in nb) {
                    val nx = q % w; val ny = q / w
                    if (q < 0 || q >= w * h || nx < x0 || ny < y0 || ny >= y1) continue
                    if (abs(nx - qx) > 1) continue
                    if (!seen[q] && white(q)) { seen[q] = true; stack[sp++] = q }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val ratio = bw.toFloat() / bh
            val fill = area.toFloat() / (bw * bh)
            val sizeOk = bw >= w * 0.025 && bw <= w * 0.10
            if (sizeOk && ratio in 0.7f..1.4f && fill in 0.2f..0.8f && area > bestArea) {
                bestArea = area
                best = Pair(sx.toFloat() / area, sy.toFloat() / area)
            }
        }
        return best
    }

    private fun percentileAbs(v: DoubleArray, q: Double): Double {
        val a = DoubleArray(v.size) { abs(v[it]) }
        a.sort()
        return a[((a.size - 1) * q).toInt()]
    }
}
