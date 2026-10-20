package ar.com.brilla.trivia.core

import java.text.Normalizer
import java.util.Locale

data class Question(val id: Long, val text: String, val answers: List<String>)
data class GameConfig(
    val rounds: Int = 10, val answerMs: Long = 10_000, val intervalMs: Long = 5_000,
    val points: Int = 1, val random: Boolean = true, val repeat: Boolean = true,
    val randomCycles: Boolean = true, val exhaustFirst: Boolean = true,
    val multipleWinners: Boolean = true, val showRanking: Boolean = true,
    val rankingSize: Int = 10, val ignoreAccents: Boolean = true,
    val sendIntervalMs: Long = 3_000, val messageLength: Int = 500,
    val hostPlays: Boolean = false, val hostName: String = "Ayelén🏹"
) {
    fun error(): String? = when {
        rounds !in 1..1000 -> "Elegí entre 1 y 1000 preguntas."
        answerMs !in 1000..300_000 -> "El tiempo de respuesta debe ser de 1 a 300 segundos."
        intervalMs !in 0..300_000 -> "El intervalo debe ser de 0 a 300 segundos."
        points !in 1..100 -> "Los puntos deben estar entre 1 y 100."
        sendIntervalMs !in 2000..60_000 -> "Separá los envíos entre 2 y 60 segundos."
        messageLength !in 100..4000 -> "El límite por mensaje debe ser de 100 a 4000 caracteres."
        rankingSize !in listOf(0,3,5,10) -> "Clasificación: completa, top 3, 5 o 10."
        hostName.isBlank() || hostName.length > 40 -> "El nombre de la anfitriona debe tener entre 1 y 40 caracteres."
        else -> null
    }
}
fun normalized(text: String, accents: Boolean = true): String {
    val value = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    return if (accents) Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") else value
}
data class ChatMessage(val id: String, val sender: String?, val text: String, val own: Boolean = false, val reliable: Boolean = true)
data class Score(val name: String, val points: Int, val rank: Int)
data class RoundResult(val number: Int, val question: String, val answer: String, val winners: List<String>, val pointsByName: Map<String, Int> = emptyMap())
enum class Phase(val label: String) {
    WELCOME("Preparando partida"), QUESTION("Publicando pregunta"), ANSWERS("Esperando respuestas"),
    RESULT("Publicando resultados"), GAP("Próxima pregunta"), FINAL("Publicando final"),
    FINISHED("Finalizado"), STOPPED("Detenido")
}
data class Publication(val id: Long, val text: String)
fun schedule(bank: List<Question>, c: GameConfig, random: kotlin.random.Random = kotlin.random.Random.Default): List<Question> {
    require(bank.isNotEmpty()) { "No hay preguntas válidas." }
    require(c.error() == null) { c.error()!! }
    val result = mutableListOf<Question>()
    while (result.size < c.rounds) {
        val cycle = if ((result.isEmpty() && c.random) || (result.isNotEmpty() && c.randomCycles && c.random)) bank.shuffled(random) else bank
        val wanted = minOf(c.rounds - result.size, bank.size)
        if (c.repeat && !c.exhaustFirst && c.random) repeat(wanted) { result += bank[random.nextInt(bank.size)] }
        else result += cycle.take(wanted)
        if (!c.repeat) break
    }
    return result
}
