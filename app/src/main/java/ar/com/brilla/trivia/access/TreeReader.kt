package ar.com.brilla.trivia.access

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class TreeSnapshot(val frame:Frame, val handles:Map<String,AccessibilityNodeInfo>): java.io.Closeable {
    override fun close() { handles.values.forEach { @Suppress("DEPRECATION") it.recycle() } }
}
class TreeReader(private val service: AccessibilityService) {
    var lastProblem=""; private set
    fun read():TreeSnapshot? {
        if(!service.getSystemService(PowerManager::class.java).isInteractive) { lastProblem="Pantalla apagada."; return null }
        if(service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { lastProblem="Teléfono bloqueado."; return null }
        val windows=service.windows
        val active=windows.firstOrNull { it.isActive && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if(active!=null && active.type!=AccessibilityWindowInfo.TYPE_APPLICATION) { lastProblem="Una ventana del sistema está abierta."; return null }
        val window=active ?: windows.filter { it.type==AccessibilityWindowInfo.TYPE_APPLICATION }.firstOrNull { it.isFocused }
        val root=window?.root ?: run { lastProblem="No hay una ventana de aplicación activa."; return null }
        val nodes=mutableListOf<UiNode>(); val handles=linkedMapOf<String,AccessibilityNodeInfo>()
        val start=SystemClock.uptimeMillis()
        fun walk(node:AccessibilityNodeInfo,path:String,parent:String?,depth:Int) {
            handles[path]=node
            check(nodes.size<1800 && depth<=32 && SystemClock.uptimeMillis()-start<1200) { "El árbol de accesibilidad es demasiado grande o responde con demora." }
            val rect=Rect(); node.getBoundsInScreen(rect)
            if(node.isVisibleToUser) nodes += UiNode(path,parent,node.viewIdResourceName.orEmpty(),if(node.isPassword || (node.isEditable && node.isShowingHintText)) "" else node.text?.toString().orEmpty(),if(node.isPassword) "" else node.contentDescription?.toString().orEmpty(),node.className?.toString().orEmpty(),node.isEditable && !node.isPassword,node.isFocused,node.isEnabled,node.isClickable,rect.top)
            for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it,"$path/$i",path,depth+1) }
        }
        return try { walk(root,"0",null,0); lastProblem=""; TreeSnapshot(Frame(root.packageName?.toString().orEmpty(),window!!.id,nodes),handles) }
        catch(e:Exception) { handles.values.forEach { @Suppress("DEPRECATION") it.recycle() }; lastProblem=e.message ?: "No se pudo leer la ventana."; null }
    }
}
