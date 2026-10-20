package ar.com.brilla.trivia.core

import java.util.ArrayDeque

data class EngineState(
    val config: GameConfig, val plan: List<Question>, val templates: List<Template>,
    val phase: Phase, val index: Int, val scores: Map<String,Int>, val history: List<RoundResult>,
    val winners: List<String>, val remainingMs: Long, val queued: List<Publication>,
    val flight: Publication?, val serial: Long, val seen: List<String>, val uncertain: Boolean,
    val notice: String, val startedAt: Long
)

/** All mutations belong to one background thread. A clock and an acknowledged transport are injected. */
class TriviaEngine(val config: GameConfig, val plan: List<Question>, val templates: List<Template>, private val clock: () -> Long) {
    var phase = Phase.WELCOME; private set
    var index = -1; private set
    var paused = false; private set
    var reason = ""; private set
    var uncertain = false; private set
    val scores = linkedMapOf<String,Int>()
    val history = mutableListOf<RoundResult>()
    val winners = linkedSetOf<String>()
    private val seen = linkedSetOf<String>()
    private val queue = ArrayDeque<Publication>()
    var flight: Publication? = null; private set
    private var serial = 0L
    private var deadline = 0L
    private var frozen = 0L
    private var nextSend = 0L
    private var startedAt = clock()
    private var notice: String = ""
    val active get() = phase != Phase.FINISHED && phase != Phase.STOPPED
    val current get() = plan.getOrNull(index)
    val round get() = (index+1).coerceAtLeast(0)
    val status get() = if (paused) "Pausado · $reason" else phase.label
    fun remaining(): Long = if (paused) frozen else (deadline-clock()).coerceAtLeast(0)
    init { require(config.error() == null); require(plan.isNotEmpty()); enqueue(listOf("welcome","start")) }

