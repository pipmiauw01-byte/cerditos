package cl.martina.cerditos

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var state: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (22 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            setBackgroundColor(Color.rgb(232, 245, 214))
        }
        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.rgb(60, 50, 45)) =
            TextView(this).apply {
                text = s; textSize = size; setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            }
        fun button(s: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = s; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            val p = (14 * dp).toInt(); setPadding(p, p, p, p)
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(color) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
            setOnClickListener { onClick() }
        }

        root.addView(text("🐷 Cerditos", 28f, bold = true, color = Color.rgb(90, 58, 53)))
        root.addView(text("Mira la pantalla del juego y te dice en qué orden sacar los cerditos para que ninguno quede atascado. También puede tocarlos por ti.", 15f))

        state = text("", 15f, bold = true)
        root.addView(state)

        root.addView(button("1 · Activar el ayudante", Color.rgb(46, 125, 50)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("¿Aparece \"Ajuste restringido\"? Toca aquí", Color.rgb(140, 110, 100)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(text("Cómo activarlo", 18f, bold = true))
        root.addView(text(
            "• Toca «Activar el ayudante» → Aplicaciones instaladas → Cerditos – ayudante → actívalo.\n" +
            "• Si el interruptor sale gris o dice «Ajuste restringido»: toca el botón café, luego ⋮ (arriba a la derecha) → «Permitir ajustes restringidos», y vuelve a intentar.", 14.5f))

        root.addView(text("Cómo usarlo", 18f, bold = true))
        root.addView(text(
            "• Abre el juego al inicio de un nivel. Aparece un panelcito 🐷 (arrástralo desde el título; tócalo para plegarlo).\n" +
            "• Analizar: pone un número sobre cada cerdito. Tócalos en ese orden. El naranjo es el próximo.\n" +
            "• Siguiente: toca por ti el próximo cerdito y vuelve a analizar.\n" +
            "• Auto: saca todos los cerditos solo. Toca «Parar» cuando quieras.\n" +
            "• Si hay cerditos sin salida, salen con ✕ rojo y uno con «OVNI» amarillo: ese es el que conviene quitar con el booster del OVNI.", 14.5f))

        root.addView(text("Funciona mejor si lo usas desde el comienzo del nivel: una vez que los cerditos quedan trabados entre ellos, ningún orden los saca sin un booster.", 13.5f, color = Color.rgb(110, 100, 95)))

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.rgb(232, 245, 214)) })
    }

    override fun onResume() {
        super.onResume()
        if (serviceEnabled()) {
            state.text = "✅ Ayudante activo. Abre el juego."
            state.setTextColor(Color.rgb(46, 125, 50))
        } else {
            state.text = "❌ Ayudante desactivado"
            state.setTextColor(Color.rgb(200, 40, 40))
        }
    }

    private fun serviceEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return list.split(':').any { it.startsWith(packageName) && it.endsWith("PigService") }
    }
}
