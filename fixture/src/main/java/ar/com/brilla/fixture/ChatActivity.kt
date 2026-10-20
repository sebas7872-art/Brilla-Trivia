package ar.com.brilla.fixture

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.*

/** Standalone, local test chat. No account, network, or third-party platform. */
class ChatActivity:Activity() {
    private lateinit var messages:LinearLayout
    private lateinit var scroll:ScrollView
    private var serial=0
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply { orientation=1; setPadding(18,18,18,18) }
        val title=TextView(this).apply { id=R.id.chat_title; text="Grupo de prueba Brilla"; textSize=22f }; root.addView(title)
        messages=LinearLayout(this).apply { orientation=1 }
        scroll=ScrollView(this); scroll.addView(messages); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val field=EditText(this).apply { id=R.id.message_field; hint="Mensaje"; setSingleLine(true); showSoftInputOnFocus=false }
        root.addView(field)
        fun button(label:String,action:()->Unit) { root.addView(Button(this).apply { text=label; isAllCaps=false; setOnClickListener { action() } }) }
        root.addView(Button(this).apply { id=R.id.send; text="Enviar"; contentDescription="Enviar"; setOnClickListener { if(field.text.isNotBlank()) { row(null,listOf(field.text.toString())); field.setText("") } } })
        button("Pedro responde París") { row("Pedro",listOf("Londres","París")) }
        button("María responde París") { row("María",listOf("París")) }
        button("Cambiar grupo") { title.text=if(title.text=="Grupo de prueba Brilla") "Otro grupo" else "Grupo de prueba Brilla" }
        button("Ocultar campo") { field.visibility=if(field.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        setContentView(root); row("María",listOf("Listos para jugar")); field.requestFocus()
    }
    private fun row(name:String?,texts:List<String>) {
        val box=LinearLayout(this).apply { orientation=1; id=R.id.message_row; setPadding(6,8,6,8) }
        box.addView(TextView(this).apply { id=if(name==null) R.id.outgoing else R.id.sender_name; text=name ?: "Tú"; textSize=14f })
        texts.forEach { text -> box.addView(TextView(this).apply { id=R.id.message_text; this.text=text; textSize=16f }) }
        box.addView(TextView(this).apply { id=R.id.timestamp; text="mensaje ${++serial}"; textSize=10f })
        messages.addView(box); while(messages.childCount>20) messages.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
