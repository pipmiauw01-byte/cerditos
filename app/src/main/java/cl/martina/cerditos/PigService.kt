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
 *  - Auto: repite "Siguiente" hasta vaciar el nivel (o hasta un atasco).
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
        row2.addView(button("Borrar", Color.rgb(120, 120, 120)) { stopAuto(); overlay?.clear(); setStatus("Listo para analizar.") })
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

    /** Esconde nuestras capas, saca captura y las vuelve a mostrar. */
    private fun capture(cb: (Bitmap?) -> Unit) {
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
                    cb(null)
                }
            })
        }, 150)
    }

    private fun analyzeScreen(done: (List<Pig>, Plan) -> Unit) {
        capture { bmp ->
            if (bmp == null) {
                busy = false
                stopAuto()
                setStatus("No pude capturar la pantalla. Intenta de nuevo en un segundo.")
                return@capture
            }
            worker.execute {
                val pigs = Detector.detect(bmp)
                bmp.recycle()
                val plan = Solver(pigs).solve()
                main.post { done(pigs, plan) }
            }
        }
    }

    // ---------------------------------------------------------------- acciones

    private fun analyze() {
        if (busy) return
        busy = true
        setStatus("Mirando…")
        analyzeScreen { pigs, plan ->
            busy = false
            overlay?.show(pigs, plan)
            setStatus(describe(pigs, plan))
        }
    }

    private fun stepOnce() {
        if (busy) return
        auto = false
        lastSignature = ""
        noProgress = 0
        step()
    }

    private fun toggleAuto() {
        if (auto) { stopAuto(); setStatus("Auto detenido."); return }
        if (busy) return
        auto = true
        lastSignature = ""
        noProgress = 0
        autoBtn.text = "Parar ■"
        step()
    }

    private fun stopAuto() {
        auto = false
        if (::autoBtn.isInitialized) autoBtn.text = "Auto ▶"
    }

    /** Analiza, toca el cerdito #1 y (en auto) repite. */
    private fun step() {
        busy = true
        setStatus("Mirando…")
        analyzeScreen { pigs, plan ->
            if (pigs.isEmpty()) {
                busy = false; stopAuto(); overlay?.clear()
                setStatus("No veo cerditos. ¿Nivel terminado? 🎉")
                return@analyzeScreen
            }
            if (plan.moves.isEmpty()) {
                busy = false; stopAuto()
                overlay?.show(pigs, plan)
                setStatus(describe(pigs, plan))
                return@analyzeScreen
            }
            // ¿cambió algo desde el toque anterior? (si no, el juego no está respondiendo como esperamos)
            val sig = signature(pigs)
            if (sig == lastSignature) noProgress++ else noProgress = 0
            lastSignature = sig
            if (noProgress >= 2) {
                busy = false; stopAuto()
                overlay?.show(pigs, plan)
                setStatus("Toqué y nada se movió. Revisa que el juego esté a la vista, o mueve tú el cerdito naranjo.")
                return@analyzeScreen
            }

            overlay?.show(pigs, plan, onlyNext = true)
            val next = plan.moves[0]
            setStatus(
                "Quedan ${pigs.size}. " +
                    (if (next.exits) "Saco uno con el camino libre…" else "Acerco un cerdito para abrir camino…")
            )
            val target = pigs[next.pig]
            tap(target.cx, target.cy) {
                main.postDelayed({
                    busy = false
                    if (auto) step() else analyze()
                }, STEP_DELAY_MS)
            }
        }
    }

    private fun tap(x: Float, y: Float, done: () -> Unit) {
        setPanelTouchable(false) // que el toque llegue al juego aunque caiga bajo el panel
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
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
        private const val STEP_DELAY_MS = 1300L // tiempo para que el cerdito termine de salir
    }
}
