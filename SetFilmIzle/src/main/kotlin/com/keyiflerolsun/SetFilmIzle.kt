// SetFilmIzle - SetFilm site yapısına göre uyarlanmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/" to "Aile",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/animasyon/" to "Animasyon",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/" to "Biyografi",
        "${mainUrl}/tur/dini/" to "Dini",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/fantastik/" to "Fantastik",
        "${mainUrl}/tur/genclik/" to "Gençlik",
        "${mainUrl}/tur/gerilim/" to "Gerilim",
        "${mainUrl}/tur/gizem/" to "Gizem",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/mini-dizi/" to "Mini Dizi",
        "${mainUrl}/tur/muzik/" to "Müzik",
        "${mainUrl}/tur/program/" to "Program",
        "${mainUrl}/tur/romantik/" to "Romantik",
        "${mainUrl}/tur/savas/" to "Savaş",
        "${mainUrl}/tur/spor/" to "Spor",
        "${mainUrl}/tur/suc/" to "Suç",
        "${mainUrl}/tur/tarih/" to "Tarih",
        "${mainUrl}/tur/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url).document
        val cards = document.select(
            "div.film-list div.item-relative, div.film-list article, div.items article, article.item-relative"
        )
        val results = cards.mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url.substringBefore("?").trimEnd('/').lowercase() }
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst("a.item, a[href*='/film/'], a[href*='/dizi/'], a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val normalized = href.substringBefore("?").trimEnd('/').lowercase()
        if (!normalized.contains("/film/") && !normalized.contains("/dizi/")) return null

        val title = listOf(
            link.attr("data-title"),
            selectFirst("div.flbaslik")?.text().orEmpty(),
            selectFirst("h2")?.text().orEmpty(),
            selectFirst("h3")?.text().orEmpty(),
            selectFirst(".title")?.text().orEmpty(),
            selectFirst("img")?.attr("alt").orEmpty()
        ).map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null

        val image = selectFirst("img")
        val poster = fixUrlNull(
            listOf(
                image?.attr("data-src").orEmpty(),
                image?.attr("data-lazy-src").orEmpty(),
                image?.attr("data-original").orEmpty(),
                image?.attr("data-poster").orEmpty(),
                image?.attr("src").orEmpty()
            ).firstOrNull { it.isNotBlank() }
        )

        return if (normalized.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select(
            "div.result-item article, div.film-list div.item-relative, div.film-list article"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url.substringBefore("?").trimEnd('/').lowercase() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("div.title a, a.item, a[href*='/film/'], a[href*='/dizi/'], a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val normalized = href.substringBefore("?").trimEnd('/').lowercase()
        if (!normalized.contains("/film/") && !normalized.contains("/dizi/")) return null
        val title = listOf(
            link.attr("data-title"),
            link.text(),
            selectFirst("div.title")?.text().orEmpty(),
            selectFirst("div.flbaslik")?.text().orEmpty(),
            selectFirst("img")?.attr("alt").orEmpty()
        ).map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val image = selectFirst("img")
        val poster = fixUrlNull(
            listOf(image?.attr("data-src").orEmpty(), image?.attr("data-lazy-src").orEmpty(), image?.attr("data-original").orEmpty(), image?.attr("src").orEmpty())
                .firstOrNull { it.isNotBlank() }
        )
        return if (normalized.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img, .poster img")?.let { image ->
            listOf(image.attr("data-src"), image.attr("data-lazy-src"), image.attr("data-original"), image.attr("src")).firstOrNull { it.isNotBlank() }
        })
        val description = document.selectFirst("div.wp-content p, .wp-content p")?.text()?.trim()
        var year = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.sgeneros a").map { it.text() }
        val ratingValue = document.selectFirst("span.dt_rating_vgs")?.text()?.trim()?.toDoubleOrNull()
        var duration = document.selectFirst("span.runtime")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val recommendations = document.select("div.srelacionados article").mapNotNull { it.toRecommendationResult() }
        val actors = document.select("span.valor a").map { Actor(it.text()) }
        val trailer = Regex("""embed\\/(.*)\\?rel""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }

        if (url.contains("/dizi/")) {
            year = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
            duration = document.selectFirst("div#info span:containsOwn(Dakika)")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
            val episodes = document.select("div#episodes ul.episodios li").mapNotNull {
                val epLink = it.selectFirst("div.episodiotitle a") ?: return@mapNotNull null
                val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
                val epName = epLink.ownText().trim().ifBlank { epLink.text().trim() }
                val epDetail = it.selectFirst("div.numerando")?.text()?.split(" - ") ?: return@mapNotNull null
                newEpisode(epHref) {
                    name = epName
                    season = epDetail.firstOrNull()?.toIntOrNull()
                    episode = epDetail.lastOrNull()?.toIntOrNull()
                }
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
        val link = selectFirst("a[href*='/film/'], a[href*='/dizi/'], a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val normalized = href.substringBefore("?").trimEnd('/').lowercase()
        if (!normalized.contains("/film/") && !normalized.contains("/dizi/")) return null
        val image = link.selectFirst("img") ?: selectFirst("img") ?: return null
        val title = listOf(image.attr("alt"), link.attr("data-title"), link.text()).map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(listOf(image.attr("data-src"), image.attr("data-lazy-src"), image.attr("data-original"), image.attr("src")).firstOrNull { it.isNotBlank() })
        return if (normalized.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("STF", "data » $data")
        val document = app.get(data).document
        document.select("nav.player a, .player a, a[onclick*='Change_Source']").forEach { element ->
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
