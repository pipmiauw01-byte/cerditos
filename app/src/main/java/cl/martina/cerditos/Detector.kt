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

/**
 * Detección por color, pensada para el juego de cerditos rosados sobre pasto verde.
 *  1. Se marca el **interior rosado** de los cerditos (R > G y B parecido a G). El contorno café
 *     queda fuera, así que dos cerditos que se tocan siguen siendo dos manchas separadas.
 *     El pasto tiene G >> R; las monedas y el trofeo son dorados (B mucho menor que G).
 *  2. Cada mancha es un cerdito solo si es alargada y tiene ojos cerca de un extremo
 *     (así se descartan monedas, frasco, trofeo, foto).
 *  3. Si una mancha es demasiado grande o no parece cerdito (dos que se rozan con las patitas),
 *     se "adelgaza" hasta que se separa en núcleos y cada núcleo recupera sus píxeles.
 *  4. El eje largo (PCA) da la orientación; los ojos dicen en qué extremo está la cabeza.
 */
object Detector {
    private const val S = 2              // se analiza a media resolución: más rápido y suficiente
    private const val OUTLINE_FIX = 1.04f // el interior es un poco más chico que el dibujo completo

    fun detect(bmp: Bitmap): Scene {
        val w0 = bmp.width
        val h0 = bmp.height
        val full = IntArray(w0 * h0)
        bmp.getPixels(full, 0, w0, 0, 0, w0, h0)
        return detect(full, w0, h0)
    }

    private class Img(val px: IntArray, val w: Int, val h: Int) {
        fun r(i: Int) = (px[i] shr 16) and 0xff
        fun g(i: Int) = (px[i] shr 8) and 0xff
        fun b(i: Int) = px[i] and 0xff
        fun lum(i: Int) = r(i) + g(i) + b(i)
    }

    private class Cand(val pig: Pig, val area: Int)

    fun detect(full: IntArray, w0: Int, h0: Int): Scene {
        val w = w0 / S
        val h = h0 / S
        val n = w * h
        val px = IntArray(n)
        for (y in 0 until h) {
            val row = y * S * w0
            for (x in 0 until w) px[y * w + x] = full[row + x * S]
        }
        val img = Img(px, w, h)

        // 1. máscara rosada (se ignoran la barra superior del juego y los botones de abajo)
        val pink = BooleanArray(n)
        val top = (h * 0.10).toInt()
        val bot = (h * 0.88).toInt()
        var grassN = 0
        var pinkN = 0
        var darkN = 0
        var foreignN = 0
        for (y in top until bot) {
            for (x in 0 until w) {
                val i = y * w + x
                val r = img.r(i); val g = img.g(i); val b = img.b(i)
                val grass = g > r + 10 && g > b + 25 && g > 140
                val pk = !grass && r > g + 8 && g - b < 30
                pink[i] = pk
                when {
                    grass -> grassN++
                    pk -> pinkN++
                    g < 120 && r < 140 && b < 140 -> darkN++
                    else -> foreignN++
                }
            }
        }
        val fieldN = maxOf(1, (bot - top) * w)

        // 2. manchas rosadas conectadas
        val comps = components(pink, w, h, minSize = (0.0002 * n).toInt())

        // 3. primera pasada: manchas que ya parecen un cerdito → tamaño típico
        val firstPass = comps.mapNotNull { features(it, img) }
        var med = median(firstPass.map { it.area })

        val cands = ArrayList<Cand>()
        for (c in comps) {
            val f = features(c, img)
            val tooBig = if (f == null) (med == 0 || c.size > 1.6 * med) else (med > 0 && f.area > 1.6 * med)
            if (tooBig) {
                for (part in split(c, w, if (med > 0) med else c.size / 2)) features(part, img)?.let { cands.add(it) }
            } else if (f != null) cands.add(f)
        }
        // todos los cerditos son del mismo tamaño: se descarta lo que se aleje mucho de la mediana
        med = median(cands.map { it.area })
        val kept = cands.filter { it.area >= 0.5 * med && it.area <= 1.6 * med }
        val explained = kept.sumOf { it.area }

        // 4. ¿Hay algo encima del juego?
        val grassFrac = grassN.toFloat() / fieldN
        val darkFrac = darkN.toFloat() / fieldN
        val obstructed = grassFrac < 0.35f ||                       // pantalla oscurecida (cofre, ventana)
            foreignN.toFloat() / fieldN > 0.06f ||                   // cartel claro o de otro color
            darkFrac > 0.05f ||                                      // franja oscura / anuncio
            (kept.isNotEmpty() && pinkN - explained > 4 * med) ||    // cartel rosado que no es cerdito
            (kept.isEmpty() && pinkN > 0.02f * fieldN)

        var cx: Float? = null
        var cy: Float? = null
        if (obstructed) {
            findCloseButton(img)?.let { (x, y) -> cx = (x + 0.5f) * S; cy = (y + 0.5f) * S }
        }
        return Scene(if (obstructed) emptyList() else kept.map { it.pig }, obstructed, cx, cy)
    }

