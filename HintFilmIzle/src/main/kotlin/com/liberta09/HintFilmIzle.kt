package com.liberta09

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Filmler",
        "$mainUrl/film-izle" to "Filmler & Diziler",
        "$mainUrl/trendler" to "Trendler",
        "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/dram-filmleri" to "Dram",
        "$mainUrl/tur/komedi-filmleri" to "Komedi",
        "$mainUrl/tur/korku-filmleri" to "Korku",
        "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/tarih-filmleri" to "Tarih",
        "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page"
        val document = app.get(pageUrl).document
        val results = document.select("article, .film-box, .movie-item, .poster, .movie, .film, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(60)
        val hasNext = document.selectFirst("a.next, a[rel=next], .pagination a.next, .pagination .next") != null || results.size >= 10
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    private fun Element.findCard(): Element {
        var current: Element? = this
        repeat(5) {
            if (current?.selectFirst("img") != null && current.select("a[href]").isNotEmpty()) return current!!
            current = current?.parent()
        }
        return this
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val card = findCard()
        val anchor = if (tagName() == "a") this else card.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href").trim()) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val path = href.removePrefix(mainUrl).substringBefore("?").removeSuffix("/")
        if (path.isBlank() || path == "/film" || path == "/film-izle" || path == "/trendler" || path.startsWith("/tur/") || path.startsWith("/kategori") || path.startsWith("/koleksiyon")) return null

        val cardTitle = listOf(
            card.selectFirst("h1,h2,h3,h4,h5,.title,.name,.film-name,.movie-title")?.text(),
            card.selectFirst("img")?.attr("alt"),
            anchor.attr("title"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() && it.trim().length > 1 }?.trim() ?: return null

        val posterUrl = fixUrlNull(card.selectFirst("img")?.let { image ->
            image.attr("data-src").ifBlank {
                image.attr("data-lazy-src").ifBlank {
                    image.attr("data-original").ifBlank { image.attr("src") }
                }
            }
        })

        val cardText = card.text()
        val series = cardText.contains("dizi", true) || cardText.contains("sezon", true) || cardText.contains("bölüm", true) || path.startsWith("/dizi/") || path.contains("/series/")
        return if (series) newTvSeriesSearchResponse(cardTitle, href) { this.posterUrl = posterUrl }
        else newMovieSearchResponse(cardTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrls = listOf("$mainUrl/?s=$encoded", "$mainUrl/film?s=$encoded", "$mainUrl/film-izle?s=$encoded")
        for (searchUrl in searchUrls) {
            val results = runCatching {
                app.get(searchUrl).document.select("article, .film-box, .movie-item, .poster, .movie, .film, .item")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }.getOrNull().orEmpty()
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun extractValue(text: String, pattern: String, group: Int = 1): String? =
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(text)?.groupValues?.getOrNull(group)?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching { app.get(url).document }.getOrNull() ?: return null
        val pageText = document.text().replace(Regex("\\s+"), " ").trim()

        val titleValue = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: document.title().substringBefore("|").trim().takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").substringBefore("-izle").replace('-', ' ').replaceFirstChar { it.uppercase() }

        val posterUrlValue = document.selectFirst("meta[property='og:image'], meta[name='twitter:image']")?.attr("content")?.let(::fixUrlNull)
            ?: document.selectFirst("img")?.let { image ->
                fixUrlNull(image.attr("data-src").ifBlank {
                    image.attr("data-lazy-src").ifBlank {
                        image.attr("data-original").ifBlank { image.attr("src") }
                    }
                })
            }

        val plotValue = extractValue(pageText, "Genel Bakış\\s*(.*?)(?:Bu Film özeti|Ülke\\s)")
            ?: document.select("p").map { it.text().trim() }
                .filter { it.length >= 80 && !it.contains("Hint Film izleme sitemizde", true) }
                .maxByOrNull { it.length }
            ?: document.selectFirst("meta[name='description'], meta[property='og:description']")?.attr("content")?.trim()

        val genreTags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = document.select("a[href]").mapNotNull { anchorElement ->
            val episodeText = anchorElement.text().trim()
            val episodeHref = fixUrlNull(anchorElement.attr("href").trim())
            if (episodeHref == null || episodeText.isBlank()) return@mapNotNull null
            val match = Regex("(?:S(?:ezon)?\\s*)?(\\d+)?\\s*(?:x|[.]?Bölüm|Episode)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(episodeText)
            if (match != null) {
                newEpisode(episodeHref) {
                    name = episodeText
                    season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
                    episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 1
                }
            } else null
        }.distinctBy { it.data }

        val series = episodes.isNotEmpty() || pageText.contains("sezon", true) || pageText.contains("bölüm", true) || url.contains("/dizi/")

        return if (series) {
            newTvSeriesLoadResponse(titleValue, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrlValue
                this.plot = plotValue
                this.tags = genreTags
            }
        } else {
            newMovieLoadResponse(titleValue, url, TvType.Movie, url) {
                this.posterUrl = posterUrlValue
                this.plot = plotValue
                this.tags = genreTags
            }
        }
    }

    private suspend fun loadFrame(frameUrl: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var loaded = false
        runCatching {
            loadExtractor(frameUrl, referer, subtitleCallback, callback)
            loaded = true
        }
        runCatching {
            val childDocument = app.get(frameUrl, referer = referer).document
            val childFrames = childDocument.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url], video source[src], video[src], source[src]")
                .mapNotNull { element ->
                    val rawUrl = element.attr("src").ifBlank {
                        element.attr("data-src").ifBlank {
                            element.attr("data-lazy-src").ifBlank { element.attr("data-url") }
                        }
                    }
                    fixUrlNull(rawUrl)
                }.distinct()
            childFrames.forEach { nestedUrl ->
                runCatching {
                    loadExtractor(nestedUrl, frameUrl, subtitleCallback, callback)
                    loaded = true
                }
            }
        }
        return loaded
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching { app.get(data).document }.getOrNull() ?: return false
        var loaded = false

        val frameUrls = document.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url], video source[src], video[src], source[src]")
            .mapNotNull { element ->
                val rawUrl = element.attr("src").ifBlank {
                    element.attr("data-src").ifBlank {
                        element.attr("data-lazy-src").ifBlank { element.attr("data-url") }
                    }
                }
                fixUrlNull(rawUrl)
            }.distinct()

        frameUrls.forEach { frameUrl ->
            if (loadFrame(frameUrl, data, subtitleCallback, callback)) loaded = true
        }

        val playerUrls = document.select("a[href]").mapNotNull { anchor ->
            val anchorText = anchor.text().trim()
            val href = fixUrlNull(anchor.attr("href").trim())
            if (href != null && (anchorText.contains("TEKPART", true) || anchorText.contains("PART", true) || href.contains("embed", true) || href.contains("player", true))) href else null
        }.distinct()

        playerUrls.forEach { playerUrl ->
            if (loadFrame(playerUrl, data, subtitleCallback, callback)) loaded = true
        }

        return loaded
    }
}
