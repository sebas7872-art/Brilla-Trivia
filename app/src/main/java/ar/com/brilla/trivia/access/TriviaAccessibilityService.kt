package ar.com.brilla.trivia.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import ar.com.brilla.trivia.BrillaApp
import ar.com.brilla.trivia.ui.FloatingPanel

class TriviaAccessibilityService:AccessibilityService() {
    var panel:FloatingPanel?=null; private set
    private val runtime get()=(application as BrillaApp).runtime
    override fun onServiceConnected() { super.onServiceConnected(); panel=FloatingPanel(this,runtime); runtime.connect(this) }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event==null) return
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val kind=windows.firstOrNull { it.id==event.windowId }?.type
            if(kind!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && kind!=AccessibilityWindowInfo.TYPE_INPUT_METHOD) runtime.invalidateWindow()
        }
    }
    override fun onInterrupt() { runtime.pause() }
    override fun onUnbind(intent:Intent?):Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
    private fun cleanup() { panel?.hide(); panel=null; runtime.disconnect(this) }
}
