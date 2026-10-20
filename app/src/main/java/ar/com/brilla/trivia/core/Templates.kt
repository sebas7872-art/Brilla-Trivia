package ar.com.brilla.trivia.core

data class Template(val key: String, val label: String, val text: String, val enabled: Boolean = true, val order: Int = 0)
object Templates {
    val variables = setOf("pregunta","respuesta","numero","total","tiempo","acertantes","cantidad_acertantes","clasificacion","puntos","siguiente","ganador","ganadores","puntos_ganador","nombre","posicion")
    private val token = Regex("\\{([^{}]+)\\}")
    fun error(value: String): String? {
        if (value.length > 10_000) return "La plantilla supera los 10.000 caracteres."
        val bad = token.findAll(value).map { it.groupValues[1] }.firstOrNull { it !in variables }
        return bad?.let { "Variable desconocida: {$it}" }
    }
    fun render(text: String, values: Map<String,String>): String = token.replace(text) { values[it.groupValues[1]] ?: "" }.take(60_001)
    fun defaults(): List<Template> = listOf(
        Template("welcome","Bienvenida","🎮 ¡BIENVENIDOS A LA TRIVIA!"),
        Template("start","Inicio de partida","🏆 Hoy tendremos {total} preguntas.\n⭐ Cada acierto vale {puntos} punto(s).\n🎉 ¡MUCHA SUERTE!"),
        Template("question","Presentación de pregunta","❓ PREGUNTA {numero}/{total}\n\n{pregunta}"),
        Template("time","Tiempo disponible","⏳ ¡Tienen {tiempo} segundos!"),
        Template("closed","Tiempo terminado","⏰ ¡TIEMPO TERMINADO!"),
        Template("answer","Respuesta correcta","📚 La respuesta correcta era: {respuesta}"),
        Template("winners","Acertantes","🎉 RESPONDIERON BIEN:\n{acertantes}"),
        Template("nobody","Nadie acertó","❌ Esta vez nadie acertó."),
        Template("ranking","Clasificación por ronda","🏆 CLASIFICACIÓN GENERAL:\n{clasificacion}"),
        Template("next","Próxima pregunta","➡️ ¡Vamos por la pregunta {siguiente}!"),
        Template("pause","Pausa manual","⏸ Trivia pausada."),
        Template("resume","Reanudación","▶️ ¡Continuamos!"),
        Template("end","Fin de partida","🎊 ¡FINALIZÓ LA GRAN TRIVIA! 🏆\n👏 Completamos las {total} preguntas. ¡Gracias por participar!"),
        Template("final_ranking","Clasificación final","🌟 CLASIFICACIÓN FINAL 🌟\n{clasificacion}"),
        Template("champion","Campeón","🥁👑 ¡EL GRAN CAMPEÓN ES!\n🎉 {ganador}\n⭐ {puntos_ganador} puntos.\n💖 ¡NOS VEMOS EN LA PRÓXIMA TRIVIA!"),
        Template("tie","Campeones empatados","👑🏆 ¡TENEMOS CAMPEONES!\n{ganadores}\n🎉 ¡FELICITACIONES!"),
        Template("no_winner","Sin ganadores","💖 La partida terminó sin ganadores. ¡Gracias por jugar!"),
        Template("score_line","Estilo de clasificación","{posicion}. {nombre}: {puntos} punto(s)"),
        Template("winner_line","Estilo de acertantes","⭐ {nombre}"),
        Template("champion_line","Estilo de campeones","👑 {nombre}: {puntos} punto(s)")
    ).mapIndexed { index, t -> t.copy(order = index) }
    fun chunks(text: String, max: Int): List<String> {
        require(max >= 2); require(text.length <= 60_000) { "El anuncio es demasiado largo." }
        val result = mutableListOf<String>(); var rest = text.trim()
        while (rest.isNotEmpty()) {
            if (rest.codePointCount(0, rest.length) <= max) { result += rest; break }
            val end = rest.offsetByCodePoints(0,max)
            val boundary = rest.lastIndexOfAny(charArrayOf('\n',' '),end-1).takeIf { it > end/2 } ?: end
            result += rest.substring(0,boundary).trimEnd(); rest = rest.substring(boundary).trimStart()
            require(result.size < 128) { "El anuncio necesita demasiados mensajes." }
        }
        return result
    }
}
