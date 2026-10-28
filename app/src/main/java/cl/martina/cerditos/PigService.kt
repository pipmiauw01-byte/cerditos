package cl.martina.cerditos

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Servicio de accesibilidad: muestra un panelcito flotante sobre el juego.
 *  - Analizar: captura la pantalla y dibuja el orden (1, 2, 3…) sobre cada cerdito.
 *  - Siguiente: toca por ti el cerdito #1 y vuelve a analizar.
 *  - Auto: juega solo. Saca cerditos (varios a la vez si sus caminos no se cruzan); si no ve
 *    cerditos, revisa 3 veces en 30 s por si carga otro nivel; si algo tapa el juego espera,
 *    y si es una ventana con X (como el cofre), la cierra con la X. Nunca toca "Get".
 */
class PigService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var overlay: OverlayView? = null
    private var panel: LinearLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var body: LinearLayout
    private lateinit var autoBtn: TextView

    private var busy = false
    private var auto = false
    private var lastSignature = ""
    private var noProgress = 0

    private val dp get() = resources.displayMetrics.density

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()
        addPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAuto() }

    override fun onDestroy() {
        stopAuto()
        overlay?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- ventanas

    private fun addOverlay() {
        val v = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(v, lp)
        overlay = v
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addPanel() {
        val pad = (10 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(Color.argb(235, 255, 250, 245))
                setStroke((2 * dp).toInt(), Color.rgb(240, 160, 150))
            }
            elevation = 8 * dp
        }
        val header = TextView(this).apply {
            text = "🐷 Cerditos"
            textSize = 15f
            setTextColor(Color.rgb(90, 58, 53))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(header)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = "Abre el nivel y toca Analizar."
            textSize = 12.5f
            setTextColor(Color.rgb(60, 60, 60))
            maxWidth = (230 * dp).toInt()
            setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
        }
        body.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Analizar", Color.rgb(46, 125, 50)) { analyze() })
        row1.addView(button("Siguiente", Color.rgb(255, 109, 0)) { stepOnce() })
        body.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        autoBtn = button("Auto ▶", Color.rgb(30, 90, 220)) { toggleAuto() }
        row2.addView(autoBtn)
        row2.addView(button("Borrar", Color.rgb(120, 120, 120)) { stopAuto(); busy = false; overlay?.clear(); setStatus("Listo para analizar.") })
        body.addView(row2)
        root.addView(body)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * dp).toInt()
            y = (48 * dp).toInt()
        }

        // arrastrar desde el título; un toque sin arrastrar pliega/despliega el panel
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = panelParams.x; startY = panelParams.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val ddx = e.rawX - downX; val ddy = e.rawY - downY
                    if (abs(ddx) + abs(ddy) > 12 * dp) moved = true
                    if (moved) {
                        panelParams.x = startX + ddx.toInt(); panelParams.y = startY + ddy.toInt()
                        wm.updateViewLayout(root, panelParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            true
        }

        wm.addView(root, panelParams)
        panel = root
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val h = (8 * dp).toInt()
            setPadding(h * 2, h, h * 2, h)
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(color) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                val m = (3 * dp).toInt(); setMargins(m, m, m, m)
            }
            minWidth = (100 * dp).toInt()
            setOnClickListener { onClick() }
        }

    private fun setStatus(s: String) { status.text = s }

    private fun setPanelTouchable(on: Boolean) {
        val p = panel ?: return
        panelParams.flags = if (on) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(p, panelParams)
    }

    // ---------------------------------------------------------------- captura

    /** Esconde nuestras capas, saca captura y las vuelve a mostrar. Reintenta si Android pide esperar. */
    private fun capture(attempt: Int = 0, cb: (Bitmap?) -> Unit) {
        overlay?.visibility = View.INVISIBLE
        panel?.visibility = View.INVISIBLE
        fun restore() { overlay?.visibility = View.VISIBLE; panel?.visibility = View.VISIBLE }
        main.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    hb.close()
                    restore()
                    cb(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    restore()
                    // Android limita cuántas capturas por segundo: se espera un poco y se reintenta
                    if (attempt < 4) main.postDelayed({ capture(attempt + 1, cb) }, 350L)
                    else cb(null)
                }
            })
        }, HIDE_MS)
    }

    private fun analyzeScreen(done: (Scene, Solver?, Plan?) -> Unit) {
        capture { bmp ->
            if (bmp == null) {
                busy = false
                stopAuto()
                setStatus("No pude capturar la pantalla. Intenta de nuevo en un segundo.")
                return@capture
            }
            worker.execute {
                val scene = Detector.detect(bmp)
                bmp.recycle()
                val solver = if (scene.pigs.isNotEmpty()) Solver(scene.pigs) else null
                val plan = solver?.solve()
                main.post { done(scene, solver, plan) }
            }
        }
    }

    // ---------------------------------------------------------------- acciones

    private fun analyze() {
        if (busy) return
        busy = true
        setStatus("Mirando…")
        analyzeScreen { scene, _, plan ->
            busy = false
            when {
                scene.obstructed -> { overlay?.clear(); setStatus("Hay una ventana encima del juego. Ciérrala y vuelve a analizar.") }
                plan == null -> { overlay?.clear(); setStatus("No veo cerditos en pantalla.") }
                else -> { overlay?.show(scene.pigs, plan); setStatus(describe(scene.pigs, plan)) }
            }
        }
    }

    private fun stepOnce() {
        if (busy) return
        auto = false
        resetAutoState()
        step(single = true)
    }

    private fun toggleAuto() {
        if (auto) { stopAuto(); busy = false; setStatus("Auto detenido."); return }
        if (busy) return
        auto = true
        resetAutoState()
        autoBtn.text = "Parar ■"
        step(single = false)
    }

    private fun resetAutoState() {
        lastSignature = ""
        noProgress = 0
        emptyChecks = 0
        popupSince = 0L
        recentTaps = emptyList()
        lastCount = -1
        lastBatchExits = 0
        suspicious = 0
    }

    private fun stopAuto() {
        auto = false
        main.removeCallbacks(tick)
        if (::autoBtn.isInitialized) autoBtn.text = "Auto ▶"
    }

    /** Vuelve a mirar la pantalla después de `delay` ms (solo si el auto sigue encendido). */
    private val tick = Runnable { busy = false; if (auto) step(single = false) }
    private fun again(delay: Long) {
        main.removeCallbacks(tick)
        main.postDelayed(tick, delay)
    }

    /** Un ciclo del modo automático (o un solo toque, si single = true). */
    private fun step(single: Boolean) {
        busy = true
        if (!single && emptyChecks == 0 && popupSince == 0L) setStatus("Mirando…")
        analyzeScreen { scene, solver, plan ->
            if (!single && !auto) { busy = false; return@analyzeScreen } // lo detuvieron mientras miraba
            // 1) algo tapa el juego: cerrar con la X si se ve; si no, esperar a que se vaya
            if (scene.obstructed) {
                overlay?.clear()
                if (single) { busy = false; setStatus("Hay una ventana encima del juego."); return@analyzeScreen }
                emptyChecks = 0
                val now = System.currentTimeMillis()
                if (popupSince == 0L) popupSince = now
                val cx = scene.closeX
                val cy = scene.closeY
                if (cx != null && cy != null) {
                    setStatus("Hay una ventana (¿cofre?). La cierro con la X…")
                    tap(cx, cy) { again(CLOSE_WAIT_MS) }
                } else if (now - popupSince > POPUP_MAX_MS) {
                    busy = false; stopAuto()
                    setStatus("Hace más de ${POPUP_MAX_MS / 1000} s que algo tapa el juego. Auto detenido.")
                } else {
                    setStatus("Algo tapa el juego. Espero a que se quite…")
                    again(POPUP_POLL_MS)
                }
                return@analyzeScreen
            }
            popupSince = 0L

            // 2) no hay cerditos: revisar de nuevo 3 veces en 30 s (puede estar cargando el próximo nivel)
            if (plan == null || solver == null) {
                overlay?.clear()
                if (single) { busy = false; setStatus("No veo cerditos. ¿Nivel terminado? 🎉"); return@analyzeScreen }
                if (emptyChecks >= EMPTY_CHECKS) {
                    busy = false; stopAuto()
                    setStatus("No aparecieron más cerditos en ${EMPTY_CHECKS * EMPTY_WAIT_MS / 1000} s. Auto terminado 🎉")
                } else {
                    emptyChecks++
                    setStatus("No veo cerditos. Reviso de nuevo en ${EMPTY_WAIT_MS / 1000} s ($emptyChecks/$EMPTY_CHECKS)…")
                    again(EMPTY_WAIT_MS)
                }
                return@analyzeScreen
            }
            emptyChecks = 0
            val pigs = scene.pigs

            // 3) un cerdito todavía va corriendo: esperar un poquito
            if (!single && stillMoving(pigs)) { again(MOVING_RECHECK_MS); return@analyzeScreen }

            // 4) desaparecieron más cerditos de los que saqué: algo los tapa a medias, mirar otra vez
            if (!single && lastCount >= 0 && pigs.size < lastCount - lastBatchExits - 1 && suspicious < 2) {
                suspicious++
                again(SUSPICIOUS_RECHECK_MS)
                return@analyzeScreen
            }
            suspicious = 0
            recentTaps = emptyList()

            if (plan.moves.isEmpty()) {
                busy = false; stopAuto()
                overlay?.show(pigs, plan)
                setStatus(describe(pigs, plan))
                return@analyzeScreen
            }

            // 5) ¿cambió algo desde el toque anterior? si no, el juego no responde como esperamos
            val sig = signature(pigs)
            if (sig == lastSignature) noProgress++ else noProgress = 0
            lastSignature = sig
            if (noProgress >= 3) {
                busy = false; stopAuto()
                overlay?.show(pigs, plan)
                setStatus("Toqué y nada se movió. Revisa que el juego esté a la vista, o mueve tú el cerdito naranjo.")
                return@analyzeScreen
            }

            // 6) tocar: varias salidas seguidas si sus recorridos no se cruzan
            val batch = if (single) plan.moves.take(1) else solver.firstBatch(plan)
            overlay?.show(pigs, plan, onlyNext = true)
            setStatus(
                "Quedan ${pigs.size}. " + when {
                    !batch[0].exits -> "Acerco un cerdito para abrir camino…"
                    batch.size > 1 -> "Saco ${batch.size} a la vez…"
                    else -> "Saco uno con el camino libre…"
                }
            )
            lastCount = pigs.size
            lastBatchExits = batch.count { it.exits }
            recentTaps = batch.map { Tapped(pigs[it.pig], it.exits, it.dist) }
            tapSequence(batch.map { pigs[it.pig] }) {
                if (single) main.postDelayed({ busy = false; analyze() }, SETTLE_MS)
                else again(if (batch[0].exits) SETTLE_MS else SLIDE_SETTLE_MS)
            }
        }
    }

    /** Un cerdito que acabamos de tocar y que puede seguir en movimiento. */
    private class Tapped(val pig: Pig, val exits: Boolean, val dist: Float)
    private var recentTaps: List<Tapped> = emptyList()
    private var emptyChecks = 0
    private var popupSince = 0L
    private var lastCount = -1
    private var lastBatchExits = 0
    private var suspicious = 0

    /** ¿Hay un cerdito a medio camino en el recorrido de uno que acabamos de tocar? */
    private fun stillMoving(pigs: List<Pig>): Boolean {
        for (t in recentTaps) {
            val a = t.pig
            for (p in pigs) {
                if (p.dx * a.dx + p.dy * a.dy < 0.9f) continue // mira hacia otro lado
                val rx = p.cx - a.cx
                val ry = p.cy - a.cy
                val fwd = rx * a.dx + ry * a.dy
                val lat = abs(-rx * a.dy + ry * a.dx)
                if (lat > a.width * 0.5f || fwd < a.width * 0.25f) continue
                if (t.exits || fwd < t.dist - a.width * 0.25f) return true
            }
        }
        return false
    }

    private fun tapSequence(targets: List<Pig>, done: () -> Unit) {
        if (targets.isEmpty()) { done(); return }
        tap(targets[0].cx, targets[0].cy) {
            if (targets.size == 1) done()
            else main.postDelayed({ tapSequence(targets.drop(1), done) }, TAP_GAP_MS)
        }
    }

    private fun tap(x: Float, y: Float, done: () -> Unit) {
        setPanelTouchable(false) // que el toque llegue al juego aunque caiga bajo el panel
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
            .build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { setPanelTouchable(true); done() }
            override fun onCancelled(g: GestureDescription?) { setPanelTouchable(true); done() }
        }
        if (!dispatchGesture(gesture, cb, main)) { setPanelTouchable(true); done() }
    }

    private fun signature(pigs: List<Pig>): String {
        val q = (pigs.map { it.width }.average().toFloat() / 3f).coerceAtLeast(1f)
        return pigs.map { "${(it.cx / q).toInt()},${(it.cy / q).toInt()}" }.sorted().joinToString(";")
    }

    private fun describe(pigs: List<Pig>, plan: Plan): String {
        val slides = plan.moves.count { !it.exits }
        val slideNote = if (slides > 0)
            "\n$slides de esos toques solo hacen avanzar un cerdito hasta chocar (flecha corta): es parte de la solución."
        else ""
        return when {
            pigs.isEmpty() -> "No veo cerditos en pantalla."
            plan.complete -> "${pigs.size} cerditos · se resuelve en ${plan.moves.size} toques ✓\n" +
                "Tócalos en orden: 1, 2, 3… (el naranjo es el próximo)." + slideNote
            plan.moves.isEmpty() -> "⚠ Ningún cerdito puede moverse.\nUsa el OVNI en el marcado en amarillo."
            else -> "⚠ No hay forma de sacarlos todos. Sigue los ${plan.moves.size} toques numerados " +
                "y después toca Analizar: te marcará cuál quitar con el OVNI." + slideNote
        }
    }

    companion object {
        private const val HIDE_MS = 70L                 // esconder el panel antes de capturar
        private const val TAP_GAP_MS = 90L              // entre toques de una misma tanda
        private const val SETTLE_MS = 350L              // después de sacar cerditos
        private const val SLIDE_SETTLE_MS = 350L        // después de acercar uno
        private const val MOVING_RECHECK_MS = 200L      // si alguno sigue corriendo
        private const val SUSPICIOUS_RECHECK_MS = 600L  // si faltan cerditos de golpe
        private const val EMPTY_WAIT_MS = 10_000L       // sin cerditos: 3 revisiones cada 10 s = 30 s
        private const val EMPTY_CHECKS = 3
        private const val POPUP_POLL_MS = 1_500L        // ventana encima: revisar cada 1,5 s
        private const val POPUP_MAX_MS = 120_000L       // …hasta 2 minutos
        private const val CLOSE_WAIT_MS = 1_200L        // después de tocar la X
    }
}
