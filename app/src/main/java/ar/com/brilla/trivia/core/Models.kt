package ar.com.brilla.trivia.core

data class Question(val id: String, val text: String, val answers: List<String>)
data class GameConfig(
    val rounds: Int = 10,
    val answerSeconds: Int = 10,
    val pauseSeconds: Int = 5,
    val points: Int = 1,
    val random: Boolean = true,
    val multi: Boolean = true,
    val ranking: String = "top5",
    val repeat: Boolean = true
) {
    fun error(): String? = when {
        rounds !in 1..1000 -> "Rondas fuera de rango (1-1000)"
        answerSeconds !in 5..120 -> "Tiempo de respuesta fuera de rango"
        pauseSeconds !in 0..60 -> "Pausa entre rondas fuera de rango"
        points !in 1..100 -> "Puntos por acierto fuera de rango"
        else -> null
    }
}
data class Template(val key: String, val text: String, val enabled: Boolean = true, val order: Int = 0)
data class Score(val name: String, val points: Int, val rank: Int = 0)
data class RoundResult(val round: Int, val question: String, val winners: List<String>, val none: Boolean)
data class ChatMessage(val id: String, val sender: String?, val text: String, val own: Boolean, val reliable: Boolean = true)
data class Publication(val id: String, val text: String)
data class GameRecord(val id: Long, val date: String, val rounds: Int, val players: Int)
enum class Phase { Idle, Question, Waiting, Announce, Ranking, Finished }
