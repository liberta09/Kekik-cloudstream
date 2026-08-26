// SetFilmIzle - SetFilm site yapısına göre uyarlanmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class SetFilmIzle : MainAPI() {
    override var mainUrl              = "https://www.setfilmizle.ltd"
    override var name                 = "SetFilmIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/"        to "Aile",
        "${mainUrl}/tur/aksiyon/"     to "Aksiyon",
        "${mainUrl}/tur/animasyon/"   to "Animasyon",
        "${mainUrl}/tur/belgesel/"    to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/"   to "Biyografi",
        "${mainUrl}/tur/dini/"        to "Dini",
        "${mainUrl}/tur/dram/"        to "Dram",
        "${mainUrl}/tur/fantastik/"   to "Fantastik",
        "${mainUrl}/tur/genclik/"     to "Gençlik",
        "${mainUrl}/tur/gerilim/"     to "Gerilim",
        "${mainUrl}/tur/gizem/"       to "Gizem",
        "${mainUrl}/tur/komedi/"      to "Komedi",
        "${mainUrl}/tur/korku/"       to "Korku",
        "${mainUrl}/tur/macera/"      to "Macera",
        "${mainUrl}/tur/mini-dizi/"   to "Mini Dizi",
        "${mainUrl}/tur/muzik/"       to "Müzik",
        "${mainUrl}/tur/program/"     to "Program",
        "${mainUrl}/tur/romantik/"    to "Romantik",
        "${mainUrl}/tur/savas/"       to "Savaş",
        "${mainUrl}/tur/spor/"        to "Spor",
        "${mainUrl}/tur/suc/"         to "Suç",
        "${mainUrl}/tur/tarih/"       to "Tarih",
        "${mainUrl}/tur/western/"     to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url).document
        val home = document.select("div.items article")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url.substringBefore("?").trimEnd('/').lowercase() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = selectFirst("div.flbaslik")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val normalized = href.substringBefore("?").trimEnd('/').lowercase()
        if (!normalized.contains("/film/") && !normalized.contains("/dizi/")) return null
        val image = selectFirst("img")
        val posterUrl = fixUrlNull(
            listOf(image?.attr("data-src").orEmpty(), image?.attr("data-lazy-src").orEmpty(), image?.attr("data-original").orEmpty(), image?.attr("src").orEmpty())
                .firstOrNull { it.isNotBlank() }
        )
        return if (normalized.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("div.result-item article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url.substringBefore("?").trimEnd('/').lowercase() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.title a")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(selectFirst("div.title a")?.attr("href")) ?: return null
        val normalized = href.substringBefore("?").trimEnd('/').lowercase()
        if (!normalized.contains("/film/") && !normalized.contains("/dizi/")) return null
        val image = selectFirst("img")
        val posterUrl = fixUrlNull(listOf(image?.attr("data-src").orEmpty(), image?.attr("data-lazy-src").orEmpty(), image?.attr("src").orEmpty()).firstOrNull { it.isNotBlank() })
        return if (normalized.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.let { image ->
            listOf(image.attr("data-src"), image.attr("data-lazy-src"), image.attr("src")).firstOrNull { it.isNotBlank() }
        })
        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        var year = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.sgeneros a").map { it.text() }
        val ratingValue = document.selectFirst("span.dt_rating_vgs")?.text()?.trim()?.toDoubleOrNull()
        var duration = document.selectFirst("span.runtime")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val recommendations = document.select("div.srelacionados article").mapNotNull { it.toRecommendationResult() }
        val actors = document.select("span.valor a").map { Actor(it.text()) }
        val trailer = Regex("""embed\/(.*)\?rel""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }

        if (url.contains("/dizi/")) {
            year = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
            duration = document.selectFirst("div#info span:containsOwn(Dakika)")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
            val episodes = document.select("div#episodes ul.episodios li").mapNotNull {
                val epHref = fixUrlNull(it.selectFirst("div.episodiotitle a")?.attr("href")) ?: return@mapNotNull null
                val epName = it.selectFirst("div.episodiotitle a")?.ownText()?.trim() ?: return@mapNotNull null
                val epDetail = it.selectFirst("div.numerando")?.text()?.split(" - ") ?: return@mapNotNull null
                val epSeason = epDetail.firstOrNull()?.toIntOrNull()
                val epEpisode = epDetail.lastOrNull()?.toIntOrNull()
                newEpisode(epHref) { name = epName; season = epSeason; episode = epEpisode }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
                ratingValue?.let { score = Score.from10(it) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            ratingValue?.let { score = Score.from10(it) }
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val image = selectFirst("a img") ?: return null
        val title = image.attr("alt").trim().takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val normalized = href.substringBefore("?").trimEnd('/').lowercase()
        if (!normalized.contains("/film/") && !normalized.contains("/dizi/")) return null
        val posterUrl = fixUrlNull(listOf(image.attr("data-src"), image.attr("data-lazy-src"), image.attr("src")).firstOrNull { it.isNotBlank() })
        return if (normalized.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("STF", "data » $data")
        val document = app.get(data).document
        document.select("nav.player a").forEach { element ->
            val onclick = element.attr("onclick")
            if (!onclick.contains("Change_Source")) return@forEach
            val sourceParts = onclick.substringAfter("Change_Source('").substringBefore("');").split("','")
            val sourceId = sourceParts.getOrNull(0).orEmpty()
            val providerName = sourceParts.getOrNull(1).orEmpty()
            val partKey = sourceParts.getOrNull(2).orEmpty()
            if (sourceId.isBlank() || sourceId.contains("event", ignoreCase = true)) return@forEach
            try {
                val sourceUrl = "${mainUrl}/play/play.php?ser=${sourceId}&name=${providerName}&partKey=${partKey}"
                val sourceIframe = app.get(sourceUrl, referer = data).document.selectFirst("iframe")?.attr("src")?.trim().orEmpty()
                if (sourceIframe.isBlank()) return@forEach
                val extractorUrl = if (sourceIframe.contains("explay.store", ignoreCase = true) || sourceIframe.contains("setplay.site", ignoreCase = true)) {
                    if (sourceIframe.contains("partKey=")) sourceIframe else if (sourceIframe.contains("?")) "$sourceIframe&partKey=$partKey" else "$sourceIframe?partKey=$partKey"
                } else sourceIframe
                loadExtractor(extractorUrl, "${mainUrl}/", subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("STF", "Provider link alınamadı: $providerName", e)
            }
        }
        return true
    }
}
