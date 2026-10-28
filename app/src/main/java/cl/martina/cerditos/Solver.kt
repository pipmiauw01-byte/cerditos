package cl.martina.cerditos

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Un toque. pig = índice del cerdito (según la detección). exits = sale de la pantalla;
 * si no, avanza `dist` píxeles hasta chocar con otro y se queda ahí.
 */
data class Move(val pig: Int, val exits: Boolean, val dist: Float)

/**
 * moves:    toques en orden.
 * stuck:    cerditos que quedan sin poder salir al final (vacío = se resuelve completo).
 * ufo:      si hay atasco, el cerdito que conviene quitar con el OVNI.
 */
data class Plan(val moves: List<Move>, val stuck: List<Int>, val ufo: Int?) {
    val complete get() = stuck.isEmpty()
}

/**
 * Reglas del juego (deducidas de las capturas):
 *  - Al tocar un cerdito corre en línea recta hacia donde mira su cabeza.
 *  - Si no hay nadie en su carril, sale de la pantalla.
 *  - Si hay alguien, avanza hasta tocarlo y se queda ahí (no vuelve atrás).
 *
 * Por eso a veces hay que "acomodar" cerditos antes de que otros puedan salir. El solver hace
 * una búsqueda en profundidad con memoria de estados:
 *  - si algún cerdito puede salir, lo saca (sacar uno nunca le cierra el paso a otro);
 *  - si no, prueba avanzar cerditos, primero los que dejan más caminos libres.
 * Primero busca soluciones con avances largos (más fiables) y, si no hay, permite avances cortos.
 */
class Solver(private val pigs: List<Pig>) {
    private val n = pigs.size
    private val dx = FloatArray(n) { pigs[it].dx }
    private val dy = FloatArray(n) { pigs[it].dy }
    private val halfLen: Float
    private val halfWid: Float
    private val eps: Float
    private val quant: Float
    private var minSlide = 0f
    private var deadline = 0L
    private var nodes = 0

    init {
        val lens = pigs.map { it.length }.sorted()
        val wids = pigs.map { it.width }.sorted()
        halfLen = if (n > 0) lens[n / 2] / 2f else 1f
        halfWid = if (n > 0) wids[n / 2] / 2f else 1f
        eps = 0.12f * halfWid         // rozarse de costado no es chocar
        quant = 0.5f * halfWid        // resolución para recordar estados ya vistos
    }

    /** Mitad del largo de la caja de j medida a lo largo del vector unitario (ux, uy). */
    private fun ext(j: Int, ux: Float, uy: Float): Float =
        abs(halfLen * (dx[j] * ux + dy[j] * uy)) + abs(halfWid * (-dy[j] * ux + dx[j] * uy))

    /** Distancia libre delante de i, o null si tiene el camino libre hasta salir. */
    private fun gap(i: Int, px: FloatArray, py: FloatArray, alive: BooleanArray): Float? {
        val nx = -dy[i]
        val ny = dx[i]
        var best = Float.MAX_VALUE
        var found = false
        for (j in 0 until n) {
            if (j == i || !alive[j]) continue
            val rx = px[j] - px[i]
            val ry = py[j] - py[i]
            if (abs(rx * nx + ry * ny) >= halfWid + ext(j, nx, ny) - eps) continue // otro carril
            var g = rx * dx[i] + ry * dy[i] - ext(j, dx[i], dy[i]) - halfLen
            if (g < -eps) continue // está detrás o al lado
            if (g < 0f) g = 0f
            if (g < best) { best = g; found = true }
        }
        return if (found) best else null
    }

    private class Key(val a: IntArray) {
        private val h = a.contentHashCode()
        override fun hashCode() = h
        override fun equals(other: Any?) = other is Key && other.a.contentEquals(a)
    }

    private fun key(px: FloatArray, py: FloatArray, alive: BooleanArray): Key {
        val a = IntArray(2 * n)
        for (i in 0 until n) {
            if (alive[i]) { a[2 * i] = (px[i] / quant).roundToInt(); a[2 * i + 1] = (py[i] / quant).roundToInt() }
            else { a[2 * i] = Int.MIN_VALUE; a[2 * i + 1] = Int.MIN_VALUE }
        }
        return Key(a)
    }

    private var bestPath: List<Move> = emptyList()
    private var bestLeft = Int.MAX_VALUE
    private var bestAlive = BooleanArray(0)
    private var bestPx = FloatArray(0)
    private var bestPy = FloatArray(0)

