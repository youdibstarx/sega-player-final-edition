package com.segaiptv.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Reader
import java.io.FilterReader
import java.io.InputStreamReader
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

internal object XmltvGuide {
    internal fun timestamp(value: String): Long? = runCatching {
        val parts = value.trim().split(Regex("\\s+"))
        val digits = parts[0]
        val pattern = when (digits.length) {
            14 -> "yyyyMMddHHmmss"
            12 -> "yyyyMMddHHmm"
            else -> return null
        }
        val local = LocalDateTime.parse(digits, DateTimeFormatter.ofPattern(pattern))
        val offset = if (parts.size > 1) ZoneOffset.of(parts[1]) else ZoneOffset.UTC
        local.toInstant(offset).toEpochMilli()
    }.getOrNull()

    internal fun parse(reader: Reader, channelId: String, now: Long): List<EpgProgramme> {
        if (channelId.isBlank()) return emptyList()
        val results = mutableListOf<EpgProgramme>()
        var matching = false
        var start: Long? = null
        var end: Long? = null
        var field = ""
        val title = StringBuilder()
        val description = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource("".reader())
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                if (qName == "programme") {
                    matching = attrs.getValue("channel") == channelId
                    start = timestamp(attrs.getValue("start").orEmpty())
                    end = timestamp(attrs.getValue("stop").orEmpty())
                    title.setLength(0); description.setLength(0)
                }
                field = if (matching && qName in listOf("title", "desc")) qName else ""
            }
            override fun characters(ch: CharArray, offset: Int, length: Int) {
                val target = when (field) { "title" -> title; "desc" -> description; else -> return }
                if (target.length < 20_000) target.append(ch, offset, minOf(length, 20_000 - target.length))
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                field = ""
                if (qName == "programme") {
                    val a = start; val b = end
                    if (matching && a != null && b != null && b > now && b > a) {
                        results += EpgProgramme(title.toString().trim(), description.toString().trim(), a, b)
                        // Keep only the nearest twelve events even if a source is not ordered.
                        results.sortBy { it.start }
                        if (results.size > 12) results.removeAt(results.lastIndex)
                    }
                    matching = false
                }
            }
        }
        val factory = SAXParserFactory.newInstance()
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        // No internal entity expansion. XMLTV's ordinary external DOCTYPE remains supported.
        val xml = factory.newSAXParser().xmlReader
        xml.entityResolver = handler
        xml.contentHandler = handler
        xml.setProperty("http://xml.org/sax/properties/lexical-handler", object : org.xml.sax.ext.DefaultHandler2() {
            override fun startEntity(name: String) {
                if (name !in listOf("[dtd]", "amp", "lt", "gt", "apos", "quot")) error("XML entities are not supported")
            }
        })
        xml.parse(InputSource(reader))
        return results.distinctBy { it.start to it.title }
    }

    suspend fun download(url: String, channelId: String, now: Long): List<EpgProgramme> {
        val coroutine = currentCoroutineContext()
        val connection = NetworkClient.open(URL(url))
        connection.connectTimeout = 12_000; connection.readTimeout = 20_000
        try {
            check(connection.responseCode in 200..299) { "EPG unavailable" }
            return BufferedInputStream(connection.inputStream).use { input ->
                input.mark(2)
                val gz = input.read() == 0x1f && input.read() == 0x8b
                input.reset()
                val stream = if (gz) GZIPInputStream(input) else input
                InputStreamReader(stream, Charsets.UTF_8).use { raw ->
                    val bounded = object : FilterReader(raw) {
                        var count = 0L
                        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                            coroutine.ensureActive()
                            val n = super.read(buffer, offset, length)
                            if (n > 0) count += n
                            check(count <= 128L * 1024 * 1024) { "EPG source too large" }
                            return n
                        }
                    }
                    parse(bounded, channelId, now)
                }
            }
        } finally { connection.disconnect() }
    }
}

