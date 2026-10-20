package ar.com.brilla.trivia.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.PixelFormat
import android.view.*
import android.widget.*
import ar.com.brilla.trivia.AppRuntime
import ar.com.brilla.trivia.AppView
import ar.com.brilla.trivia.R
import ar.com.brilla.trivia.access.TriviaAccessibilityService

class FloatingPanel(private val service: TriviaAccessibilityService, private val runtime: AppRuntime) {
    private val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var info: TextView? = null
    fun show() {
        if (root != null) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 120 }
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE1A1A1A.toInt())
            setPadding(16, 12, 16, 12)
            elevation = 12f
        }
        info = TextView(service).apply { setTextColor(0xFFF5F5F5.toInt()); textSize = 12f; setPadding(0,0,0,8) }
        panel.addView(info)
        fun btn(label: String, action: () -> Unit) = Button(service).apply {
            text = label; isAllCaps = false; textSize = 11f
            setOnClickListener { action() }
            panel.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })
        }
        btn("Detectar") { runtime.detect { names, msg -> service.panel?.updateStatus(msg); if (names.isNotEmpty()) choose(names) } }
        btn("▶ Iniciar") { runtime.start() }
        btn("⏸ Pausa") { runtime.pause() }
        btn("▶ Reanudar") { runtime.resume() }
        btn("Detener") { runtime.stopNow() }
        btn("Confirmar envío") { runtime.confirmPublication() }
        btn("Abrir app") { service.startActivity(Intent(service, ar.com.brilla.trivia.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        root = panel; wm.addView(panel, lp)
    }
    private fun choose(names: List<String>) {
        AlertDialog.Builder(service).setTitle("Confirmá el grupo").setItems(names.toTypedArray()) { _, which ->
            runtime.bind(names[which]) { service.panel?.updateStatus(it) }
        }.setNegativeButton("Cancelar", null).show()
    }
    fun update(v: AppView) {
        show()
        info?.text = "${v.mode} · ${v.status}\n${v.chat}\n${v.field}\nRonda ${v.round}/${v.total} · ${v.remaining/1000}s"
    }
    fun updateStatus(msg: String) { info?.text = msg }
    fun hide() { root?.let { runCatching { wm.removeView(it) } }; root = null; info = null }
}
