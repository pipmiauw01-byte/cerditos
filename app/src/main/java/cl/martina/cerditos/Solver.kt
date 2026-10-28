package cl.martina.cerditos

import kotlin.math.abs

/**
 * order: índices de cerditos en el orden en que hay que tocarlos (todos salen limpio).
 * stuck: los que quedan sin salida aunque se saquen todos los demás.
 * ufo:   si hay atascados, el que conviene quitar con el OVNI para liberar a más.
 */
data class Plan(val order: List<Int>, val stuck: List<Int>, val ufo: Int?)

/**
 * Modelo del juego: al tocar un cerdito, corre en línea recta hacia donde mira su cabeza
 * hasta salir de la pantalla. Si algún otro cerdito está en ese carril, choca.
 *
 * Los cerditos viven en una grilla (en tu captura, rotada 45°): cada uno ocupa 2×1 casillas
 * y la casilla mide ~1,25 veces el ancho del dibujo. Un cerdito B bloquea a A si la "caja"
 * de B se cruza con el carril de A por delante de A.
 *
 * Sacar un cerdito nunca bloquea a otro, así que basta con ir sacando siempre uno que tenga
 * el camino libre: si existe alguna forma de sacarlos a todos, este método la encuentra.
 */
object Solver {
    fun solve(pigs: List<Pig>): Plan {
        val n = pigs.size
        if (n == 0) return Plan(emptyList(), emptyList(), null)

        val widths = pigs.map { it.width }.sorted()
        val cell = widths[widths.size / 2] * 1.25f
        val halfLen = cell          // media longitud de la caja (2 casillas)
        val halfWid = cell / 2f     // medio ancho (1 casilla)
        val eps = 0.2f * cell       // tolerancia: tocarse de costado no es chocar

        fun extent(p: Pig, ux: Float, uy: Float): Float {
            val nx = -p.dy
            val ny = p.dx
            return abs(halfLen * (p.dx * ux + p.dy * uy)) + abs(halfWid * (nx * ux + ny * uy))
        }

        fun blocks(i: Int, j: Int): Boolean {
            val a = pigs[i]
            val b = pigs[j]
            val nx = -a.dy
            val ny = a.dx
            val rx = b.cx - a.cx
            val ry = b.cy - a.cy
            if (abs(rx * nx + ry * ny) >= halfWid + extent(b, nx, ny) - eps) return false // otro carril
            val gap = (rx * a.dx + ry * a.dy) - extent(b, a.dx, a.dy) - halfLen
            return gap >= -eps // está delante
        }

        // blk[i][j] = j está en el camino de i
        val blk = Array(n) { i -> BooleanArray(n) { j -> i != j && blocks(i, j) } }

        fun greedy(start: Set<Int>): Pair<List<Int>, Set<Int>> {
            val alive = start.toMutableSet()
            val out = ArrayList<Int>()
            while (alive.isNotEmpty()) {
                val free = alive.filter { i -> alive.none { j -> blk[i][j] } }
                if (free.isEmpty()) break
                // de los libres, primero el que desbloquea a más (y si empatan, el de más arriba)
                val pick = free.maxWith(
                    compareBy<Int> { i -> alive.count { j -> blk[j][i] } }.thenByDescending { pigs[it].cy }
                )
                out.add(pick)
                alive.remove(pick)
            }
            return out to alive
        }

        val (order, rest) = greedy((0 until n).toSet())
        if (rest.isEmpty()) return Plan(order, emptyList(), null)

        // Atasco: ¿a cuál conviene sacar con el OVNI?
        var best: Int? = null
        var bestFreed = -1
        for (k in rest) {
            val freed = greedy(rest - k).first.size
            if (freed > bestFreed) { bestFreed = freed; best = k }
        }
        return Plan(order, rest.toList(), best)
    }
}
