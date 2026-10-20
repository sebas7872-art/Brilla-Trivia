package ar.com.brilla.trivia.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.PixelFormat
import android.view.*
import android.widget.*
import ar.com.brilla.trivia.AppRuntime
import ar.com.brilla.trivia.AppView
import ar.com.brilla.trivia.access.TriviaAccessibilityService

class FloatingPanel(private val service:TriviaAccessibilityService,private val runtime:AppRuntime) {
    private val wm=service.getSystemService(WindowManager::class.java)
    private var root:LinearLayout?=null; private var info:TextView?=null; private var details:LinearLayout?=null
    private var compact=false; private var lastActive=false
    private val params=WindowManager.LayoutParams(Style.dp(service,222),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=16; y=180 }
    fun show() {
        if(root!=null) return
        val v=Style.column(service).apply { setPadding(Style.dp(service,9),Style.dp(service,7),Style.dp(service,9),Style.dp(service,9)); background=Style.round(Style.pale,Style.dp(service,22).toFloat()); elevation=Style.dp(service,8).toFloat() }
        val title=Style.label(service,"✦ Brilla Trivia  ·  arrastrá",13f,true)
        var sx=0f; var sy=0f; var ox=0; var oy=0
        title.setOnTouchListener { _,e -> when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=params.x; oy=params.y; true }
            MotionEvent.ACTION_MOVE -> { params.x=(ox+e.rawX-sx).toInt().coerceIn(0,(service.resources.displayMetrics.widthPixels-params.width).coerceAtLeast(0)); params.y=(oy+e.rawY-sy).toInt().coerceIn(0,(service.resources.displayMetrics.heightPixels-Style.dp(service,140)).coerceAtLeast(0)); root?.let { wm.updateViewLayout(it,params) }; true }
            MotionEvent.ACTION_UP -> { title.performClick(); true }
            else -> false
        } }
        v.addView(title)
        info=Style.label(service,"Preparado",12f).also { v.addView(it) }
        val controls=LinearLayout(service)
        listOf(Triple("▶","Iniciar o reanudar",{ if(runtime.view.paused) runtime.resume() else runtime.start() }),Triple("Ⅱ","Pausar",{runtime.pause()}),Triple("■","Detener",{runtime.stopNow()})).forEach { (text,label,action) ->
            val b=Style.button(service,text,text=="■",action).apply { contentDescription=label; layoutParams=LinearLayout.LayoutParams(0,Style.dp(service,48),1f).apply { setMargins(2,0,2,0) } }; controls.addView(b)
        }
        v.addView(controls)
        details=Style.column(service).also { d ->
            d.addView(Style.button(service,"Detectar y confirmar chat") { detect() })
            d.addView(Style.button(service,"Abrir aplicación") { service.startActivity(Intent(service,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
            d.addView(Style.button(service,"Cerrar panel") { runtime.stopNow(); hide() }); v.addView(d)
        }
        v.addView(Style.button(service,"⌃ / ⌄",false) { compact=!compact; layout() }.apply { contentDescription="Minimizar o expandir"; minHeight=Style.dp(service,32); layoutParams=LinearLayout.LayoutParams(-1,Style.dp(service,36)) })
        root=v; wm.addView(v,params); update(runtime.view)
    }
    private fun layout() { details?.visibility=if(compact) View.GONE else View.VISIBLE; params.width=Style.dp(service,if(compact) 190 else 222); root?.let { wm.updateViewLayout(it,params) } }
    fun update(v:AppView) { if(root==null) return; if(v.active && !lastActive) { compact=true; layout() }; lastActive=v.active
        val time=if(v.remaining>0) " · ${(v.remaining+999)/1000}s" else ""
        info?.text="${v.round}/${v.total}$time · ${v.status.take(110)}\n${v.chat.take(36)}"
    }
    private fun dialog(builder:AlertDialog.Builder) { val d=builder.create(); d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY); d.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE); d.show() }
    private fun detect() { runtime.detect { titles,message ->
        if(titles.isEmpty()) dialog(AlertDialog.Builder(service).setTitle("Revisá el chat").setMessage(message).setPositiveButton("Entendido",null))
        else dialog(AlertDialog.Builder(service).setTitle("Confirmá el grupo").setItems(titles.toTypedArray()) { _,i -> runtime.bind(titles[i]) { result -> dialog(AlertDialog.Builder(service).setMessage(result).setPositiveButton("Listo",null)) } }.setNegativeButton("Cancelar",null))
    } }
    fun hide() { root?.let { runCatching { wm.removeView(it) } }; root=null; info=null }
}
