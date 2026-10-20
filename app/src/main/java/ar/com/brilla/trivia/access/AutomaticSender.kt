package ar.com.brilla.trivia.access

import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import ar.com.brilla.trivia.core.Publication
import java.util.concurrent.atomic.AtomicBoolean

/** One publication at a time, no coordinate fallback and no automatic retry after a click. */
class AutomaticSender(private val reader:TreeReader,private val handler:Handler,private val allowed:AtomicBoolean) {
    var state="Sin envíos"; private set
    var busy=false; private set
    private var generation=0
    fun cancel() { generation++; busy=false; state="Envío interrumpido; no se reintenta automáticamente." }
    fun send(p:Publication,b:Binding,done:(Boolean,String)->Unit) {
        if(busy) { done(false,"Ya hay un envío en curso."); return }
        busy=true; val run=++generation; val began=SystemClock.uptimeMillis(); var before=emptySet<String>()
        fun finish(ok:Boolean,message:String) { if(run!=generation) return; busy=false; state=message; done(ok,message) }
        fun safe():Boolean = run==generation && allowed.get()
        fun read(action:(TreeSnapshot,Adapter)->Unit) {
            if(!safe()) { finish(false,"Envío pausado por seguridad."); return }
            val snapshot=reader.read() ?: run { finish(false,reader.lastProblem); return }
            snapshot.use { s ->
                val a=Adapters.forPackage(s.frame.pkg) ?: run { finish(false,"Aplicación no compatible."); return }
                val error=a.inspect(s.frame,b)
                if(error!=null) { finish(false,error); return }
                if(!safe()) { finish(false,"Cambió la ventana durante el envío."); return }
                action(s,a)
            }
        }
        fun receipt() {
            if(!safe()) { finish(false,"Envío incierto. Revisá el chat antes de continuar."); return }
            read { s,a ->
                val field=a.field(s.frame,b)!!
                val own=a.messages(s.frame).any { it.own && it.text==p.text && it.id !in before }
                if(field.text.isEmpty() && own) finish(true,"Publicación confirmada en pantalla")
                else if(SystemClock.uptimeMillis()-began>=8000) finish(false,"No se pudo confirmar el mensaje. No se reenviará automáticamente.")
                else handler.postDelayed({ receipt() },200)
            }
        }
        read { s,a ->
            val field=a.field(s.frame,b)!!
            if(field.text.isNotBlank()) { finish(false,"Hay un borrador en el campo. No se sobrescribió."); return@read }
            before=a.messages(s.frame).map { it.id }.toSet()
            val node=s.handles[field.path]!!
            if(!safe()) { finish(false,"Envío cancelado antes de escribir."); return@read }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if(!safe()) { finish(false,"Cambió la ventana antes de escribir."); return@read }
            val args=Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,p.text) }
            if(!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) { finish(false,"La aplicación no permite escribir mediante accesibilidad."); return@read }
            state="Verificando el texto"
            fun verifyAndSend(attempt:Int) {
                read next@{ fresh,adapter ->
                    val current = adapter.field(fresh.frame,b)?.text.orEmpty()
                    if(current == p.text) {
                        // Texto verificado exactamente: solo entonces se pulsa Enviar
                        val send=adapter.send(fresh.frame)
                        if(send==null) { finish(false,"No se encontró un único botón Enviar accesible."); return@next }
                        if(!safe()) { finish(false,"Envío detenido antes de pulsar Enviar."); return@next }
                        state="Esperando confirmación"
                        if(!fresh.handles[send.path]!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) finish(false,"La acción Enviar fue rechazada.")
                        else handler.postDelayed({ receipt() },200)
                        return@next
                    }
                    if(current.isEmpty()) {
                        // Campo vacío: comprobar si el mensaje ya está publicado (sin pulsar Enviar)
                        val alreadyPublished = adapter.messages(fresh.frame).any { it.own && it.text==p.text && it.id !in before }
                        if(alreadyPublished) {
                            finish(true,"Publicación ya visible en el chat (campo vacío tras aceptar el texto)")
                            return@next
                        }
                        if(attempt < 2) {
                            handler.postDelayed({ verifyAndSend(attempt+1) }, 220)
                            return@next
                        }
                        finish(false,"El campo quedó vacío y el mensaje no aparece publicado. No se envió. Revisá el chat.")
                        return@next
                    }
                    // Texto distinto (ni vacío ni coincidente)
                    if(attempt < 2) {
                        handler.postDelayed({ verifyAndSend(attempt+1) }, 220)
                        return@next
                    }
                    finish(false,"El texto introducido no coincide. No se envió.")
                }
            }
            handler.postDelayed({ verifyAndSend(0) }, 320)
        }
    }
}
