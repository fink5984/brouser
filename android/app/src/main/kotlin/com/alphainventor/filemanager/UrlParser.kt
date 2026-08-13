package com.alphainventor.filemanager

import java.net.URLEncoder

enum class SearchEngine(val id: String, val label: String) {
    GOOGLE("google", "Google"),
    BING("bing", "Bing"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo");

    fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return when (this) {
            GOOGLE -> "https://www.google.com/search?q=$encoded"
            BING -> "https://www.bing.com/search?q=$encoded"
            DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded"
        }
    }

    companion object {
        fun fromId(id: String): SearchEngine = entries.firstOrNull { it.id == id } ?: GOOGLE
    }
}

/**
 * Turns whatever the user typed into the address bar into either a URL to
 * navigate to, or a search query to run through the configured engine.
 * Mirrors the heuristic every mainstream mobile browser omnibox uses.
 */
object UrlParser {
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val IPV4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?$")
    private val DOMAIN_LIKE = Regex(
        "^([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(:\\d+)?(/.*)?$",
    )

    sealed class Input {
        data class Url(val url: String) : Input()
        data class Search(val query: String) : Input()
    }

    fun parse(rawInput: String, searchEngine: SearchEngine): Input {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return Input.Search("")

        if (SCHEME_PREFIX.containsMatchIn(trimmed) ||
            trimmed.startsWith("about:") ||
            trimmed.startsWith("data:")
        ) {
            return Input.Url(trimmed)
        }

        if (looksLikeUrl(trimmed)) {
            return Input.Url("https://$trimmed")
        }

        return Input.Search(trimmed)
    }

    private fun looksLikeUrl(input: String): Boolean {
        if (input.contains(' ')) return false
        if (input == "localhost" || input.startsWith("localhost:") || input.startsWith("localhost/")) return true
        if (IPV4.matches(input)) return true

        // Require a dot with a plausible TLD so single words ("weather",
        // "espn") fall through to search rather than a bogus navigation.
        return DOMAIN_LIKE.matches(input)
    }
}
