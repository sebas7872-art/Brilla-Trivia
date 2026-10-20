package ar.com.brilla.trivia

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import ar.com.brilla.trivia.access.*
import ar.com.brilla.trivia.core.*
import ar.com.brilla.trivia.data.*
import ar.com.brilla.trivia.ui.MainActivity
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class AppView(
    val ready:Boolean=false,val connected:Boolean=false,val mode:String="Simulación",val status:String="Preparando…",
    val round:Int=0,val total:Int=0,val remaining:Long=0,val active:Boolean=false,val paused:Boolean=false,val uncertain:Boolean=false,
    val chat:String="Sin chat confirmado",val field:String="Sin campo",val sender:String="Sin envíos",val diagnostic:String="",
    val questions:List<Question> = emptyList(),val config:GameConfig=GameConfig(),val templates:List<Template> = Templates.defaults(),
    val ranking:List<Score> = emptyList(),val history:List<RoundResult> = emptyList(),val transcript:List<String> = emptyList(),
    val games:List<GameRecord> = emptyList(),val candidates:List<String> = emptyList()
)
class AppRuntime(private val context:Context) {
    val worker=Handler(HandlerThread("Brilla-coordinador").apply { start() }.looper)
    private val main=Handler(Looper.getMainLooper())
    val allowed=AtomicBoolean(false)
    val listeners=CopyOnWriteArrayList<(AppView)->Unit>()
    @Volatile var view=AppView(); private set
    @Volatile var service:TriviaAccessibilityService?=null; private set
    private var repository:Repository?=null
    private var engine:TriviaEngine?=null
    private var binding:Binding?=null
    private var detected:Frame?=null
    private var reader:TreeReader?=null
    private var sender:AutomaticSender?=null
    private var tracker=MessageTracker()
    private var mode="Simulación"
    private var bank=emptyList<Question>()
    private var config=GameConfig()
    private var templates=Templates.defaults()
    private var games=emptyList<GameRecord>()
    private var idle="Preparado"
    private var field="Sin campo"
    private var diagnostic=""
    private var lastSave=0L
    private var lastScan=0L
    private var seenPhase:Phase?=null
    private var archived=false
    private var simSerial=0L
    private val transcript=ArrayDeque<String>()
    private var simSafe=true
    private val pulse=object:Runnable { override fun run() {
        try { tick() } catch(e:Exception) { allowed.set(false); sender?.cancel(); engine?.pause("Error interno; revisá Diagnóstico."); diagnostic=e.javaClass.simpleName+": "+e.message.orEmpty().take(160) }
        publish(); worker.postDelayed(this,100)
    } }
    init {
        worker.post {
            try {
                val r=Repository(context); r.initialize(); repository=r
                bank=r.questions(); config=r.config(); templates=r.templates(); games=r.dao.games()
                mode=r.dao.value("mode") ?: "Simulación"
                binding=r.dao.value("binding")?.let { j -> JSONObject(j).let { Binding(it.getString("pkg"),it.getString("titleId"),it.getString("title"),it.getString("fieldId"),it.getString("fieldType"),it.getInt("window")) } }
                engine=runCatching { r.restore(::now) }.getOrElse { diagnostic="No se pudo recuperar la partida. Las preguntas siguen guardadas."; null }
            } catch(e:Exception) { idle="No se pudo abrir la base de datos"; diagnostic=e.javaClass.simpleName }
            publish(); worker.post(pulse)
        }
    }
    private fun now()=SystemClock.elapsedRealtime()
    fun connect(s:TriviaAccessibilityService) { service=s; worker.post { reader=TreeReader(s); sender=AutomaticSender(reader!!,worker,allowed); publish() } }
    fun disconnect(s:TriviaAccessibilityService) { if(service===s) { allowed.set(false); service=null; worker.post { sender?.cancel(); sender=null; reader=null; engine?.pause("Servicio de accesibilidad desconectado."); save(); publish() } } }
    fun invalidateWindow() { allowed.set(false); worker.post {
        if(mode!="Simulación" && engine?.active==true && engine?.paused==false) {
            val error=verify()
            if(error!=null) pauseInternal(error) else allowed.set(true)
        }
    } }
    private fun verify():String? {
        val b=binding ?: return "Confirmá primero el chat."
        val s=reader?.read() ?: return reader?.lastProblem ?: "Conectá Accesibilidad."
        s.use { val a=Adapters.forPackage(it.frame.pkg) ?: return "Esta no es la aplicación donde comenzó la partida."; val error=a.inspect(it.frame,b); field=if(error==null) "Campo editable disponible" else "Sin campo confirmado"; return error }
    }
    fun detect(done:(List<String>,String)->Unit) { worker.post {
        val s=reader?.read()
        if(s==null) { main.post { done(emptyList(),reader?.lastProblem ?: "Conectá Accesibilidad.") }; return@post }
        s.use {
            val a=Adapters.forPackage(it.frame.pkg)
            if(a==null || a.label!=mode) { main.post { done(emptyList(),"Abrí el chat de $mode y tocá su campo de escritura.") }; return@use }
            val candidates = a.candidateFields(it.frame)
            val focused = candidates.filter { it.focused }
            val fields = if (focused.size == 1) focused else candidates
            val titles = a.titles(it.frame)
            if (fields.isEmpty() || titles.isEmpty()) {
                main.post { done(emptyList(), "NO SE DETECTÓ EL CAMPO O EL TÍTULO DEL CHAT. Volvé a tocar el campo; la app debe exponerlo a accesibilidad.") }
                return@use
            }
            val chosen = if (fields.size == 1) fields.single() else focused.singleOrNull()
            if (chosen == null) {
                main.post { done(emptyList(), "Hay varios campos editables. Tocá el de escritura del chat y volvé a detectar.") }
                return@use
            }
            detected=it.frame; val names=titles.map { n->n.text }; idle="Confirmá el grupo detectado"; publish()
            main.post { done(names,"Confirmá el destino. No selecciones chats con el mismo nombre.") }
        }
    } }
    fun bind(title:String,done:(String)->Unit={}) { worker.post {
        val old=detected; val current=reader?.read()
        if(old==null || current==null) { main.post { done("Volvé a detectar el chat.") }; return@post }
        current.use { s ->
            val a=Adapters.forPackage(s.frame.pkg)
            val t=a?.titles(s.frame)?.singleOrNull { it.text==title }
            val fields=a?.candidateFields(s.frame).orEmpty(); val f=fields.singleOrNull { it.focused } ?: fields.singleOrNull()
            if(a==null || a.label!=mode || s.frame.pkg!=old.pkg || t==null || f==null) { main.post { done("La ventana cambió; volvé a detectar.") }; return@use }
            val b=Binding(s.frame.pkg,t.id,t.text,f.id,f.type,s.frame.window)
            val prior=binding
            if(engine?.active==true && prior!=null && (prior.pkg!=b.pkg || prior.title!=b.title || prior.titleId!=b.titleId || prior.fieldId!=b.fieldId)) { main.post { done("La partida pertenece a otro chat. No se cambió el destino.") }; return@use }
            binding=b; tracker.baseline(a.messages(s.frame)); field="Campo editable disponible"; idle="CHAT DETECTADO: LISTO PARA JUGAR"
            repository?.dao?.put(SettingRecord("binding",JSONObject().put("pkg",b.pkg).put("titleId",b.titleId).put("title",b.title).put("fieldId",b.fieldId).put("fieldType",b.fieldType).put("window",b.window).toString()))
            publish(); main.post { done(idle) }
        }
    } }
    fun setMode(value:String) { worker.post { if(engine?.active==true) return@post; mode=value; binding=null; detected=null; repository?.dao?.put(SettingRecord("mode",value)); repository?.dao?.delete("binding"); idle="Preparado"; publish() } }
    fun start() { worker.post {
        if(engine?.active==true) return@post
        val error=config.error() ?: if(bank.isEmpty()) "Cargá al menos una pregunta válida." else null
        if(error!=null) { idle=error; publish(); return@post }
        if(templates.none { it.key=="question" && it.enabled && it.text.isNotBlank() }) { idle="Activá una plantilla de pregunta antes de jugar."; publish(); return@post }
        if(mode!="Simulación") {
            val err=verify(); if(err!=null) { idle=err; publish(); return@post }
            reader?.read()?.use { s ->
                val messages=Adapters.forPackage(s.frame.pkg)!!.messages(s.frame)
                tracker.baseline(messages)
            }
        }
        engine=TriviaEngine(config,schedule(bank,config),templates,::now); seenPhase=null; archived=false; transcript.clear(); simSafe=true
        allowed.set(true); save(); publish()
    } }
    private fun tick() {
        val e=engine ?: return
        if(!e.active) { if(!archived) { save(); repository?.archive(e); games=repository?.dao?.games().orEmpty(); archived=true }; return }
        if(mode!="Simulación" && !e.paused && now()-lastScan>=250) {
            lastScan=now(); val s=reader?.read()
            if(s==null) { pauseInternal(reader?.lastProblem ?: "Accesibilidad desconectada."); return }
            s.use {
                val a=Adapters.forPackage(it.frame.pkg); val b=binding
                val error=if(a==null || b==null) "Chat incorrecto." else a.inspect(it.frame,b)
                if(error!=null) { pauseInternal(error); return }
                field="Campo editable disponible"
                val messages=a!!.messages(it.frame)
                tracker.poll(messages).forEach { m ->
                    if(!m.own) diagnostic=if(m.reliable) "Último mensaje reconocido · ${m.sender}: ${m.text.take(80)}" else "Mensaje con remitente ambiguo; no puntuó."
                    e.receive(m)
                }
                seenPhase=e.phase
            }
        }
        val p=e.tick()
        if(p!=null) {
            save()
            if(mode=="Simulación") { transcript.addLast(p.text); while(transcript.size>150) transcript.removeFirst(); e.acknowledge(p.id,true) }
            else {
                val b=binding; val s=sender
                if(b==null || s==null || !allowed.get()) e.acknowledge(p.id,false,"Servicio o chat no disponible.")
                else s.send(p,b) { ok,message -> e.acknowledge(p.id,ok,message); if(!ok) allowed.set(false); save(); publish() }
            }
        }
        if(now()-lastSave>=1000) save()
    }
    private fun pauseInternal(reason:String) {
        allowed.set(false)
        val e=engine ?: return
        if(sender?.busy==true && e.flight!=null) e.acknowledge(e.flight!!.id,false,reason+" Envío incierto.")
        sender?.cancel(); e.pause(reason); save(); publish()
    }
    fun pause() { allowed.set(false); worker.post { pauseInternal("Pausa manual") } }
    fun resume() { worker.post {
        val e=engine ?: return@post
        val error=if(mode=="Simulación") { if(simSafe) null else "Restaurá primero el chat del simulador." } else verify()
        if(error!=null) { e.pause(error); publish(); return@post }
        if(e.uncertain) { publish(); return@post }
        if(mode!="Simulación") reader?.read()?.use { s -> tracker.baseline(Adapters.forPackage(s.frame.pkg)!!.messages(s.frame)) }
        e.resume(); allowed.set(!e.paused); save(); publish()
    } }
    fun confirmPublication() { worker.post { if(mode!="Simulación" && verify()!=null) return@post; engine?.confirmVisiblePublication(); save(); publish() } }
    fun stopNow() { allowed.set(false); worker.post { sender?.cancel(); engine?.stop(); save(); publish() } }
    fun simulate(raw:String,duplicate:Boolean=false,ambiguous:Boolean=false) { worker.post {
        if(mode!="Simulación") return@post
        raw.lineSequence().filter { it.isNotBlank() }.take(500).forEach { line ->
            val parts=line.split(':',limit=2); if(parts.size!=2) { diagnostic="Usá Nombre: respuesta."; return@forEach }
            val m=ChatMessage("sim-${++simSerial}",if(ambiguous) null else parts[0].trim(),parts[1].trim(),reliable=!ambiguous)
            engine?.receive(m); if(duplicate) engine?.receive(m)
        }; save(); publish()
    } }
    fun simulationFault(reason:String?) { worker.post { if(mode!="Simulación") return@post; simSafe=reason==null; if(reason!=null) pauseInternal(reason); else { diagnostic="Chat original recuperado. Reanudá manualmente."; publish() } } }
    fun database(action:(Repository)->String,done:(String)->Unit) { worker.post {
        val result=try { val r=repository ?: error("Base de datos no disponible"); action(r).also { bank=r.questions(); config=r.config(); templates=r.templates(); games=r.dao.games() } } catch(e:Exception) { e.message ?: "No se pudo guardar." }
        publish(); main.post { done(result) }
    } }
    fun resetScores() { stopNow(); worker.post { engine=null; repository?.dao?.delete("checkpoint"); idle="Puntuaciones reiniciadas"; publish() } }
    private fun save() { engine?.let { repository?.checkpoint(it) }; lastSave=now() }
    private var posted=false
    private fun publish() {
        val e=engine
        view=AppView(repository!=null,service!=null,mode,e?.status ?: idle,e?.round ?: 0,e?.plan?.size ?: config.rounds,e?.remaining() ?: 0,e?.active==true,e?.paused==true,e?.uncertain==true,binding?.title ?: if(mode=="Simulación") "Chat local de simulación" else "Sin chat confirmado",field,sender?.state ?: "Sin envíos",diagnostic,bank,config,templates,e?.ranking().orEmpty(),e?.history.orEmpty(),transcript.toList(),games,detected?.let { Adapters.forPackage(it.pkg)?.titles(it)?.map { n->n.text } }.orEmpty())
        if(!posted) { posted=true; main.post { posted=false; val latest=view; listeners.forEach { it(latest) }; service?.panel?.update(latest); notification(latest) } }
    }
    private var notificationKey=""
    private fun notification(v:AppView) {
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("partida","Partida y detención",NotificationManager.IMPORTANCE_LOW))
        val key="${v.active}|${v.paused}|${v.round}|${v.status}"
        if(notificationKey==key) return; notificationKey=key
        if(!v.active) { nm.cancel(10); return }
        val stop=PendingIntent.getBroadcast(context,1,Intent(context,StopReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(context,2,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=Notification.Builder(context,"partida").setSmallIcon(R.drawable.ic_notification).setContentTitle("Ayelén Trivia🏹 · ${v.round}/${v.total}").setContentText(v.status).setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null,"DETENER",stop).build()).build()
        try { nm.notify(10,n) } catch(_:SecurityException) { }
    }
}
