package io.github.custom.lyrictranslator

import android.net.Uri
import android.os.Looper
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AutoLyricTranslator {

    private val translationCache = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newCachedThreadPool()
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
            val cached = translationCache.get(trackKey)
            if (cached != null) {
                if (cached.isNotBlank()) {
                    json.put("translationLyric", cached)
                    return Pair(json.toString(), true)
                }
                return Pair(rawJson, false)
            }

            if (!inFlight.add(trackKey)) {
                return Pair(rawJson, false)
            }

            val future = executor.submit(Callable {
                try {
                    val translated = translateLrcBlock(primaryLyric, "ru") ?: ""
                    if (translated.isNotBlank()) {
                        translationCache.put(trackKey, translated)
                        XposedBridge.log("LyricAutoTranslator: SUCCESS $trackKey (${translated.lines().size} lines)")
                    }
                    translated
                } finally {
                    inFlight.remove(trackKey)
                }
            })

            val isMainThread = Looper.myLooper() == Looper.getMainLooper()
            val resultText = if (!isMainThread) {
                try {
                    future.get(5, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    ""
                }
            } else {
                ""
            }

            if (resultText.isNotBlank()) {
                json.put("translationLyric", resultText)
                Pair(json.toString(), true)
            } else {
                Pair(rawJson, false)
            }
        } catch (e: Exception) {
            XposedBridge.log("LyricAutoTranslator error: ${e.message}")
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
        val translatedText = requestGoogleGtx(joinedPayload, targetLang)
            ?: requestMyMemory(joinedPayload, targetLang)
            ?: return null

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

    private fun requestGoogleGtx(text: String, targetLang: String): String? {
        return try {
            val urlStr = Uri.Builder()
                .scheme("https")
                .authority("://googleapis.com")
                .appendPath("translate_a")
                .appendPath("single")
                .appendQueryParameter("client", "gtx")
                .appendQueryParameter("sl", "auto")
                .appendQueryParameter("tl", targetLang)
                .appendQueryParameter("dt", "t")
                .appendQueryParameter("q", text)
                .build()
                .toString()

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (conn.responseCode != 200) {
                XposedBridge.log("LyricAutoTranslator Google HTTP: ${conn.responseCode}")
                return null
            }

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
            XposedBridge.log("LyricAutoTranslator Google error: ${e.message}")
            null
        }
    }

    private fun requestMyMemory(text: String, targetLang: String): String? {
        return try {
            val urlStr = Uri.Builder()
                .scheme("https")
                .authority("api.mymemory.translated.net")
                .appendPath("get")
                .appendQueryParameter("q", text.take(480))
                .appendQueryParameter("langpair", "en|$targetLang")
                .build()
                .toString()

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (conn.responseCode != 200) return null
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            json.getJSONObject("responseData").getString("translatedText")
        } catch (e: Exception) {
            XposedBridge.log("LyricAutoTranslator MyMemory error: ${e.message}")
            null
        }
    }
}
