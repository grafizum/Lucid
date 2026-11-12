package com.grafizum.lucid

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/** The dark language list, shared by the in-app dialog and the overlay sheet. */
object LanguagePicker {

    /**
     * [title] heads the list; [includeAuto] adds an "Auto detect" row first,
     * used when picking the card's source language.
     */
    fun build(
        context: Context,
        current: String,
        title: String = "TRANSLATE INTO",
        includeAuto: Boolean = false,
        onPick: (Languages.Lang) -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_dark_bg)
            setPadding(dp(16), dp(16), dp(16), dp(10))
        }

        column.addView(
            TextView(context).apply {
                text = title
                setTextColor(context.getColor(R.color.sand_500))
                textSize = 9.5f
                letterSpacing = 0.18f
                typeface = ResourcesCompat.getFont(context, R.font.lucid_sans)
            },
        )

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val options = buildList {
            if (includeAuto) add(Languages.Lang("auto", "Auto detect", "Auto detect"))
            addAll(Languages.targets)
        }

        options.forEach { lang ->
            val selected = lang.code == current
            list.addView(
                TextView(context).apply {
                    text = if (selected) "${lang.native}  ·  ${lang.label}  ✓" else "${lang.native}  ·  ${lang.label}"
                    setTextColor(context.getColor(if (selected) R.color.clay_300 else R.color.cream_50))
                    textSize = 13.5f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    typeface = ResourcesCompat.getFont(context, R.font.lucid_sans)
                    Press.attach(this)
                    setOnClickListener { onPick(lang) }
                },
            )
        }

        column.addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(list)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(270)),
        )

        return column
    }
}
