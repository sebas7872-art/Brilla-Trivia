package ar.com.brilla.trivia

import android.app.Instrumentation
import android.app.NotificationManager
import android.app.UiAutomation
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import ar.com.brilla.trivia.core.*
import ar.com.brilla.trivia.data.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs only on an emulator and only against the separate local fixture application. */
class DeviceTests:Instrumentation() {
    private lateinit var rt:AppRuntime
    private lateinit var ui:UiAutomation
    private var checks=0
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments); start() }
    private fun ok(value:Boolean,label:String) { check(value) { "$label · ${if(::rt.isInitialized) rt.view.status else "sin aplicación"}" }; checks++; sendStatus(0,Bundle().apply {putString("stream","OK $checks: $label\n")}) }
    private fun await(label:String,ms:Long=30000,predicate:()->Boolean) { val end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(predicate()){ok(true,label);return};SystemClock.sleep(100)};error("Tiempo agotado: $label · ${rt.view}") }
    private fun shell(command:String) { ui.executeShellCommand(command).use { p -> android.os.ParcelFileDescriptor.AutoCloseInputStream(p).readBytes() } }
    private fun click(text:String) {
        fun search(n:AccessibilityNodeInfo):Boolean { if((n.text?.toString()==text || n.contentDescription?.toString()==text) && n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);for(i in 0 until n.childCount){val c=n.getChild(i)?:continue;val found=search(c);@Suppress("DEPRECATION") c.recycle();if(found)return true};return false }
        val roots=ui.windows.filter { it.type==AccessibilityWindowInfo.TYPE_APPLICATION || it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.mapNotNull {it.root}
        val result=roots.any {search(it)};roots.forEach { @Suppress("DEPRECATION") it.recycle() };ok(result,"Pulsar $text")
    }
    private fun capture(name:String) { ui.takeScreenshot()?.let { b -> val file=File(targetContext.getExternalFilesDir(null),"verification/$name.png");file.parentFile?.mkdirs();file.outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle() } }
    private fun bind() {
        var names=emptyList<String>();var reason="";val until=SystemClock.elapsedRealtime()+30000
        while(names.isEmpty() && SystemClock.elapsedRealtime()<until) {
            val latch=CountDownLatch(1);rt.detect { n,r->names=n;reason=r;latch.countDown() };latch.await(5,TimeUnit.SECONDS);if(names.isEmpty()) SystemClock.sleep(350)
        }
        ok(names.contains("Grupo de prueba Brilla"),"Detectar grupo ($reason)")
        val latch=CountDownLatch(1);rt.bind("Grupo de prueba Brilla") {latch.countDown()};ok(latch.await(10,TimeUnit.SECONDS),"Confirmar chat")
    }
    override fun onStart() {
        try {
            waitForIdleSync();rt=(targetContext.applicationContext as BrillaApp).runtime
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.contains("generic")) {"Solo se permite ejecutar el runner en un emulador."}
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            shell("settings put system screen_off_timeout 1800000");shell("svc power stayon true");shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            await("Base Room lista") {rt.view.ready};ok(rt.view.questions.size==6,"Seis preguntas persistidas")
            val done=CountDownLatch(1)
            rt.database({ r ->
                val c=GameConfig(rounds=1,random=false,sendIntervalMs=2000,messageLength=1000);r.save(c)
                val ts=Templates.defaults().map {if(it.key=="welcome")it.copy(text="💖 Prueba local {total}")else it};r.save(ts)
                check(r.config()==c);check(r.templates()==ts)
                val e=TriviaEngine(c,schedule(r.questions(),c),ts){0};check(Json.state(Json.state(e.snapshot()))==e.snapshot())
                "Guardado y recuperado"
            }) {done.countDown()}
            ok(done.await(20,TimeUnit.SECONDS),"Persistencia de configuración, plantillas y estado")
            shell("settings put secure accessibility_enabled 0")
            shell("settings put secure enabled_accessibility_services ar.com.brilla.trivia/ar.com.brilla.trivia.access.TriviaAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
            shell("pm grant ar.com.brilla.trivia android.permission.POST_NOTIFICATIONS")
            await("Servicio de accesibilidad conectado",45000) {rt.view.connected}
            rt.setMode("Chat de prueba");SystemClock.sleep(400)
            shell("am start -W -n ar.com.brilla.fixture/.ChatActivity")
            bind();await("Campo editable confirmado") {rt.view.field.contains("disponible")}
            runOnMainSync {rt.service!!.panel!!.show()};capture("panel-preparado")
            rt.start();await("Pregunta publicada y reloj iniciado",60000) {rt.view.status=="Esperando respuestas"}
            ok(rt.view.remaining in 1..10000,"Cronómetro real de 10 segundos");capture("panel-jugando")
            click("Pedro responde París")
            await("Incorrecta seguida de correcta, mismo remitente",10000) {rt.view.ranking.any {it.name=="Pedro"&&it.points==1}}
            await("Final exacto de una ronda y anuncio del campeón",60000) {rt.view.status=="Finalizado"}
            ok(rt.view.round==1 && rt.view.history.size==1,"No hay pregunta 2");ok(rt.view.ranking.single().points==1,"Sin puntos duplicados")
            click("Pedro responde París");rt.start();await("Segunda partida local activa") {rt.view.active}
            click("Cambiar grupo");await("Pausa al cambiar grupo") {rt.view.paused}
            val frozen=rt.view.remaining;SystemClock.sleep(1400);ok(rt.view.remaining==frozen,"Cronómetro congelado")
            rt.resume();SystemClock.sleep(500);ok(rt.view.paused,"No reanuda en grupo incorrecto")
            click("Cambiar grupo");rt.resume();await("Reanudación en grupo original") {!rt.view.paused}
            click("Ocultar campo");await("Pausa sin campo") {rt.view.paused};click("Ocultar campo");rt.resume();await("Recuperación del campo") {!rt.view.paused}
            shell("am start -W -n ar.com.brilla.trivia/.ui.MainActivity");await("Pausa al cambiar aplicación") {rt.view.paused};capture("inicio")
            click("Detener");await("Stop flotante") {!rt.view.active}
            rt.setMode("Simulación");SystemClock.sleep(300);rt.start();await("Simulación con mismo motor") {rt.view.active}
            val nm=targetContext.getSystemService(NotificationManager::class.java)
            await("Notificación persistente") {nm.activeNotifications.any {it.id==10}}
            nm.activeNotifications.single {it.id==10}.notification.actions.single().actionIntent.send()
            await("Stop desde notificación") {!rt.view.active}
            runOnMainSync {rt.service?.panel?.hide()}
            finish(ActivityResult.OK,Bundle().apply {putString("stream","BRILLA_DEVICE_TESTS_OK: $checks verificaciones. Android ${Build.VERSION.RELEASE}. Chat local; WhatsApp/TikTok no probados.\n")})
        } catch(e:Throwable) {
            if(::rt.isInitialized) rt.stopNow()
            finish(1,Bundle().apply {putString("stream","BRILLA_DEVICE_TESTS_FAILED: $checks verificaciones antes del fallo.\n${e.stackTraceToString()}\n")})
        }
    }
    private object ActivityResult { const val OK=-1 }
}
