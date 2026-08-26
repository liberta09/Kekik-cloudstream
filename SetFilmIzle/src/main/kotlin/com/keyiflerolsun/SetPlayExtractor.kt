package com.keyiflerolsun

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

open class SetPlay : ExtractorApi() {
    override val name = "SetPlay"
    override val mainUrl = "https://setplay.shop"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val response = app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            ),
            referer = referer
        )

        val html = response.text
        val cookies = response.headers.values("Set-Cookie")
            .joinToString("; ") { it.substringBefore(';') }

        // SetPlay/FirePlayer config can contain escaped JSON and nested objects.
        val config = Regex(
            """FirePlayer\s*\(.*?,\s*(\{.*?\})\s*,\s*(?:true|false)\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)

        if (config.isNullOrBlank()) {
            // Some versions expose the media URL directly in the page.
            val direct = Regex("""https?://[^\"'\\s]+\.(?:m3u8|mp4)(?:\?[^\"'\\s]*)?""", RegexOption.IGNORE_CASE)
                .find(html)?.value
            if (!direct.isNullOrBlank()) {
                callback(
                    newExtractorLink(name, name, direct, ExtractorLinkType.M3U8) {
                        headers = mapOf("Referer" to url, "Cookie" to cookies, "User-Agent" to userAgent)
                    }
                )
                return
            }
            throw ErrorLoadingException("SetPlay oynatıcı yapılandırması bulunamadı")
        }

        val json = try {
            JSONObject(config.replace("\\/", "/"))
        } catch (e: Exception) {
            Log.e("Kekik_SetPlay", "Player JSON parse hatası", e)
            throw ErrorLoadingException("SetPlay player verisi okunamadı")
        }

        val videoServer = json.optString("videoServer", "1")
        val videoUrl = json.optString("videoUrl", "").replace("\\/", "/")
        if (videoUrl.isBlank()) throw ErrorLoadingException("SetPlay video URL bulunamadı")

        val partKey = Uri.parse(url).getQueryParameter("partKey").orEmpty()
        val suffix = when {
            partKey.contains("turkcedublaj", true) -> "Dublaj"
            partKey.contains("turkcealtyazi", true) -> "Altyazı"
            partKey.isNotBlank() -> partKey
            else -> json.optString("title", "SetPlay")
        }

        val finalUrl = when {
            videoUrl.startsWith("http://") || videoUrl.startsWith("https://") -> {
                if (videoUrl.contains("?")) "$videoUrl& s=$videoServer".replace("& s=", "&s=")
                else "$videoUrl?s=$videoServer"
            }
            else -> "$mainUrl${if (videoUrl.startsWith('/')) videoUrl else "/$videoUrl"}?s=$videoServer"
        }

        Log.d("Kekik_$name", "SetPlay Final Link -> $finalUrl")
        callback(
            newExtractorLink(name, "$name - $suffix", finalUrl, ExtractorLinkType.M3U8) {
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "Referer" to url,
                    "Cookie" to cookies,
                    "User-Agent" to userAgent,
                    "Accept" to "*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                )
            }
        )
    }
}
