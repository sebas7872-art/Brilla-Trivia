package ar.com.brilla.trivia.core

data class ImportResult(val questions: List<Question>, val errors: List<String>, val duplicates: Int)
object Importer {
    val sample = """A= ¿Cuál es la capital de Francia?
B= París

A= ¿Cuántos días tiene una semana?
B= 7

A= ¿Qué planeta es conocido como el planeta rojo?
B= Marte

A= ¿Cuál es el océano más grande del mundo?
B= Pacífico

A= ¿Cuánto es 9 multiplicado por 8?
B= 72

A= ¿Quién escribió Don Quijote de la Mancha?
B= Miguel de Cervantes"""
    fun parse(input: String, existing: List<Question> = emptyList()): ImportResult {
        require(input.length <= 2_000_000) { "El bloque supera los 2 millones de caracteres." }
        val questions = mutableListOf<Question>(); val errors = mutableListOf<String>()
        val seen = existing.map { normalized(it.text) }.toMutableSet()
        var pending: String? = null; var duplicates = 0
        input.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isBlank()) return@forEachIndexed
            val match = Regex("^([AaBb])\\s*=\\s*(.*)$").matchEntire(line)
            if (match == null) { if (errors.size < 100) errors += "Línea ${index+1}: usá A= o B=."; return@forEachIndexed }
            val text = match.groupValues[2].trim()
            if (match.groupValues[1].equals("A", true)) {
                if (pending != null && errors.size < 100) errors += "Línea ${index+1}: falta la respuesta anterior."
                pending = text
            } else {
                val q = pending; pending = null
                val answers = text.split("||").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                if (q.isNullOrBlank() || answers.isEmpty() || q.length > 2000 || answers.any { it.length > 500 }) {
                    if (errors.size < 100) errors += "Línea ${index+1}: pareja incompleta o demasiado larga."
                } else if (!seen.add(normalized(q))) duplicates++
                else questions += Question(0, q, answers)
            }
        }
        if (pending != null && errors.size < 100) errors += "Falta B= para la última pregunta."
        return ImportResult(questions, errors, duplicates)
    }
}
