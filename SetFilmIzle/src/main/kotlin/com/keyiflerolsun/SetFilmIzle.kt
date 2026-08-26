package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.*

class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenenler",
        "${mainUrl}/tur/aile/" to "Aile",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/animasyon/" to "Animasyon",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/" to "Biyografi",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/fantastik/" to "Fantastik",
        "${mainUrl}/tur/gerilim/" to "Gerilim",
        "${mainUrl}/tur/gizem/" to "Gizem",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/romantik/" to "Romantik",
        "${mainUrl}/tur/savas/" to "Savaş",
        "${mainUrl}/tur/spor/" to "Spor",
        "${mainUrl}/tur/suc/" to "Suç",
        "${mainUrl}/tur/tarih/" to "Tarih",
        "${mainUrl}/tur/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.trimEnd('/')
        val url = if (page <= 1) request.data else "$baseUrl/page/$page/"
        val document = app.get(url, headers = requestHeaders, referer = mainUrl).document
        val home = document.select(".items .item, .items article, .items .item.tvshows, .items .item.movies, .row .item, article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val img = selectFirst("img")
        val title = selectFirst(".data h3, .data h2, .title, h3, h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null
        val posterUrl = fixUrlNull(img?.let {
            it.attr("data-src").takeIf(String::isNotBlank)
                ?: it.attr("data-lazy-src").takeIf(String::isNotBlank)
                ?: it.attr("data-original").takeIf(String::isNotBlank)
                ?: it.attr("src").takeIf(String::isNotBlank)
        })
        return if (href.contains("/dizi/"))
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> = try {
        val mainPage = app.get(mainUrl, headers = requestHeaders).document
        val nonce = Regex("""nonce:\s*['\"]([^'\"]+)['\"]""").find(mainPage.html())?.groupValues?.get(1).orEmpty()
        val search = app.post(
            url = "${mainUrl}/wp-admin/admin-ajax.php",
            headers = requestHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to mainUrl),
            data = mapOf("action" to "ajax_search", "nonce" to nonce, "search" to query)
        )
        val html = JSONObject(search.text).optString("html")
        if (html.isBlank()) emptyList() else Jsoup.parse(html)
            .select(".items .item, .items article, article.item, div.item, .row .item")
            .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    } catch (e: Exception) {
        Log.e("STF", "Arama hatası", e)
        emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = requestHeaders, referer = mainUrl).document
        val title = document.selectFirst(".sheader h1, .title h1, h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".sheader .poster img, .poster img, meta[property='og:image'], img")?.let { el ->
            if (el.tagName() == "meta") el.attr("content") else el.attr("data-src").takeIf(String::isNotBlank)
                ?: el.attr("data-lazy-src").takeIf(String::isNotBlank)
                ?: el.attr("data-original").takeIf(String::isNotBlank)
                ?: el.attr("src").takeIf(String::isNotBlank)
        })
        val description = document.selectFirst(".wp-content, .sheader .wp-content, meta[property='og:description']")?.let { el ->
            if (el.tagName() == "meta") el.attr("content").trim() else el.text().trim()
        }
        val year = document.selectFirst(".extra span.C a, .extra a[href*='/yil/'], a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags = document.select(".sgeneros a, .genres a").map { it.text().trim() }.filter { it.isNotBlank() }
        val duration = document.selectFirst(".runtime, .extra .runtime")?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val recommendations = document.select(".srelacionados .item, .srelacionados article").mapNotNull { it.toRecommendationResult() }
        val actors = document.select(".cast .person, span.valor a").mapNotNull { it.text().trim().takeIf(String::isNotBlank)?.let(::Actor) }
        val trailer = Regex("""(?:youtube\.com/embed/|youtu\.be/)([A-Za-z0-9_-]+)""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }

        if (url.contains("/dizi/")) {
            val episodes = document.select("#episodes ul.episodios li, .episodios li").mapNotNull {
                val epLink = it.selectFirst("h4.episodiotitle a, .episodiotitle a, a[href]") ?: return@mapNotNull null
                val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
                val epName = epLink.text().trim().ifBlank { return@mapNotNull null }
                val epSeason = Regex("(\\d+)\\.\\s*Sezon").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epEpisode = Regex("(\\d+)\\.\\s*Bölüm").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(epHref) { name = epName; season = epSeason; episode = epEpisode }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; plot = description; this.year = year; this.tags = tags; this.duration = duration; this.recommendations = recommendations
                addActors(actors); addTrailer(trailer)
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; plot = description; this.year = year; this.tags = tags; this.duration = duration; this.recommendations = recommendations
            addActors(actors); addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? = toSearchResult()

    private fun sendMultipartRequest(nonce: String, postId: String, playerName: String, partKey: String, referer: String): Response {
        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("action", "get_video_url")
            addFormDataPart("nonce", nonce)
            addFormDataPart("post_id", postId)
            addFormDataPart("player_name", playerName)
            addFormDataPart("part_key", partKey)
        }.build()
        return OkHttpClient().newCall(
            Request.Builder().url("${mainUrl}/wp-admin/admin-ajax.php").post(requestBody)
                .addHeader("Referer", referer)
                .addHeader("User-Agent", requestHeaders["User-Agent"]!!)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .build()
        ).execute()
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, headers = requestHeaders, referer = mainUrl).document
        val nonce = document.selectFirst("#playex, div#playex")?.attr("data-nonce").orEmpty()

        document.select("nav.player a, .dooplay_player_option a, .player-option a, [data-player-name][data-post-id]")
            .forEach { element ->
                val playerName = element.attr("data-player-name").ifBlank { element.text().trim() }
                val sourceId = element.attr("data-post-id")
                val partKey = element.attr("data-part-key")
                if (sourceId.isBlank() || sourceId.contains("event", true)) return@forEach

                try {
                    val body = sendMultipartRequest(nonce, sourceId, playerName, partKey, data).use { it.body.string() }
                    val json = JSONObject(body)
                    val iframe = json.optJSONObject("data")?.optString("url").orEmpty()
                    if (iframe.isBlank()) return@forEach

                    val finalUrl = if (partKey.isNotBlank() && !iframe.contains("partKey=")) {
                        if (iframe.contains("?")) "$iframe&partKey=$partKey" else "$iframe?partKey=$partKey"
                    } else iframe

                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("STF", "Player link alınamadı: $playerName", e)
                }
            }
        return true
    }
}
