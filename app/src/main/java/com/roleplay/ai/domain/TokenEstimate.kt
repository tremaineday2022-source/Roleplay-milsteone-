package com.roleplay.ai.domain

import com.roleplay.ai.data.api.ChatMessage

/**
 * Rough token counts for showing cost. Real counts depend on the model's tokenizer; for
 * English text one token is about 4 characters (roughly three quarters of a word).
 */
object TokenEstimate {
    private const val CHARS_PER_TOKEN = 4
    private const val PER_MESSAGE = 4
    /** About what a high-detail image of the size the app sends costs on OpenAI-style models. */
    const val PER_IMAGE = 765

    fun text(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    fun request(messages: List<ChatMessage>): Int =
        messages.sumOf { text(it.content) + PER_MESSAGE + it.images.size * PER_IMAGE } + 3
}

/** Cuts a long document down to its latest words for writing mode. */
object DocumentWindow {
    const val OMITTED_MARKER = "[Earlier text omitted]\n\n"
    private val word = Regex("\\S+")

    fun wordCount(text: String): Int = word.findAll(text).count()

    /**
     * The document as it is when [maxWords] is 0 or it is short enough. Otherwise its last
     * [maxWords] words, starting at the next paragraph (or sentence) break where one is close,
     * prefixed with [OMITTED_MARKER].
     */
    fun tail(text: String, maxWords: Int): String {
        if (maxWords <= 0) return text
        val words = word.findAll(text).toList()
        if (words.size <= maxWords) return text
        var start = words[words.size - maxWords].range.first
        val paragraph = text.indexOf("\n\n", start)
        val sentence = Regex("[.!?…][\"”’)]?\\s+").find(text, start)
        start = when {
            paragraph in start until start + 1500 && paragraph < text.length - 2 -> paragraph + 2
            sentence != null && sentence.range.last < start + 600 && sentence.range.last < text.length - 1 ->
                sentence.range.last + 1
            else -> start
        }
        while (start < text.length && text[start].isWhitespace()) start++
        return OMITTED_MARKER + text.substring(start)
    }
}