    private fun median(v: List<Int>): Int = if (v.isEmpty()) 0 else v.sorted()[v.size / 2]

    /** Componentes conectadas (4 vecinos) de la máscara; cada una como arreglo de índices. */
    private fun components(mask: BooleanArray, w: Int, h: Int, minSize: Int): List<IntArray> {
        val n = w * h
        val visited = BooleanArray(n)
        val stack = IntArray(n)
        val buf = IntArray(n)
        val out = ArrayList<IntArray>()
        for (seed in 0 until n) {
            if (!mask[seed] || visited[seed]) continue
            var sp = 0
            var cnt = 0
            stack[sp++] = seed
            visited[seed] = true
            while (sp > 0) {
                val p = stack[--sp]
                buf[cnt++] = p
                val x = p % w
                if (x + 1 < w) { val q = p + 1; if (mask[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
                if (x > 0) { val q = p - 1; if (mask[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
                if (p + w < n) { val q = p + w; if (mask[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
                if (p - w >= 0) { val q = p - w; if (mask[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
            }
            if (cnt >= minSize) out.add(buf.copyOf(cnt))
        }
        return out
    }

    /** Si la mancha parece un cerdito (alargada, con ojos cerca de un extremo), lo devuelve. */
    private fun features(pix: IntArray, img: Img): Cand? {
        val w = img.w
        val sz = pix.size
        var sx = 0.0; var sy = 0.0
        var minX = w; var maxX = 0; var minY = img.h; var maxY = 0
        for (p in pix) {
            val x = p % w; val y = p / w
            sx += x; sy += y
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (minX <= 1 || maxX >= w - 2) return null // cortado por el borde
        val mx = sx / sz
        val my = sy / sz
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pix) {
            val ddx = p % w - mx; val ddy = p / w - my
            sxx += ddx * ddx; syy += ddy * ddy; sxy += ddx * ddy
        }
        sxx /= sz; syy /= sz; sxy /= sz
        val th = 0.5 * atan2(2 * sxy, sxx - syy)
        val ax = cos(th)
        val ay = sin(th)
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(tr * tr / 4 - det, 0.0))
        val elong = sqrt((tr / 2 + disc) / max(tr / 2 - disc, 1e-6))
        if (elong < 1.4 || elong > 2.8) return null

        val proj = DoubleArray(sz)
        val lat = DoubleArray(sz)
        for ((k, p) in pix.withIndex()) {
            val ddx = p % w - mx; val ddy = p / w - my
            proj[k] = ddx * ax + ddy * ay
            lat[k] = -ddx * ay + ddy * ax
        }
        val hw = percentileAbs(lat, 0.95)
        val hl = percentileAbs(proj, 0.98)

        // ojos: píxeles casi negros dentro del óvalo del cuerpo (son "agujeros" en el interior rosado)
        var eSum = 0.0
        var eN = 0
        for (y in minY..maxY) for (x in minX..maxX) {
            val i = y * w + x
            if (img.lum(i) >= 110) continue
            val ddx = x - mx; val ddy = y - my
            val pr = ddx * ax + ddy * ay
            val la = -ddx * ay + ddy * ax
            if (abs(la) < hw * 0.8 && abs(pr) < hl * 0.95) { eSum += pr; eN++ }
        }
        if (eN < 2) return null                  // sin ojos: no es cerdito
        val eyePos = eSum / eN
        if (abs(eyePos) < 0.3 * hl) return null  // "ojos" al centro: no es cerdito
        val s = if (eyePos < 0) -1.0 else 1.0
        return Cand(
            Pig(
                cx = ((mx + 0.5) * S).toFloat(),
                cy = ((my + 0.5) * S).toFloat(),
                dx = (ax * s).toFloat(),
                dy = (ay * s).toFloat(),
                width = (2 * hw * S).toFloat() * OUTLINE_FIX,
                length = (2 * hl * S).toFloat() * OUTLINE_FIX
            ),
            sz
        )
    }

    /**
     * Separa una mancha con varios cerditos pegados: la va adelgazando (erosión) hasta que se parte
     * en núcleos y luego reparte cada píxel original al núcleo más cercano (crecimiento simultáneo).
     */
    private fun split(pix: IntArray, w: Int, med: Int): List<IntArray> {
        var minX = Int.MAX_VALUE; var maxX = 0; var minY = Int.MAX_VALUE; var maxY = 0
        for (p in pix) {
            val x = p % w; val y = p / w
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val bw = maxX - minX + 3
        val bh = maxY - minY + 3
        val orig = BooleanArray(bw * bh)
        for (p in pix) orig[(p / w - minY + 1) * bw + (p % w - minX + 1)] = true
        var cur = orig.copyOf()
        val minCore = maxOf(4, (0.12 * med).toInt())
        repeat(8) {
            val next = BooleanArray(bw * bh)
            for (y in 1 until bh - 1) for (x in 1 until bw - 1) {
                val i = y * bw + x
                next[i] = cur[i] && cur[i - 1] && cur[i + 1] && cur[i - bw] && cur[i + bw]
            }
            cur = next
            val cores = components(cur, bw, bh, minCore)
            if (cores.size >= 2) {
                val owner = IntArray(bw * bh) { -1 }
                val queue = IntArray(bw * bh)
                var head = 0; var tail = 0
                for ((k, core) in cores.withIndex()) for (i in core) { owner[i] = k; queue[tail++] = i }
                while (head < tail) {
                    val i = queue[head++]
                    val x = i % bw
                    val nb = intArrayOf(if (x + 1 < bw) i + 1 else -1, if (x > 0) i - 1 else -1, i + bw, i - bw)
                    for (q in nb) {
                        if (q < 0 || q >= bw * bh || !orig[q] || owner[q] >= 0) continue
                        owner[q] = owner[i]; queue[tail++] = q
                    }
                }
                val parts = Array(cores.size) { ArrayList<Int>() }
                for (i in 0 until bw * bh) if (owner[i] >= 0) {
                    val x = i % bw - 1 + minX
                    val y = i / bw - 1 + minY
                    parts[owner[i]].add(y * w + x)
                }
                return parts.map { it.toIntArray() }
            }
            if (cores.isEmpty() && cur.none { it }) return listOf(pix)
        }
        return listOf(pix)
    }

    /**
     * Busca la X blanca de cerrar: una mancha blanca más o menos cuadrada, de tamaño de botón,
     * en la esquina superior derecha (bajo la barra de estado). Devuelve su centro (media resolución).
     */
    private fun findCloseButton(img: Img): Pair<Float, Float>? {
        val w = img.w
        val h = img.h
        val x0 = (w * 0.70).toInt()
        val y0 = (h * 0.04).toInt()
        val y1 = (h * 0.25).toInt()
        fun white(i: Int) = img.r(i) > 225 && img.g(i) > 225 && img.b(i) > 225
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
                if (qx + 1 < w) { val q = p + 1; if (!seen[q] && white(q)) { seen[q] = true; stack[sp++] = q } }
                if (qx - 1 >= x0) { val q = p - 1; if (!seen[q] && white(q)) { seen[q] = true; stack[sp++] = q } }
                if (qy + 1 < y1) { val q = p + w; if (!seen[q] && white(q)) { seen[q] = true; stack[sp++] = q } }
                if (qy - 1 >= y0) { val q = p - w; if (!seen[q] && white(q)) { seen[q] = true; stack[sp++] = q } }
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
