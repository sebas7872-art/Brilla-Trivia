package ar.com.brilla.trivia.access

import ar.com.brilla.trivia.core.ChatMessage
import java.security.MessageDigest

data class UiNode(val path: String,val parent: String?,val id: String,val text: String,val desc: String,val type: String,val editable: Boolean,val focused: Boolean,val enabled: Boolean,val clickable: Boolean,val y: Int)
data class Frame(val pkg: String,val window: Int,val nodes: List<UiNode>)
data class Binding(val pkg: String,val titleId: String,val title: String,val fieldId: String,val fieldType: String,val window: Int)
data class Detected(val binding: Binding?,val reason: String,val titles: List<UiNode>,val field: UiNode?)
data class Adapter(val label:String,val packages:Set<String>,val titleIds:Set<String>,val textIds:Set<String>,val senderIds:Set<String>,val rowIds:Set<String>,val ownIds:Set<String>,val sendIds:Set<String>) {
    private fun local(id:String)=id.substringAfterLast('/')
    private fun localLower(id:String)=local(id).lowercase()
    fun titles(f:Frame): List<UiNode> {
        val byId = f.nodes.filter { local(it.id) in titleIds && it.text.isNotBlank() && !it.editable }
        if (byId.isNotEmpty()) return byId.distinctBy { it.id to it.text }
        return f.nodes.filter {
            !it.editable && it.text.isNotBlank() && it.text.length in 2..60 && it.y < 400 &&
            !it.type.contains("Button", ignoreCase=true) && !it.clickable
        }.distinctBy { it.text }.take(5)
    }
    fun fields(f:Frame)=f.nodes.filter { it.editable && it.enabled }
    fun candidateFields(f:Frame): List<UiNode> {
        val all = fields(f)
        val focused = all.filter { it.focused }
        if (focused.size == 1) return focused
        val withId = all.filter { it.id.isNotBlank() }
        return if (withId.isNotEmpty()) withId else all
    }
    fun inspect(f:Frame,b:Binding): String? = when {
        f.pkg != b.pkg -> "Esta no es la aplicación donde comenzó la partida."
        f.window != b.window -> "Cambió la ventana. Volvé a confirmar el grupo original."
        f.nodes.count { (it.id==b.titleId || b.titleId.isBlank()) && it.text==b.title && !it.editable } < 1 -> "Esta no es la ventana donde comenzó la partida. Volvé al grupo original."
        field(f,b) == null -> "Campo de escritura no disponible."
        else -> null
    }
    fun field(f:Frame,b:Binding)=f.nodes.singleOrNull { it.id==b.fieldId && it.type==b.fieldType && it.editable && it.enabled }
        ?: if (b.fieldId.isBlank()) f.nodes.singleOrNull { it.editable && it.enabled && it.type==b.fieldType }
        else null
    fun send(f:Frame): UiNode? {
        val known=f.nodes.filter { it.enabled && it.clickable && local(it.id) in sendIds }
        if(known.size==1) return known.single()
        if(known.isNotEmpty()) return null
        return f.nodes.filter { it.enabled && it.clickable && (it.desc.trim().lowercase() in setOf("enviar","send","enviar mensaje","send message")) }.singleOrNull()
    }

    private fun looksOwn(node: UiNode, children: List<UiNode>): Boolean {
        if (children.any { local(it.id) in ownIds }) return true
        if (node.desc.startsWith("Tú: ") || node.desc.startsWith("You: ")) return true
        val blob = (node.id + " " + node.desc + " " + node.type + " " + children.joinToString(" ") { it.id + " " + it.desc + " " + it.type }).lowercase()
        val ownKeywords = listOf("outgoing", "message_out", "msg_out", "self", "mine", "sent_by_me", "is_me", "own_message", "from_me", "is_outgoing", "right_message", "my_message")
        if (ownKeywords.any { it in blob }) return true
        if (listOf("tú", "tu:", "you:", "me:").any { it in node.desc.lowercase() }) return true
        return false
    }

    fun messages(f:Frame): List<ChatMessage> {
        val byPath = f.nodes.associateBy { it.path }
        fun descendants(path: String) = f.nodes.filter { it.path == path || it.path.startsWith("$path/") }
        val result = mutableListOf<ChatMessage>()
        val usedBodies = mutableSetOf<String>()

        // Primary: known text ids
        f.nodes.filter { local(it.id) in textIds && !it.editable && it.text.isNotBlank() }.forEach { body ->
            if (body.path in usedBodies) return@forEach
            usedBodies += body.path
            result += extractMessage(f, body, byPath, ::descendants, strict = true)
        }

        // TikTok / sparse fallback: only if primary found nothing or we are on TikTok
        if (result.isEmpty() || label == "TikTok") {
            f.nodes.filter {
                !it.editable && it.enabled && it.text.isNotBlank() && it.text.length in 1..500 &&
                !it.type.contains("EditText", ignoreCase = true) &&
                !it.type.contains("Button", ignoreCase = true) &&
                local(it.id) !in titleIds && it.y > 80 &&
                it.path !in usedBodies
            }.forEach { body ->
                if (body.path in usedBodies) return@forEach
                usedBodies += body.path
                result += extractMessage(f, body, byPath, ::descendants, strict = false)
            }
        }
        return result.distinctBy { it.id }
    }

