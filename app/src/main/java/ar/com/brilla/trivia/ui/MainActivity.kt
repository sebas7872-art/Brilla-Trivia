package ar.com.brilla.trivia.ui

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import ar.com.brilla.trivia.*
import ar.com.brilla.trivia.core.*
import ar.com.brilla.trivia.data.Json

class MainActivity:Activity() {
    private val rt get()=(application as BrillaApp).runtime
    private lateinit var content:LinearLayout
    private lateinit var status:TextView
    private var section="Inicio"
    private var page=0
    private var simOutput:TextView?=null
    private var wasReady=false
    private var wasUncertain=false
    private val observer:(AppView)->Unit={ v ->
        status.text="${v.status}\nPregunta ${v.round}/${v.total} · ${(v.remaining+999)/1000}s"
        simOutput?.text=v.transcript.takeLast(8).joinToString("\n\n──────────\n\n")
        if((v.ready && !wasReady) || (v.uncertain!=wasUncertain && section=="Inicio")) { Handler(mainLooper).post { render() } }
        wasReady=v.ready; wasUncertain=v.uncertain
    }
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); build() }
    override fun onStart() { super.onStart(); rt.listeners.add(observer); observer(rt.view) }
    override fun onStop() { rt.listeners.remove(observer); super.onStop() }
    override fun onResume() { super.onResume(); if(::content.isInitialized) render() }
    private fun build() {
        val root=Style.column(this).apply { setPadding(Style.dp(this@MainActivity,18),Style.dp(this@MainActivity,12),Style.dp(this@MainActivity,18),0) }
        root.addView(Style.label(this,"✦ Ayelén Trivia🏹",27f,true))
        status=Style.label(this,"Preparando…",14f).also { root.addView(it) }
        val select=Spinner(this); val labels=listOf("Inicio","Preguntas y respuestas","Configuración del juego","Mensajes y anuncios","Clasificación","Simulación","Diagnóstico")
        select.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,labels)
        select.minimumHeight=Style.dp(this,48); root.addView(select)
        val scroll=ScrollView(this); content=Style.column(this); content.setPadding(0,0,0,Style.dp(this,28)); scroll.addView(content); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        select.onItemSelectedListener=object:AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent:AdapterView<*>?) {} ; override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { section=labels[position]; render() } }
        setContentView(root); render()
    }
    private fun title(text:String)=content.addView(Style.label(this,text,21f,true))
    private fun note(text:String)=content.addView(Style.label(this,text,14f))
    private fun button(text:String,primary:Boolean=false,click:()->Unit) { content.addView(Style.button(this,text,primary,click)) }
    private fun input(label:String,value:String,multi:Boolean=false):EditText { note(label); return Style.edit(this,value,multi).also { content.addView(it) } }
    private fun number(label:String,value:Long):EditText=input(label,value.toString()).apply { inputType=InputType.TYPE_CLASS_NUMBER }
    private fun check(label:String,value:Boolean):CheckBox=CheckBox(this).apply { text=label; isChecked=value; minHeight=Style.dp(this@MainActivity,48); setTextColor(Style.ink) }.also { content.addView(it) }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    private fun confirm(title:String,message:String,action:()->Unit) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Cancelar",null).setPositiveButton("Confirmar") { _,_->action() }.show() }
    private fun controls() {
        button("▶ Iniciar / reanudar",true) { if(rt.view.paused) rt.resume() else rt.start() }
        button("Ⅱ Pausar") { rt.pause() }; button("■ Detener") { rt.stopNow() }
        if(rt.view.uncertain) button("Confirmar que el último mensaje SÍ se publicó") {
            confirm("Envío incierto","Usá esta opción solo si comprobaste el mensaje publicado en el grupo original. No se reenvía. Si no estás seguro, detené la partida.") { rt.confirmPublication(); toast("Confirmación registrada; reanudá desde el grupo original.") }
        }
    }
    private fun render() {
        if(!::content.isInitialized) return
        content.removeAllViews(); simOutput=null; val v=rt.view
        when(section) {
            "Inicio" -> {
                title("Tu presentadora de trivia")
                note("Accesibilidad: ${if(v.connected) "conectada" else "desconectada"}\nPlataforma: ${v.mode}\nChat: ${v.chat}")
                button("Elegir plataforma · ${v.mode}") { val choices=arrayOf("Simulación","WhatsApp","TikTok","Chat de prueba"); AlertDialog.Builder(this).setTitle("Dónde jugar").setItems(choices) { _,i -> rt.setMode(choices[i]); Handler(mainLooper).postDelayed({ render() },250) }.show() }
                button("Conectar accesibilidad") { confirm("Acceso al chat abierto","Ayelén Trivia🏹 lee el texto y los nombres visibles del chat que confirmás y publica tus anuncios. No usa Internet, APIs de las plataformas ni servidores. Podés detenerla desde el panel o la notificación. Habilitá Ayelén Trivia🏹 en la pantalla siguiente.") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
                button("Permitir notificación con botón Detener") { if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),5) else toast("Las notificaciones ya están permitidas.") }
                button("Abrir panel flotante",true) { rt.service?.panel?.show() ?: toast("Primero conectá Accesibilidad.") }
                note("Para un grupo: elegí la plataforma, abrí el panel y entrá al chat. Tocá el campo de escritura y usá Detectar y confirmar chat en el panel.")
                controls()
                note("WhatsApp y TikTok pueden ocultar el campo, los remitentes o el botón Enviar. Ante dudas, Ayelén Trivia🏹 pausa; no pulsa a ciegas.")
                button("Actualizar estado") { render() }
            }
            "Preguntas y respuestas" -> {
                title("${v.questions.size} preguntas guardadas")
                val raw=input("Pegá un bloque A= pregunta / B= respuesta. Alternativas: B= París || Paris", "",true)
                button("Importar y agregar",true) { val text=raw.text.toString(); rt.database({ r -> val parsed=Importer.parse(text,r.questions()); r.add(parsed.questions); "Agregadas: ${parsed.questions.size}\nDuplicadas: ${parsed.duplicates}\n${parsed.errors.joinToString("\n")}" }) { message -> AlertDialog.Builder(this).setTitle("Importación").setMessage(message).setPositiveButton("Listo") { _,_->render() }.show() } }
                button("Restaurar seis preguntas de ejemplo") { rt.database({ r -> val parsed=Importer.parse(Importer.sample,r.questions()); r.add(parsed.questions); "Ejemplos agregados sin borrar el banco." }) { toast(it); render() } }
                button("Eliminar todas") { confirm("Vaciar banco","Se eliminarán todas las preguntas guardadas.") { rt.database({ it.dao.clearQuestions(); "Banco vacío." }) { toast(it); render() } } }
                val pages=((v.questions.size+29)/30).coerceAtLeast(1); page=page.coerceIn(0,pages-1)
                note("Página ${page+1}/$pages")
                v.questions.drop(page*30).take(30).forEach { q -> button(q.text+"\n→ "+q.answers.joinToString(" / ")) { editQuestion(q) } }
                if(page>0) button("Página anterior") { page--; render() }; if(page+1<pages) button("Página siguiente") { page++; render() }
            }
            "Configuración del juego" -> {
                title("Configuración del juego"); val c=v.config
                note("Los cambios se aplican en la próxima partida.")
                title("Participación de la anfitriona")
                val hostPlays=check("La anfitriona también juega",c.hostPlays)
                val hostName=input("Nombre de la anfitriona",c.hostName)
                note("Cuando está activada, los mensajes propios escritos manualmente se atribuyen a este nombre. Los anuncios automáticos nunca suman puntos.")
                val rounds=number("Cantidad de preguntas (1–1000)",c.rounds.toLong())
                val quick=LinearLayout(this); listOf(10,20,30,50).forEach { n -> quick.addView(Style.button(this,n.toString()) { rounds.setText(n.toString()) }.apply { layoutParams=LinearLayout.LayoutParams(0,-2,1f) }) }; content.addView(quick)
                val time=number("Segundos para responder (10, 20, 30 u otro entre 1–300)",c.answerMs/1000)
                val gap=number("Segundos entre preguntas (0–300)",c.intervalMs/1000)
                val points=number("Puntos máximos por respuesta (1–100, default 15)",c.points.toLong())
                val send=number("Segundos mínimos entre publicaciones (2–60)",c.sendIntervalMs/1000)
                val length=number("Máximo de caracteres por publicación (100–4000)",c.messageLength.toLong())
                val random=check("Orden aleatorio",c.random); val repeat=check("Repetir preguntas al agotar el banco",c.repeat)
                val exhaust=check("No repetir hasta agotar cada ciclo",c.exhaustFirst); val shuffle=check("Volver a mezclar en los siguientes ciclos",c.randomCycles)
                val multiple=check("Permitir varios acertantes",c.multipleWinners); val ranking=check("Clasificación después de cada pregunta",c.showRanking)
                val accents=check("Ignorar tildes",c.ignoreAccents)
                val top=number("Posiciones: 3, 5, 10 o 0 para completa",c.rankingSize.toLong())
                button("Guardar configuración",true) {
                    try {
                        val updated=GameConfig(rounds.text.toString().toInt(),time.text.toString().toLong()*1000,gap.text.toString().toLong()*1000,points.text.toString().toInt(),random.isChecked,repeat.isChecked,shuffle.isChecked,exhaust.isChecked,multiple.isChecked,ranking.isChecked,top.text.toString().toInt(),accents.isChecked,send.text.toString().toLong()*1000,length.text.toString().toInt(),hostPlays.isChecked,hostName.text.toString().ifBlank { "Ayelén🏹" })
                        val err=updated.error(); if(err!=null) toast(err) else rt.database({ it.save(updated); "Configuración guardada." }) { toast(it); render() }
                    } catch(_:Exception) { toast("Revisá los números ingresados.") }
                }
                note("La puntuación depende de la velocidad: más rápido, más puntos (máximo el valor de arriba, mínimo 1). Por defecto 15.")
            }
            "Mensajes y anuncios" -> {
                title("Tu estilo, tus anuncios")
                note("Editá texto, emojis, orden y activación. Variables: "+Templates.variables.joinToString(" ") { "{$it}" })
                v.templates.sortedBy { it.order }.forEach { t -> button((if(t.enabled) "✓ " else "○ ")+t.label) { editTemplate(t) } }
                button("Restaurar plantillas") { confirm("Restaurar anuncios","Se reemplazarán tus modificaciones.") { rt.database({ it.save(Templates.defaults()); "Plantillas restauradas." }) { toast(it); render() } } }
                note("La pausa de seguridad no publica mensajes.")
            }
            "Clasificación" -> {
                title("Puntuación acumulada")
                if(v.ranking.isEmpty()) note("Todavía no hay puntos.")
                v.ranking.forEach { note("${it.rank}. ${it.name}: ${it.points} punto(s)") }
                v.history.forEach { note("${it.number}. ${it.question}\nRespuesta: ${it.answer}\nAcertaron: ${it.winners.joinToString().ifEmpty { "Nadie" }}") }
                button("Reiniciar puntuaciones") { confirm("Reiniciar","Se detendrá la partida actual. El historial guardado se conserva.") { rt.resetScores(); Handler(mainLooper).postDelayed({render()},300) } }
                title("Partidas guardadas")
                v.games.forEach { g -> val state=runCatching { Json.state(g.state) }.getOrNull(); button("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(g.date))} · ${state?.history?.size ?: 0} rondas") { AlertDialog.Builder(this).setTitle("Partida guardada").setMessage(state?.scores?.entries?.sortedByDescending { it.value }?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty().ifEmpty { "Sin puntuaciones" }).setPositiveButton("Listo",null).show() } }
                button("Actualizar clasificación") { render() }
            }
            "Simulación" -> {
                title("Probá sin enviar al chat")
                note("Usa el mismo motor y cronómetro. Elegí Simulación antes de iniciar.")
                button("Elegir Simulación") { rt.setMode("Simulación"); toast("Si hay una partida activa, detenela primero.") }
                controls()
                val messages=input("Una línea por mensaje: Nombre: respuesta","Pedro: Londres\nPedro: París\nMaría: París",true)
                val duplicate=check("Simular eventos duplicados",false); val ambiguous=check("Simular remitente ambiguo",false)
                button("Introducir respuestas",true) { rt.simulate(messages.text.toString(),duplicate.isChecked,ambiguous.isChecked) }
                button("Simular cambio de grupo") { rt.simulationFault("Esta no es la ventana donde comenzó la partida.") }
                button("Simular pérdida del campo") { rt.simulationFault("Campo de escritura no disponible.") }
                button("Volver al chat original") { rt.simulationFault(null) }
                title("Publicaciones locales")
                simOutput=Style.label(this,v.transcript.takeLast(8).joinToString("\n\n──────────\n\n"),15f).also { content.addView(it) }
            }
            else -> {
                title("Diagnóstico")
                note("Servicio: ${v.connected}\nModo: ${v.mode}\nChat: ${v.chat}\nCampo: ${v.field}\nEnvío: ${v.sender}\nEstado: ${v.status}\n${v.diagnostic}")
                note("Solo se observan mensajes visibles y accesibles. No se guarda una copia del chat. No se adjudican puntos a remitentes ambiguos.")
                button("Actualizar") { render() }
                button("Desactivar accesibilidad") { rt.stopNow(); rt.service?.disableSelf() }
                button("Eliminar datos locales") { confirm("Eliminar todos los datos","Se borrarán preguntas, ajustes, plantillas y partidas.") { rt.stopNow(); (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData() } }
            }
        }
        observer(rt.view)
    }
    private fun editQuestion(q:Question) {
        val box=Style.column(this).apply { setPadding(24,8,24,8) }; val text=Style.edit(this,q.text,true); val answer=Style.edit(this,q.answers.joinToString(" || ")); box.addView(text); box.addView(answer)
        val d=AlertDialog.Builder(this).setTitle("Editar pregunta").setView(box).setNegativeButton("Cancelar",null).setNeutralButton("Eliminar") { _,_->rt.database({it.dao.deleteQuestion(q.id);"Pregunta eliminada."}) {toast(it);render()} }.setPositiveButton("Guardar",null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val parsed=Importer.parse("A= ${text.text}\nB= ${answer.text}",rt.view.questions.filter { it.id!=q.id })
            if(parsed.questions.size!=1 || parsed.errors.isNotEmpty()) toast(parsed.errors.firstOrNull() ?: "Pregunta duplicada o inválida.")
            else rt.database({ it.add(listOf(parsed.questions.single().copy(id=q.id))); "Pregunta guardada." }) { toast(it); d.dismiss(); render() }
        } }; d.show()
    }
    private fun editTemplate(t:Template) {
        val box=Style.column(this).apply { setPadding(24,8,24,8) }; val text=Style.edit(this,t.text,true); val enabled=CheckBox(this).apply { this.text="Activada"; isChecked=t.enabled }; val order=Style.edit(this,t.order.toString()).apply { inputType=InputType.TYPE_CLASS_NUMBER }
        box.addView(text); box.addView(enabled); box.addView(Style.label(this,"Orden de esta sección")); box.addView(order)
        val preview=Style.label(this,"",14f); box.addView(Style.button(this,"Vista previa") { val values=Templates.variables.associateWith { key -> when(key){"pregunta"->"¿Cuál es la capital de Francia?";"respuesta"->"París";"ganador","nombre"->"Pedro";"clasificacion","ganadores"->"1. Pedro: 3 puntos";"acertantes"->"⭐ Pedro\n⭐ María";else->"10"} }; preview.text=Templates.render(text.text.toString(),values) }); box.addView(preview)
        val scroll=ScrollView(this); scroll.addView(box)
        val d=AlertDialog.Builder(this).setTitle(t.label).setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value=text.text.toString(); val err=Templates.error(value); val n=order.text.toString().toIntOrNull()
            if(err!=null || n==null || n !in 0..999 || (t.key.endsWith("_line") && value.length>500)) toast(err ?: "Orden inválido o estilo de línea demasiado largo (máximo 500).")
            else { val updated=t.copy(text=value,enabled=enabled.isChecked,order=n); rt.database({r->r.save(rt.view.templates.map { if(it.key==t.key) updated else it });"Plantilla guardada."}) {toast(it);d.dismiss();render()} }
        } }; d.show()
    }
}
