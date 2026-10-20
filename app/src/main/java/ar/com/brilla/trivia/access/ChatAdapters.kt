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

    /** Heurística segura de mensaje propio sin inventar resource-ids. */
    private fun looksOwn(node: UiNode, children: List<UiNode>): Boolean {
        if (children.any { local(it.id) in ownIds }) return true
        if (node.desc.startsWith("Tú: ") || node.desc.startsWith("You: ")) return true
        val blob = (node.id + " " + node.desc + " " + children.joinToString(" ") { it.id + " " + it.desc }).lowercase()
        return listOf("outgoing", "message_out", "msg_out", "self", "mine", "sent_by_me", "is_me", "own_message").any { it in blob }
            || listOf("tú", "tu:", "you:", "me:").any { it in node.desc.lowercase() }
    }

    fun messages(f:Frame): List<ChatMessage> {
        val byPath=f.nodes.associateBy { it.path }
        fun descendants(path:String)=f.nodes.filter { it.path==path || it.path.startsWith("$path/") }
        val result=mutableListOf<ChatMessage>()
        val seenBodies=mutableSetOf<String>()

        // 1) Camino principal por resource-id conocidos
        f.nodes.filter { local(it.id) in textIds && !it.editable && it.text.isNotBlank() }.forEach { body ->
            var row=body; var parent=body.parent; var depth=0
            while(parent!=null && depth++ < 5) {
                val p=byPath[parent] ?: break
                val children=descendants(p.path)
                if(local(p.id) in rowIds) { row=p; break }
                if(children.count { local(it.id) in senderIds }==1 && children.none { local(it.id) in rowIds }) row=p
                parent=p.parent
            }
            val children=descendants(row.path)
            val names=children.filter { local(it.id) in senderIds }.map { it.text.ifBlank { it.desc }.trim() }.filter { it.isNotBlank() }.distinct()
            val own=looksOwn(row, children)
            val safe=names.size==1 && children.count { local(it.id) in rowIds } <= 1
            val sender=if(safe) names.single() else null
            val stamp=children.filter { local(it.id) in setOf("date","timestamp","message_time") }.joinToString("|") { it.text }
            val identity="${f.pkg}|${sender.orEmpty()}|$own|${body.text}|$stamp|${body.path}"
            seenBodies += body.text to body.path
            result += ChatMessage(hash(identity),sender,body.text,own,safe || own)
        }

        // 2) Fallback: nodos de texto no editables cuando el árbol no expone los ids esperados (p.ej. TikTok)
        if (result.isEmpty() || label == "TikTok") {
            f.nodes.filter {
                !it.editable && it.enabled && it.text.isNotBlank() && it.text.length in 1..500 &&
                !it.type.contains("EditText", ignoreCase=true) &&
                !it.type.contains("Button", ignoreCase=true) &&
                local(it.id) !in titleIds &&
                it.y > 80
            }.forEach { body ->
                val key = body.text to body.path
                if (key in seenBodies) return@forEach
                var row = body
                var parent = body.parent
                var depth = 0
                while (parent != null && depth++ < 6) {
                    val p = byPath[parent] ?: break
                    row = p
                    parent = p.parent
                }
                val children = descendants(row.path)
                val own = looksOwn(row, children) || looksOwn(body, children)
                val stamp = children.filter { localLower(it.id).contains("time") || localLower(it.id).contains("date") }.joinToString("|") { it.text }
                val identity = "${f.pkg}|fallback|$own|${body.text}|$stamp|${body.path}"
                seenBodies += key
                // reliable solo si parece propio o tiene un único remitente claro
                val names = children.filter { local(it.id) in senderIds || localLower(it.id).contains("name") || localLower(it.id).contains("nick") }
                    .map { it.text.ifBlank { it.desc }.trim() }.filter { it.isNotBlank() && it != body.text }.distinct()
                val safe = own || names.size == 1
                result += ChatMessage(hash(identity), if (names.size == 1) names.single() else null, body.text, own, safe)
            }
        }
        return result.distinctBy { it.id }
    }

    /** Resumen seguro del árbol para diagnóstico (sin textos de terceros ni datos personales). */
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
        val msgCount = messages(f).size
        val ownCount = messages(f).count { it.own }
        return "pkg=${f.pkg.take(40)} editables=$editables sendBtns=$clickableSend msgs=$msgCount own=$ownCount exact=${if (exactMatches >= 0) exactMatches else "-"} ids=[$idSamples]"
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
/** Baseline, two stable observations and bounded digest storage; raw messages are never persisted. */
class MessageTracker {
    private val seen=linkedSetOf<String>(); private var previous=emptySet<String>(); private var initialized=false
    fun baseline(messages:List<ChatMessage>) { seen.clear(); seen.addAll(messages.map { it.id }); previous=seen.toSet(); initialized=true }
    fun poll(messages:List<ChatMessage>):List<ChatMessage> {
        if(!initialized) { baseline(messages); return emptyList() }
        val ids=messages.map { it.id }.toSet()
        val fresh=messages.filter { it.id in previous && it.id !in seen }
        seen.addAll(fresh.map { it.id }); previous=ids
        if(seen.size>16384) throw IllegalStateException("Demasiados mensajes para deduplicar con seguridad.")
        return fresh.distinctBy { it.id }
    }
}
