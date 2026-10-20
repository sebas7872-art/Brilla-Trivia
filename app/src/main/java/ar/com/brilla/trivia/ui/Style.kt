package ar.com.brilla.trivia.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

object Style {
    val ink=Color.rgb(61,40,63); val rose=Color.rgb(170,48,109); val pale=Color.rgb(250,231,244); val bg=Color.rgb(255,248,252)
    fun dp(c:Context,n:Int)=(n*c.resources.displayMetrics.density).toInt()
    fun round(color:Int,radius:Float=22f)=GradientDrawable().apply { setColor(color); cornerRadius=radius }
    fun label(c:Context,text:String,size:Float=16f,bold:Boolean=false)=TextView(c).apply { this.text=text; textSize=size; setTextColor(ink); if(bold) setTypeface(null,Typeface.BOLD); setPadding(0,dp(c,6),0,dp(c,6)) }
    fun button(c:Context,text:String,primary:Boolean=false,click:()->Unit)=Button(c).apply {
        this.text=text; isAllCaps=false; textSize=14f; minHeight=dp(c,48); setTextColor(if(primary) Color.WHITE else ink)
        background=round(if(primary) rose else pale,dp(c,16).toFloat()); setPadding(dp(c,10),dp(c,6),dp(c,10),dp(c,6))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(c,4),0,dp(c,4)) }; setOnClickListener { click() }
    }
    fun edit(c:Context,value:String,multiline:Boolean=false)=EditText(c).apply { setText(value); textSize=16f; setTextColor(ink); setPadding(dp(c,12),dp(c,10),dp(c,12),dp(c,10)); background=round(Color.WHITE); minHeight=dp(c,48); if(multiline) { minLines=4; maxLines=12; gravity=android.view.Gravity.TOP; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE } else { setSingleLine(true) }; layoutParams=LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(c,4),0,dp(c,8)) } }
    fun column(c:Context)=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL }
}
