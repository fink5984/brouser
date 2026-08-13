package com.alphainventor.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlParserTest {

    @Test
    fun `bare domain becomes an https url`() {
        val result = UrlParser.parse("openai.com", SearchEngine.GOOGLE)
        assertEquals(UrlParser.Input.Url("https://openai.com"), result)
    }

    @Test
    fun `domain with path becomes an https url`() {
        val result = UrlParser.parse("example.com/path?x=1", SearchEngine.GOOGLE)
        assertEquals(UrlParser.Input.Url("https://example.com/path?x=1"), result)
    }

    @Test
    fun `already-schemed url passes through untouched`() {
        val result = UrlParser.parse("http://example.com", SearchEngine.GOOGLE)
        assertEquals(UrlParser.Input.Url("http://example.com"), result)
    }

    @Test
    fun `https url passes through untouched`() {
        val result = UrlParser.parse("https://example.com/", SearchEngine.GOOGLE)
        assertEquals(UrlParser.Input.Url("https://example.com/"), result)
    }

    @Test
    fun `multi word phrase becomes a search`() {
        val result = UrlParser.parse("best hotels london", SearchEngine.GOOGLE)
        assertTrue(result is UrlParser.Input.Search)
        assertEquals("best hotels london", (result as UrlParser.Input.Search).query)
    }

    @Test
    fun `single word with no dot becomes a search`() {
        val result = UrlParser.parse("weather", SearchEngine.GOOGLE)
        assertTrue(result is UrlParser.Input.Search)
    }

    @Test
    fun `localhost is treated as a url`() {
        val result = UrlParser.parse("localhost:3000", SearchEngine.GOOGLE)
        assertEquals(UrlParser.Input.Url("https://localhost:3000"), result)
    }

    @Test
    fun `ipv4 address is treated as a url`() {
        val result = UrlParser.parse("192.168.1.1", SearchEngine.GOOGLE)
        assertEquals(UrlParser.Input.Url("https://192.168.1.1"), result)
    }

    @Test
    fun `search query is url-encoded and routed to the right engine`() {
        val google = UrlParser.parse("best hotels london", SearchEngine.GOOGLE)
        val bing = UrlParser.parse("best hotels london", SearchEngine.BING)
        val ddg = UrlParser.parse("best hotels london", SearchEngine.DUCKDUCKGO)

        val googleUrl = SearchEngine.GOOGLE.searchUrl((google as UrlParser.Input.Search).query)
        val bingUrl = SearchEngine.BING.searchUrl((bing as UrlParser.Input.Search).query)
        val ddgUrl = SearchEngine.DUCKDUCKGO.searchUrl((ddg as UrlParser.Input.Search).query)

        assertEquals("https://www.google.com/search?q=best+hotels+london", googleUrl)
        assertEquals("https://www.bing.com/search?q=best+hotels+london", bingUrl)
        assertEquals("https://duckduckgo.com/?q=best+hotels+london", ddgUrl)
    }

    @Test
    fun `blank input is treated as an empty search, not a crash`() {
        val result = UrlParser.parse("   ", SearchEngine.GOOGLE)
        assertTrue(result is UrlParser.Input.Search)
    }

    @Test
    fun `search engine id round trips`() {
        assertEquals(SearchEngine.BING, SearchEngine.fromId("bing"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromId("unknown-id"))
    }
}
