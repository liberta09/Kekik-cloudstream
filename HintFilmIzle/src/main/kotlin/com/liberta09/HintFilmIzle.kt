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
        "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page"
        val document = app.get(url).document
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

        val title = listOf(
            card.selectFirst("h1,h2,h3,h4,h5,.title,.name,.film-name,.movie-title")?.text(),
            card.selectFirst("img")?.attr("alt"),
            anchor.attr("title"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() && it.trim().length > 1 }?.trim() ?: return null

        val poster = fixUrlNull(card.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src").ifBlank { img.attr("data-original").ifBlank { img.attr("src") } } }
        })

        val text = card.text()
        val isSeries = text.contains("dizi", true) || text.contains("sezon", true) || text.contains("bölüm", true) || path.startsWith("/dizi/") || path.contains("/series/")
        return if (isSeries) newTvSeriesSearchResponse(title, href) { posterUrl = poster }
        else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf("$mainUrl/?s=$encoded", "$mainUrl/film?s=$encoded", "$mainUrl/film-izle?s=$encoded")
        for (url in urls) {
            val results = runCatching {
                app.get(url).document.select("article, .film-box, .movie-item, .poster, .movie, .film, .item")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }.getOrNull().orEmpty()
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun extractValue(text: String, pattern: String, group: Int = 1): String? =
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.getOrNull(group)?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching { app.get(url).document }.getOrNull() ?: return null
        val pageText = document.text().replace(Regex("\\s+"), " ").trim()

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: document.title().substringBefore("|").trim().takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").substringBefore("-izle").replace('-', ' ').replaceFirstChar { it.uppercase() }

        val poster = document.selectFirst("meta[property='og:image'], meta[name='twitter:image']")?.attr("content")?.let(::fixUrlNull)
            ?: document.selectFirst("img")?.let { img ->
                fixUrlNull(img.attr("data-src").ifBlank { img.attr("data-lazy-src").ifBlank { img.attr("data-original").ifBlank { img.attr("src") } } })
            }

        val plot = extractValue(pageText, "Genel Bakış\\s*(.*?)(?:Bu Film özeti|Ülke\\s)")
            ?: document.select("p").map { it.text().trim() }
                .filter { it.length >= 80 && !it.contains("Hint Film izleme sitemizde", true) }
                .maxByOrNull { it.length }
            ?: document.selectFirst("meta[name='description'], meta[property='og:description']")?.attr("content")?.trim()

        val tags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = document.select("a[href]").mapNotNull { a ->
            val text = a.text().trim()
            val href = fixUrlNull(a.attr("href").trim())
            if (href == null || text.isBlank()) return@mapNotNull null
            val match = Regex("(?:S(?:ezon)?\\s*)?(\\d+)?\\s*(?:x|[.]?Bölüm|Episode)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
            if (match != null) {
                newEpisode(href) {
                    name = text
                    season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
                    episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 1
                }
            } else null
        }.distinctBy { it.data }

        val isSeries = episodes.isNotEmpty() || pageText.contains("sezon", true) || pageText.contains("bölüm", true) || url.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = plot
                tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = plot
                tags = tags
            }
        }
    }

    private suspend fun loadFrame(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var loaded = false
        runCatching {
            loadExtractor(url, referer, subtitleCallback, callback)
            loaded = true
        }
        runCatching {
            val child = app.get(url, referer = referer).document
            val childFrames = child.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url], video source[src], video[src], source[src]")
                .mapNotNull { element ->
                    val value = element.attr("src").ifBlank {
                        element.attr("data-src").ifBlank {
                            element.attr("data-lazy-src").ifBlank { element.attr("data-url") }
                        }
                    }
                    fixUrlNull(value)
                }.distinct()
            childFrames.forEach {
                runCatching {
                    loadExtractor(it, url, subtitleCallback, callback)
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

        val directFrames = document.select(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url], video source[src], video[src], source[src]"
        ).mapNotNull { element ->
            val value = element.attr("src").ifBlank {
                element.attr("data-src").ifBlank {
                    element.attr("data-lazy-src").ifBlank { element.attr("data-url") }
                }
            }
            fixUrlNull(value)
        }.distinct()

        directFrames.forEach { frame ->
            if (loadFrame(frame, data, subtitleCallback, callback)) loaded = true
        }

        val playerLinks = document.select("a[href]").mapNotNull { a ->
            val text = a.text().trim()
            val href = fixUrlNull(a.attr("href").trim())
            if (href != null && (text.contains("TEKPART", true) || text.contains("PART", true) || href.contains("embed", true) || href.contains("player", true))) href else null
        }.distinct()

        playerLinks.forEach { link ->
            if (loadFrame(link, data, subtitleCallback, callback)) loaded = true
        }

        return loaded
    }
}
