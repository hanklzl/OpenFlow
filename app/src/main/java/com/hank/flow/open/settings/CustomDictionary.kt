package com.hank.flow.open.settings

object CustomDictionary {

    fun parse(raw: String): List<String> =
        normalize(raw.split('\n', ',', '，'))

    fun normalize(entries: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()
        entries.forEach { entry ->
            val normalized = normalizeEntry(entry)
            if (normalized.isEmpty()) return@forEach
            val key = normalized.lowercase()
            if (seen.add(key)) out.add(normalized)
        }
        return out
    }

    fun serialize(entries: List<String>): String =
        normalize(entries).joinToString(separator = "\n")

    fun add(entries: List<String>, rawEntry: String): List<String> =
        normalize(entries + parse(rawEntry))

    fun remove(entries: List<String>, entry: String): List<String> {
        val key = normalizeEntry(entry).lowercase()
        return normalize(entries).filterNot { it.lowercase() == key }
    }

    fun toWhisperPrompt(entries: List<String>): String? =
        normalize(entries)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = ", ")

    private fun normalizeEntry(entry: String): String =
        entry.trim().replace(Regex("\\s+"), " ")
}
