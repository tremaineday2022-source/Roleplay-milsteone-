package com.roleplay.ai.domain

import com.roleplay.ai.data.db.LorebookEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object LoreMatcher {

    fun keywords(entry: LorebookEntity): List<String> =
        entry.keywords.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Enabled entries with at least one keyword found (case-insensitively) in [text]. */
    fun match(entries: List<LorebookEntity>, text: String): List<LorebookEntity> {
        if (text.isBlank()) return emptyList()
        return entries.filter { entry ->
            entry.enabled && entry.content.isNotBlank() &&
                keywords(entry).any { text.contains(it, ignoreCase = true) }
        }
    }
}

/** Pulls JSON out of model replies that may be wrapped in code fences, prose, or think tags. */
object JsonExtract {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val think = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val fence = Regex("```[a-zA-Z0-9_-]*[ \\t]*\\r?\\n?([\\s\\S]*?)```")

    fun stripFences(raw: String): String {
        val text = think.replace(raw, "").trim()
        return fence.find(text)?.groupValues?.get(1)?.trim() ?: text
    }

    fun obj(raw: String): JsonObject? = parse(raw, '{', '}') as? JsonObject

    /** A list of strings from a JSON array, or from the first array inside a JSON object. */
    fun stringArray(raw: String): List<String> {
        val element = parse(raw, '[', ']') ?: parse(raw, '{', '}') ?: return emptyList()
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element.values.firstOrNull { it is JsonArray } as? JsonArray
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            .filter { it.isNotEmpty() }
    }

    fun string(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    private fun parse(raw: String, open: Char, close: Char): JsonElement? {
        val text = stripFences(raw)
        runCatching { json.parseToJsonElement(text) }.getOrNull()?.let { parsed ->
            val matches = if (open == '{') parsed is JsonObject else parsed is JsonArray
            if (matches) return parsed
        }
        val start = text.indexOf(open)
        val end = text.lastIndexOf(close)
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()
    }
}

object TextJoin {
    /** Appends a model continuation to existing text, adding a space only where one is missing. */
    fun join(existing: String, continuation: String): String {
        if (existing.isEmpty() || continuation.isEmpty()) return existing + continuation
        val last = existing.last()
        val first = continuation.first()
        val needsSpace = !last.isWhitespace() && !first.isWhitespace() &&
            (first.isLetterOrDigit() || first in "\"“*([") &&
            last !in "([“-—"
        return if (needsSpace) "$existing $continuation" else existing + continuation
    }

    /** Lower-cased text with punctuation removed, for duplicate checks. */
    fun normalize(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    fun isDuplicate(candidate: String, existing: Collection<String>): Boolean {
        val c = normalize(candidate)
        if (c.isEmpty()) return true
        return existing.any { other ->
            val o = normalize(other)
            o == c || (o.length >= 12 && c.contains(o)) || (c.length >= 12 && o.contains(c))
        }
    }
}
