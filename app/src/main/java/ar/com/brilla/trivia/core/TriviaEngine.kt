package ar.com.brilla.trivia.core

import java.util.ArrayDeque

data class EngineState(
    val config: GameConfig, val plan: List<Question>, val templates: List<Template>,
    val phase: Phase, val index: Int, val scores: Map<String,Int>, val history: List<RoundResult>,
    val winners: List<String>, val remainingMs: Long, val queued: List<Publication>,
    val flight: Publication?, val serial: Long, val uncertain: Boolean, val notice: String,
    val paused: Boolean, val reason: String, val startedAt: Long
)

class TriviaEngine(
    private val config: GameConfig,
    private val plan: List<Question>,
    private val templates: List<Template>,
    private val clock: () -> Long
) {
    var phase = Phase.WELCOME; private set
    var index = 0; private set
    var scores = linkedMapOf<String,Int>(); private set
    var history = mutableListOf<RoundResult>(); private set
    var winners = mutableListOf<String>(); private set
    var remainingMs = 0L; private set
    var queued = ArrayDeque<Publication>(); private set
    var flight: Publication? = null; private set
    var serial = 0L; private set
    var uncertain = false; private set
    var notice = ""; private set
    var paused = false; private set
    var reason = ""; private set
    var startedAt = clock(); private set
    var active = true; private set

    val status: String get() = when {
        !active -> "Detenido"
        paused -> reason.ifBlank { "Pausado" }
        else -> phase.label
    }
    val round: Int get() = (index + 1).coerceAtMost(plan.size)
    fun remaining() = remainingMs.coerceAtLeast(0)
    fun ranking(): List<Score> {
        val sorted = scores.entries.sortedWith(compareByDescending<Map.Entry<String,Int>> { it.value }.thenBy { it.key })
        var rank = 0; var last = Int.MAX_VALUE; var seen = 0
        return sorted.map { (name, pts) ->
            seen++; if (pts != last) { rank = seen; last = pts }
            Score(name, pts, rank)
        }.let { list -> if (config.rankingSize == 0) list else list.take(config.rankingSize) }
    }
    fun receive(m: ChatMessage) {
        if (!active || paused || phase != Phase.ANSWERS || m.own || !m.reliable || m.sender.isNullOrBlank()) return
        val q = plan.getOrNull(index) ?: return
        val ok = q.answers.any { normalized(it, config.ignoreAccents) == normalized(m.text, config.ignoreAccents) }
        if (!ok) return
        if (m.sender in winners) return
        winners += m.sender!!
        scores[m.sender] = (scores[m.sender] ?: 0) + config.points
        if (!config.multipleWinners) remainingMs = 0
    }
    fun tick(): Publication? {
        if (!active || paused) return null
        if (flight != null) return null
        if (queued.isNotEmpty()) {
            val p = queued.removeFirst()
            flight = p
            return p
        }
        when (phase) {
            Phase.WELCOME -> {
                enqueue("welcome"); enqueue("start")
                phase = Phase.QUESTION; remainingMs = 0
            }
            Phase.QUESTION -> {
                val q = plan.getOrNull(index) ?: run { finish(); return null }
                val vals = mapOf("pregunta" to q.text, "numero" to "${index+1}", "total" to "${plan.size}", "tiempo" to "${config.answerMs/1000}", "puntos" to "${config.points}")
                enqueue("question", vals); enqueue("time", vals)
                phase = Phase.ANSWERS; remainingMs = config.answerMs; winners.clear()
            }
            Phase.ANSWERS -> {
                remainingMs -= 100
                if (remainingMs <= 0) {
                    phase = Phase.RESULT
                    val q = plan[index]
                    val vals = mapOf("respuesta" to q.answers.first(), "acertantes" to winners.joinToString("\n") { Templates.render(templates.find { it.key=="winner_line" }?.text ?: "⭐ {nombre}", mapOf("nombre" to it)) }, "cantidad_acertantes" to "${winners.size}")
                    enqueue("closed"); enqueue("answer", vals)
                    if (winners.isEmpty()) enqueue("nobody") else enqueue("winners", vals)
                    if (config.showRanking) enqueue("ranking", mapOf("clasificacion" to ranking().joinToString("\n") { Templates.render(templates.find { t -> t.key=="score_line" }?.text ?: "{posicion}. {nombre}: {puntos}", mapOf("posicion" to "${it.rank}", "nombre" to it.name, "puntos" to "${it.points}")) }))
                    history += RoundResult(index+1, q.text, q.answers.first(), winners.toList())
                    remainingMs = config.intervalMs
                    phase = Phase.GAP
                }
            }
            Phase.GAP -> {
                remainingMs -= 100
                if (remainingMs <= 0) {
                    index++
                    if (index >= plan.size) { phase = Phase.FINAL; remainingMs = 0 }
                    else { enqueue("next", mapOf("siguiente" to "${index+1}")); phase = Phase.QUESTION }
                }
            }
            Phase.FINAL -> {
                enqueue("end", mapOf("total" to "${plan.size}"))
                if (config.showRanking) enqueue("final_ranking", mapOf("clasificacion" to ranking().joinToString("\n") { Templates.render(templates.find { t -> t.key=="score_line" }?.text ?: "{posicion}. {nombre}: {puntos}", mapOf("posicion" to "${it.rank}", "nombre" to it.name, "puntos" to "${it.points}")) }))
                val top = ranking().filter { it.rank == 1 }
                when {
                    top.isEmpty() -> enqueue("no_winner")
                    top.size == 1 -> enqueue("champion", mapOf("ganador" to top[0].name, "puntos_ganador" to "${top[0].points}"))
                    else -> enqueue("tie", mapOf("ganadores" to top.joinToString("\n") { Templates.render(templates.find { t -> t.key=="champion_line" }?.text ?: "👑 {nombre}: {puntos}", mapOf("nombre" to it.name, "puntos" to "${it.points}")) }))
                }
                phase = Phase.FINISHED; active = false
            }
            else -> {}
        }
        return null
    }
    fun acknowledge(id: Long, ok: Boolean, message: String = "") {
        if (flight?.id != id) return
        if (!ok) { uncertain = true; notice = message; paused = true; reason = message }
        flight = null
    }
    fun confirmVisiblePublication() { if (uncertain) { uncertain = false; notice = ""; paused = false; reason = "" } }
    fun pause(r: String) { paused = true; reason = r }
    fun resume() { if (!uncertain) { paused = false; reason = "" } }
    fun stop() { active = false; phase = Phase.STOPPED; queued.clear(); flight = null }
    private fun enqueue(key: String, values: Map<String,String> = emptyMap()) {
        val t = templates.filter { it.key == key && it.enabled }.minByOrNull { it.order } ?: return
        val text = Templates.render(t.text, values)
        Templates.chunks(text, config.messageLength).forEach { chunk ->
            serial++; queued.addLast(Publication(serial, chunk))
        }
    }
    private fun finish() { phase = Phase.FINISHED; active = false }
    fun snapshot() = EngineState(config, plan, templates, phase, index, scores.toMap(), history.toList(), winners.toList(), remainingMs, queued.toList(), flight, serial, uncertain, notice, paused, reason, startedAt)
    companion object {
        fun restore(s: EngineState, clock: () -> Long): TriviaEngine {
            val e = TriviaEngine(s.config, s.plan, s.templates, clock)
            e.phase = s.phase; e.index = s.index; e.scores.putAll(s.scores); e.history.addAll(s.history)
            e.winners.addAll(s.winners); e.remainingMs = s.remainingMs; e.queued.addAll(s.queued)
            e.flight = s.flight; e.serial = s.serial; e.uncertain = s.uncertain || s.flight != null; e.notice = s.notice; e.startedAt = clock()
            if (e.active) { e.paused = true; e.reason = if (e.uncertain) "Partida recuperada con un envío incierto. Confirmá el mensaje visible o detené." else "Partida recuperada. Volvé a confirmar el chat antes de reanudar." }
            return e
        }
    }
}