    private fun extractMessage(
        f: Frame, body: UiNode,
        byPath: Map<String, UiNode>,
        descendants: (String) -> List<UiNode>,
        strict: Boolean
    ): ChatMessage {
        // Walk up looking for a tight message container
        var container = body
        var parent = body.parent
        var depth = 0
        var bestContainer = body
        while (parent != null && depth++ < 5) {
            val p = byPath[parent] ?: break
            val kids = descendants(p.path)
            val hasSender = kids.any { local(it.id) in senderIds || localLower(it.id).contains("name") || localLower(it.id).contains("nick") }
            val isRow = local(p.id) in rowIds || kids.size in 2..12
            if (isRow && (hasSender || looksOwn(p, kids))) {
                bestContainer = p
                break
            }
            if (kids.size > 20) break // too broad, stop
            container = p
            parent = p.parent
        }
        val children = descendants(bestContainer.path)
        val strongOwn = looksOwn(bestContainer, children) || looksOwn(body, children)
        // Sender names only from nodes that are close to the body (similar y, or known sender ids)
        val nameCandidates = children.filter {
            (local(it.id) in senderIds || localLower(it.id).contains("name") || localLower(it.id).contains("nick")) &&
            it.text.isNotBlank() && it.text != body.text &&
            kotlin.math.abs(it.y - body.y) < 180
        }.map { it.text.ifBlank { it.desc }.trim() }.filter { it.isNotBlank() }.distinct()

        // On TikTok, own messages in groups typically omit the sender name.
        // If no name is found and there is no strong own marker, treat as inferred own
        // so the host can score when hostPlays is on. Auto texts are still filtered later.
        val inferredOwn = !strongOwn && nameCandidates.isEmpty() && label == "TikTok"
        val own = strongOwn || inferredOwn
        val sender = if (nameCandidates.size == 1 && !own) nameCandidates.single() else null
        val reliable = when {
            strongOwn -> true
            inferredOwn -> true // host path will still exclude autos
            sender != null && nameCandidates.size == 1 -> true
            else -> false
        }
        // Identity independent of y: text + sender/own + package. MessageTracker handles "newness".
        val identity = "${f.pkg}|${sender ?: if (own) "OWN" else "UNK"}|${body.text.trim()}"
        return ChatMessage(hash(identity), sender, body.text, own, reliable)
    }

    fun diagnosticSummary(f: Frame, expectedText: String? = null): String {
        val editables = f.nodes.count { it.editable && it.enabled }
        val clickableSend = f.nodes.count { it.clickable && it.enabled && (local(it.id) in sendIds || it.desc.trim().lowercase() in setOf("enviar","send","enviar mensaje","send message")) }
        val idSamples = f.nodes.asSequence()
            .filter { it.id.isNotBlank() }
            .map { local(it.id) }
            .distinct()
            .take(20)
            .joinToString(",")
        val exactMatches = if (expectedText != null) f.nodes.count { !it.editable && it.text == expectedText } else -1
        val msgs = messages(f)
        return "pkg=${f.pkg.take(40)} editables=$editables sendBtns=$clickableSend msgs=${msgs.size} own=${msgs.count { it.own }} exact=${if (exactMatches >= 0) exactMatches else "-"} ids=[$idSamples]"
    }
}
fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
object Adapters {
    val whatsapp=Adapter("WhatsApp",setOf("com.whatsapp","com.whatsapp.w4b"),setOf("conversation_contact_name","toolbar_title"),setOf("message_text"),setOf("participant_name","group_participant_name","sender_name"),setOf("message_container","message_row","row"),setOf("message_out","outgoing","message_status","status"),setOf("send","send_button"))
    val tiktok=Adapter("TikTok",setOf("com.zhiliaoapp.musically","com.ss.android.ugc.trill"),setOf("chat_title","conversation_title","toolbar_title"),setOf("message_text","chat_message_text"),setOf("sender_name","user_name","nickname"),setOf("message_row","message_container","chat_message"),setOf("outgoing_message","message_status"),setOf("send","send_button","send_message"))
    val fixture=Adapter("Chat de prueba",setOf("ar.com.brilla.fixture"),setOf("chat_title"),setOf("message_text"),setOf("sender_name"),setOf("message_row"),setOf("outgoing"),setOf("send"))
    val all=listOf(whatsapp,tiktok,fixture)
    fun forPackage(pkg:String)=all.firstOrNull { pkg in it.packages }
}
class MessageTracker {
    private val seen = linkedSetOf<String>()
    private var initialized = false
    fun baseline(messages: List<ChatMessage>) {
        seen.clear()
        seen.addAll(messages.map { it.id })
        initialized = true
    }
    fun poll(messages: List<ChatMessage>): List<ChatMessage> {
        if (!initialized) { baseline(messages); return emptyList() }
        val fresh = messages.filter { it.id !in seen }
        seen.addAll(fresh.map { it.id })
        if (seen.size > 16384) throw IllegalStateException("Demasiados mensajes para deduplicar con seguridad.")
        return fresh.distinctBy { it.id }
    }
    fun rebaseline(messages: List<ChatMessage>) { baseline(messages) }
}
