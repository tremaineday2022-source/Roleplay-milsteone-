package com.roleplay.ai.ui.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private val Emphasis = Regex("\\*\\*(.+?)\\*\\*|\\*([^*\\n]+?)\\*")

/** Renders `**bold**` (used for group-chat name prefixes) and `*italic*` roleplay actions. */
fun roleplayText(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in Emphasis.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        val bold = match.groups[1]?.value
        if (bold != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groups[2]?.value.orEmpty()) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
