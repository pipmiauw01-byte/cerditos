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
object Detector {
    private const val S = 2 // se analiza a media resolución: más rápido y suficiente

    fun detect(bmp: Bitmap): List<Pig> {
        val w0 = bmp.width
        val h0 = bmp.height
        val full = IntArray(w0 * h0)
        bmp.getPixels(full, 0, w0, 0, 0, w0, h0)
        return detect(full, w0, h0)
    }

    fun detect(full: IntArray, w0: Int, h0: Int): List<Pig> {
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
            }
        }

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
        if (cands.isEmpty()) return emptyList()
        // todos los cerditos son del mismo tamaño: se descarta lo que se aleje mucho de la mediana
        val med = cands.map { it.second }.sorted()[cands.size / 2]
        return cands.filter { it.second >= 0.5 * med && it.second <= 1.6 * med }.map { it.first }
    }

    private fun percentileAbs(v: DoubleArray, q: Double): Double {
        val a = DoubleArray(v.size) { abs(v[it]) }
        a.sort()
        return a[((a.size - 1) * q).toInt()]
    }
}
