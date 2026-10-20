package ar.com.brilla.trivia.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView

object Style {
    val bg = Color.parseColor("#0D0D0D")
    val card = Color.parseColor("#1A1A1A")
    val accent = Color.parseColor("#FFD700")
    val text = Color.parseColor("#F5F5F5")
    val muted = Color.parseColor("#AAAAAA")
    val danger = Color.parseColor("#FF4444")
    val ok = Color.parseColor("#44FF88")

    fun cardBg(): GradientDrawable = GradientDrawable().apply {
        setColor(card)
        cornerRadius = 24f
    }

    fun button(primary: Boolean = true): GradientDrawable = GradientDrawable().apply {
        setColor(if (primary) accent else Color.parseColor("#333333"))
        cornerRadius = 16f
    }

    fun applyTitle(tv: TextView) {
        tv.setTextColor(accent)
        tv.textSize = 18f
        tv.typeface = Typeface.DEFAULT_BOLD
    }

    fun applyBody(tv: TextView) {
        tv.setTextColor(text)
        tv.textSize = 14f
    }

    fun applyMuted(tv: TextView) {
        tv.setTextColor(muted)
        tv.textSize = 12f
    }
}