    private fun dfs(
        px: FloatArray, py: FloatArray, alive: BooleanArray, left: Int,
        path: ArrayList<Move>, seen: HashSet<Key>
    ): Boolean {
        if (left < bestLeft) {
            bestLeft = left; bestPath = ArrayList(path)
            bestAlive = alive.copyOf(); bestPx = px.copyOf(); bestPy = py.copyOf()
        }
        if (left == 0) return true
        if (System.nanoTime() > deadline) return false
        if (!seen.add(key(px, py, alive))) return false
        nodes++

        // 1) si alguien puede salir, sale (sin ramificar)
        for (i in 0 until n) {
            if (!alive[i]) continue
            if (gap(i, px, py, alive) == null) {
                alive[i] = false
                path.add(Move(i, true, 0f))
                if (dfs(px, py, alive, left - 1, path, seen)) return true
                path.removeAt(path.size - 1)
                alive[i] = true
                return false
            }
        }

        // 2) avances: ordenados por cuántos cerditos quedan con salida libre después
        val cand = ArrayList<Triple<Int, Float, Int>>()
        for (i in 0 until n) {
            if (!alive[i]) continue
            val g = gap(i, px, py, alive) ?: continue
            if (g < minSlide) continue
            val ox = px[i]; val oy = py[i]
            px[i] = ox + dx[i] * g; py[i] = oy + dy[i] * g
            var free = 0
            for (j in 0 until n) if (alive[j] && gap(j, px, py, alive) == null) free++
            px[i] = ox; py[i] = oy
            cand.add(Triple(i, g, free))
        }
        cand.sortByDescending { it.third }
        for ((i, g, _) in cand) {
            val ox = px[i]; val oy = py[i]
            px[i] = ox + dx[i] * g; py[i] = oy + dy[i] * g
            path.add(Move(i, false, g))
            if (dfs(px, py, alive, left, path, seen)) return true
            path.removeAt(path.size - 1)
            px[i] = ox; py[i] = oy
        }
        return false
    }

    /** Busca desde las posiciones dadas. Devuelve true si vacía el campo. */
    private fun search(px0: FloatArray, py0: FloatArray, alive0: BooleanArray, budgetMs: Long): Boolean {
        val left0 = alive0.count { it }
        bestPath = emptyList(); bestLeft = Int.MAX_VALUE
        val end = System.nanoTime() + budgetMs * 1_000_000
        val fractions = floatArrayOf(1.2f, 0.6f, 0.24f) // avance mínimo, en múltiplos de medio ancho
        for ((k, f) in fractions.withIndex()) {
            minSlide = f * halfWid
            val slice = (end - System.nanoTime()) / (fractions.size - k)
            deadline = System.nanoTime() + slice
            val keepPath = bestPath; val keepLeft = bestLeft
            val keepAlive = bestAlive; val keepPx = bestPx; val keepPy = bestPy
            if (dfs(px0.copyOf(), py0.copyOf(), alive0.copyOf(), left0, ArrayList(), HashSet())) return true
            if (keepLeft < bestLeft) { // conservar el mejor parcial entre rondas
                bestPath = keepPath; bestLeft = keepLeft
                bestAlive = keepAlive; bestPx = keepPx; bestPy = keepPy
            }
        }
        return false
    }

    fun solve(budgetMs: Long = 2500): Plan {
        if (n == 0) return Plan(emptyList(), emptyList(), null)
        val px = FloatArray(n) { pigs[it].cx }
        val py = FloatArray(n) { pigs[it].cy }
        val alive = BooleanArray(n) { true }
        if (search(px, py, alive, budgetMs * 2 / 3)) return Plan(bestPath, emptyList(), null)

        // No se pudo vaciar: el mejor avance parcial + sugerencia de OVNI para ese momento.
        val moves = bestPath
        val endAlive = bestAlive; val endPx = bestPx; val endPy = bestPy
        val stuck = (0 until n).filter { endAlive[it] }
        var ufo: Int? = null
        var bestLeftAfter = Int.MAX_VALUE
        val per = maxOf(30L, (budgetMs / 3) / maxOf(1, stuck.size))
        for (k in stuck) {
            val a = endAlive.copyOf(); a[k] = false
            val solved = search(endPx, endPy, a, per)
            val leftAfter = if (solved) 0 else bestLeft
            if (leftAfter < bestLeftAfter) { bestLeftAfter = leftAfter; ufo = k }
            if (solved) break
        }
        return Plan(moves, stuck, ufo)
    }
}
