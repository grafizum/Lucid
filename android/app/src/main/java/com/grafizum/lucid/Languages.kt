package com.grafizum.lucid

import java.util.Locale

/**
 * Target languages offered in the picker. All of these have on-device
 * translation models in ML Kit, so translation works offline once the model
 * has been downloaded.
 */
object Languages {

    data class Lang(val code: String, val label: String, val native: String)

    val targets = listOf(
        Lang("en", "English", "English"),
        Lang("es", "Spanish", "Español"),
        Lang("fr", "French", "Français"),
        Lang("de", "German", "Deutsch"),
        Lang("pt", "Portuguese", "Português"),
        Lang("it", "Italian", "Italiano"),
        Lang("nl", "Dutch", "Nederlands"),
        Lang("pl", "Polish", "Polski"),
        Lang("tr", "Turkish", "Türkçe"),
        Lang("ru", "Russian", "Русский"),
        Lang("uk", "Ukrainian", "Українська"),
        Lang("ar", "Arabic", "العربية"),
        Lang("hi", "Hindi", "हिन्दी"),
        Lang("ja", "Japanese", "日本語"),
        Lang("ko", "Korean", "한국어"),
        Lang("zh", "Chinese (Simplified)", "简体中文"),
        Lang("id", "Indonesian", "Indonesia"),
        Lang("vi", "Vietnamese", "Tiếng Việt"),
        Lang("th", "Thai", "ไทย"),
        Lang("sv", "Swedish", "Svenska"),
    )

    fun label(code: String): String =
        targets.firstOrNull { it.code == code }?.label
            ?: runCatching { Locale.forLanguageTag(code).displayLanguage }.getOrNull()
            ?: code

    /** The name as it fits a chip: any parenthesised qualifier left off. */
    fun short(code: String): String = label(code).substringBefore(" (")

    fun nativeLabel(code: String): String =
        targets.firstOrNull { it.code == code }?.native ?: label(code)
}