    fun ranking(): List<Score> {
        val sorted = scores.entries.sortedWith(compareByDescending<Map.Entry<String,Int>> { it.value }.thenBy { normalized(it.key) })
        var last = -1; var rank = 0
        return sorted.mapIndexed { i,e -> if (e.value != last) { rank=i+1; last=e.value }; Score(e.key,e.value,rank) }
    }
    fun champions(): List<Score> = ranking().let { rows -> rows.filter { it.points == rows.firstOrNull()?.points && it.points > 0 } }
    private fun style(key: String, fallback: String, values: Map<String,String>) = Templates.render(templates.find { it.key==key }?.text ?: fallback, values)
    fun values(): Map<String,String> {
        val all = ranking(); val ranked = if(config.rankingSize == 0) all else all.take(config.rankingSize)
        val champions = champions()
        return mapOf(
            "pregunta" to (current?.text ?: ""), "respuesta" to (current?.answers?.firstOrNull() ?: ""),
            "numero" to round.toString(), "total" to plan.size.toString(), "tiempo" to (config.answerMs/1000).toString(),
            "puntos" to config.points.toString(), "siguiente" to (round+1).toString(),
            "cantidad_acertantes" to winners.size.toString(),
            "acertantes" to winners.joinToString("\n") { style("winner_line","⭐ {nombre}",mapOf("nombre" to it)) },
            "clasificacion" to (ranked.joinToString("\n") { style("score_line","{posicion}. {nombre}: {puntos}",mapOf("nombre" to it.name,"posicion" to it.rank.toString(),"puntos" to it.points.toString())) }.ifEmpty { "Todavía no hay puntos." }),
            "ganador" to (champions.firstOrNull()?.name ?: ""), "puntos_ganador" to (champions.firstOrNull()?.points?.toString() ?: "0"),
            "ganadores" to champions.joinToString("\n") { style("champion_line","👑 {nombre}: {puntos}",mapOf("nombre" to it.name,"puntos" to it.points.toString())) }
        )
    }
    private fun enqueue(keys: List<String>) {
        try {
            val values = values()
            val text = templates.filter { it.key in keys && it.enabled }.sortedBy { it.order }.joinToString("\n\n") { Templates.render(it.text,values) }
            for (part in Templates.chunks(text,config.messageLength)) {
                check(queue.size < 128 && serial < 10_000) { "Límite de publicaciones alcanzado." }
                queue.add(Publication(++serial,part))
            }
        } catch (e: Exception) { stop(e.message ?: "Error en el anuncio.") }
    }
    fun tick(): Publication? {
        if (!active || paused) return null
        val now = clock()
        if (now-startedAt > 14_400_000) { pause("La sesión alcanzó 4 horas. Revisá el teléfono."); startedAt=now; return null }
        if (phase == Phase.ANSWERS && now >= deadline) finishRound()
        if (phase == Phase.GAP && now >= deadline) nextQuestion()
        if (flight == null && queue.isEmpty() && notice.isEmpty()) advance()
        if (flight == null && queue.isEmpty() && notice.isNotEmpty()) { notice=""; deadline=now+frozen }
        if (!active || paused || flight != null || now < nextSend || queue.isEmpty()) return null
        return queue.removeFirst().also { flight=it }
    }
    private fun advance() {
        when (phase) {
            Phase.WELCOME -> nextQuestion()
            Phase.QUESTION -> { phase=Phase.ANSWERS; deadline=clock()+config.answerMs; seen.clear() }
            Phase.RESULT -> if (round >= plan.size) { phase=Phase.FINAL; enqueue(listOf("end","final_ranking",when(champions().size){0->"no_winner";1->"champion";else->"tie"})) } else { phase=Phase.GAP; deadline=clock()+config.intervalMs }
            Phase.FINAL -> { phase=Phase.FINISHED; deadline=0 }
            else -> Unit
        }
    }
    private fun nextQuestion() {
        index++; winners.clear(); seen.clear(); deadline=0
        if (index >= plan.size) { stop("Límite de rondas alcanzado."); return }
        phase=Phase.QUESTION; enqueue(listOf("question","time"))
    }
    private fun finishRound() {
        history += RoundResult(round,current!!.text,current!!.answers.first(),winners.toList())
        phase=Phase.RESULT; deadline=0
        val keys = mutableListOf("closed","answer",if(winners.isEmpty()) "nobody" else "winners")
        if(config.showRanking) keys += "ranking"
        if(round < plan.size) keys += "next"
        enqueue(keys)
    }
    fun receive(message: ChatMessage): Boolean {
        if (paused || phase != Phase.ANSWERS || clock() >= deadline || message.own || !message.reliable) return false
        val sender=message.sender?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 } ?: return false
        if (seen.size >= 8192) { pause("Demasiados mensajes en una ronda."); return false }
        if (!seen.add(message.id) || sender in winners || (!config.multipleWinners && winners.isNotEmpty())) return false
        if (current!!.answers.none { normalized(it,config.ignoreAccents)==normalized(message.text,config.ignoreAccents) }) return false
        if (scores.size >= 1000 && sender !in scores) { pause("Límite de participantes alcanzado."); return false }
        winners += sender; scores[sender]=(scores[sender] ?: 0)+config.points
        return true
    }
    fun acknowledge(id: Long, confirmed: Boolean, error: String="No se pudo confirmar la publicación.") {
        if(flight?.id != id) return
        if(!confirmed) { uncertain=true; pause(error); return }
        flight=null; uncertain=false; nextSend=clock()+config.sendIntervalMs
    }
    fun pause(message: String="Pausa manual") {
        if (!active) return
        if(!paused) frozen=remaining()
        paused=true; reason=message
    }
    fun resume(): Boolean {
        if (!paused || uncertain) return false
        paused=false; reason=""; deadline=clock()+frozen; nextSend=clock()+config.sendIntervalMs
        return true
    }
    /** A manual acknowledgement never resends the uncertain message. */
    fun confirmVisiblePublication() { flight?.let { acknowledge(it.id,true) } }
    fun stop(message: String="Detenido por vos") { phase=Phase.STOPPED; reason=message; paused=false; queue.clear(); flight=null; uncertain=false; deadline=0 }
    fun snapshot(): EngineState = EngineState(config,plan,templates,phase,index,scores.toMap(),history.toList(),winners.toList(),remaining(),queue.toList(),flight,serial,seen.toList(),uncertain,notice,startedAt)
    companion object {
        fun restore(s: EngineState, clock:()->Long): TriviaEngine = TriviaEngine(s.config,s.plan,s.templates,clock).apply {
            phase=s.phase; index=s.index; scores.clear(); scores.putAll(s.scores); history.clear(); history.addAll(s.history)
            winners.clear(); winners.addAll(s.winners); frozen=s.remainingMs; deadline=clock()+frozen
            queue.clear(); queue.addAll(s.queued); flight=s.flight; serial=s.serial; seen.clear(); seen.addAll(s.seen)
            uncertain=s.uncertain || flight != null; notice=s.notice; startedAt=clock()
            if(active) { paused=true; reason=if(uncertain) "Partida recuperada con un envío incierto. Confirmá el mensaje visible o detené." else "Partida recuperada. Volvé a confirmar el chat antes de reanudar." }
        }
    }
}
