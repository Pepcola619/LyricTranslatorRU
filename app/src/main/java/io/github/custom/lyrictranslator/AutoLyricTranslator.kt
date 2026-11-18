package io.github.custom.lyrictranslator

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object AutoLyricTranslator {

    private val translationCache = ConcurrentHashMap<String, String>()
    private val lrcLineRegex = Regex("""^(\[\d{2}:\d{2}(?:\.\d{2,3})?\])(.*)$""")

    fun enrichLyricInfoJson(rawJson: String): Pair<String, Boolean> {
        return try {
            val json = JSONObject(rawJson)
            val existingTranslation = json.optString("translationLyric", "")
            val primaryLyric = json.optString("lyric", "")

            if (existingTranslation.isNotBlank()) {
                return Pair(rawJson, true)
            }
            if (primaryLyric.isBlank() || containsCyrillic(primaryLyric)) {
                return Pair(rawJson, false)
            }

            val trackKey = json.optString("trackKey", primaryLyric.hashCode().toString())
            val translatedLrc = translationCache.getOrPut(trackKey) {
                translateLrcBlock(primaryLyric, "ru") ?: ""
            }

            if (translatedLrc.isNotBlank()) {
                json.put("translationLyric", translatedLrc)
                Pair(json.toString(), true)
            } else {
                Pair(rawJson, false)
            }
        } catch (e: Exception) {
            Pair(rawJson, false)
        }
    }

    private fun containsCyrillic(text: String): Boolean {
        return text.any { it in '\u0400'..'\u04FF' }
    }

    private fun translateLrcBlock(lrc: String, targetLang: String): String? {
        val lines = lrc.lines()
        val timestamps = mutableListOf<String>()
        val textsToTranslate = mutableListOf<String>()

        for (line in lines) {
            val match = lrcLineRegex.find(line.trim())
            if (match != null) {
                val (timeTag, rawText) = match.destructured
                val lyricText = rawText.trim()
                if (lyricText.isNotEmpty() && !lyricText.startsWith("//")) {
                    timestamps.add(timeTag)
                    textsToTranslate.add(lyricText)
                }
            }
        }

        if (textsToTranslate.isEmpty()) return null

        val joinedPayload = textsToTranslate.joinToString("\n")
        val translatedText = requestGoogleTranslate(joinedPayload, targetLang) ?: return null
        val translatedLines = translatedText.lines()

        val resultBuilder = StringBuilder()
        for (i in timestamps.indices) {
            val trLine = translatedLines.getOrNull(i)?.trim().orEmpty()
            if (trLine.isNotEmpty()) {
                resultBuilder.append(timestamps.get(i)).append(trLine).append("\n")
            }
        }
        return resultBuilder.toString().trimEnd()
    }

    private fun requestGoogleTranslate(text: String, targetLang: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlStr = "https://googleapis.com"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (conn.responseCode != 200) return null

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val rootArray = JSONArray(response)
            val sentencesArray = rootArray.getJSONArray(0)

            val sb = StringBuilder()
            for (i in 0 until sentencesArray.length()) {
                val segment = sentencesArray.getJSONArray(i)
                sb.append(segment.getString(0))
            }
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }
}
