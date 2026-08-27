package com.liberta09

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Filmler",
        "$mainUrl/film-izle" to "Filmler & Diziler",
        "$mainUrl/trendler" to "Trendler",
        "$mainUrl/tur/aile-filmleri" to "Aile",
        "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/animasyon-filmleri" to "Animasyon",
        "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/tur/dram-filmleri" to "Dram",
        "$mainUrl/tur/fantastik-filmleri" to "Fantastik",
        "$mainUrl/tur/komedi-filmleri" to "Komedi",
        "$mainUrl/tur/korku-filmleri" to "Korku",
        "$mainUrl/tur/macera-filmleri" to "Macera",
        "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/savas-filmleri" to "Savaş",
        "$mainUrl/tur/suc-filmleri" to "Suç",
        "$mainUrl/tur/tarih-filmleri" to "Tarih"
    )

    private fun Element.card(): Element {
        var current: Element? = this
        repeat(6) {
            val value = current
            if (value != null && value.selectFirst("img") != null &&
                value.selectFirst("a[href*='/film/'], a[href*='/dizi/']") != null) return value
            current = current?.parent()
        }
        return this
    }

    private fun extractYear(text: String): Int? =
        Regex("\\b(?:19|20)\\d{2}\\b").find(text)?.value?.toIntOrNull()

    private fun extractScore(text: String): Double? =
        Regex("(?<!\\d)(?:10(?:[.,]0)?|[0-9][.,][0-9])(?!\\d)")
            .findAll(text)
            .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
            .firstOrNull { it in 0.0..10.0 }

    private fun Element.toSearchResult(): SearchResponse? {
        val container = card()
        val link = if (tagName() == "a") this else
            container.selectFirst("a[href*='/film/'], a[href*='/dizi/']") ?: return null
        val href = fixUrlNull(link.attr("href").trim()) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val title = listOf(
            container.selectFirst("h1,h2,h3,h4,h5,.title,.name,.film-name,.movie-title")?.text(),
            container.selectFirst("img")?.attr("alt"),
            link.attr("title"),
            link.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        val poster = container.selectFirst("img")?.let { image ->
            fixUrlNull(
                image.attr("data-src").ifBlank {
                    image.attr("data-lazy-src").ifBlank {
                        image.attr("data-original").ifBlank {
                            image.attr("src")
                        }
                    }
                }
            )
        }

        val cardText = container.text()
        val year = extractYear(cardText)
        val score = extractScore(cardText)

        return newMovieSearchResponse(title, href, if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie) {
            posterUrl = poster
            this.year = year
            this.score = score?.let(Score::from10)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page"
        val document = app.get(pageUrl).document
        val results = document.select("article, .film-box, .movie-item, .film-item, .movie, .film, .item, .poster")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(60)

        val hasNext = document.select("a").any {
            val text = it.text().trim()
            it.attr("href").contains("/page/") ||
                text.contains("Son", true) ||
                text.contains("İleri", true) ||
                text == ">"
        }

        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/film?s=$encoded",
            "$mainUrl/film-izle?s=$encoded"
        )

        for (searchUrl in urls) {
            val results = runCatching {
                app.get(searchUrl).document
                    .select("article, .film-box, .movie-item, .film-item, .movie, .film, .item, .poster")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }.getOrNull().orEmpty()
            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun valueFromMeta(document: org.jsoup.nodes.Document, selector: String): String? =
        document.selectFirst(selector)?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching { app.get(url).document }.getOrNull() ?: return null
        val pageText = document.text().replace(Regex("\\s+"), " ").trim()
        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: valueFromMeta(document, "meta[property='og:title']")
            ?: document.title().substringBefore("|").trim().takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").substringBefore("-izle").replace('-', ' ')

        val poster = valueFromMeta(document, "meta[property='og:image']")?.let(::fixUrlNull)
            ?: document.selectFirst("img")?.let { image ->
                fixUrlNull(
                    image.attr("data-src").ifBlank {
                        image.attr("data-lazy-src").ifBlank {
                            image.attr("data-original").ifBlank { image.attr("src") }
                        }
                    }
                )
            }

        val plot = document.select("p").map { it.text().trim() }
            .filter { it.length >= 60 && !it.contains("Hint Film izleme sitemizde", true) }
            .maxByOrNull { it.length }
            ?: valueFromMeta(document, "meta[name='description']")
            ?: valueFromMeta(document, "meta[property='og:description']")

        val tags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = extractYear(pageText)
        val score = extractScore(pageText)
        val isSeries = pageText.contains("bölüm", true) ||
            pageText.contains("sezon", true) ||
            url.contains("/dizi/")

        return newMovieLoadResponse(
            title,
            url,
            if (isSeries) TvType.TvSeries else TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.score = score?.let(Score::from10)
        }
    }

    private fun isTrailerHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be") ||
            lower.contains("youtube-nocookie.com") || lower.contains("vimeo.com") ||
            lower.contains("facebook.com") || lower.contains("twitter.com") || lower.contains("x.com")
    }

    private fun isPlayerCandidate(element: Element): Boolean {
        val marker = listOf(
            element.text(), element.attr("class"), element.attr("id"),
            element.attr("data-player"), element.attr("data-embed"),
            element.attr("data-video"), element.attr("onclick")
        ).joinToString(" ").lowercase()
        return marker.contains("tekpart") || marker.contains("player") ||
            marker.contains("embed") || marker.contains("video") || marker.contains("part")
    }

    private fun collectCandidateUrls(document: org.jsoup.nodes.Document): List<String> {
        val candidates = linkedSetOf<String>()
        val attributes = listOf(
            "src", "href", "data-src", "data-url", "data-link", "data-iframe",
            "data-video", "data-embed", "data-player", "data-content", "data-href",
            "data-lazy-src", "data-litespeed-src", "srcdoc", "value"
        )

        document.select("iframe, video, source, embed, object, a, button, [role=button], [onclick]")
            .filter { element ->
                element.tagName() in setOf("iframe", "video", "source", "embed", "object") ||
                    isPlayerCandidate(element)
            }
            .forEach { element ->
                attributes.forEach { attribute ->
                    val raw = element.attr(attribute).trim()
                    val fixed = fixUrlNull(raw)
                    if (fixed != null && fixed != mainUrl && (fixed.startsWith("http://") || fixed.startsWith("https://"))) {
                        candidates += fixed
                    }
                }

                listOf("onclick", "data-url", "data-link", "data-iframe", "data-video", "data-embed", "data-player", "data-content")
                    .forEach { attribute ->
                        Regex("(?:https?://[^\\\"'()\\s<>]+|/[^\\\"'()\\s<>]+)", RegexOption.IGNORE_CASE)
                            .findAll(element.attr(attribute))
                            .forEach { match ->
                                fixUrlNull(match.value)?.let { candidates += it }
                            }
                    }
            }

        document.select("script").forEach { script ->
            Regex("(?:https?://[^\\\"'\\s<>]+|/[^\\\"'\\s<>]+)", RegexOption.IGNORE_CASE)
                .findAll(script.data())
                .forEach { match ->
                    fixUrlNull(match.value)?.let { candidates += it }
                }
        }

        val (primary, trailers) = candidates.partition { !isTrailerHost(it) }
        return primary + trailers
    }

    private suspend fun tryExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isTrailerHost(url)) return false

        var found = false
        runCatching {
            loadExtractor(url, referer, subtitleCallback, callback)
            found = true
        }

        runCatching {
            val child = app.get(url, referer = referer).document
            collectCandidateUrls(child).forEach { nested ->
                if (!isTrailerHost(nested)) {
                    runCatching {
                        loadExtractor(nested, url, subtitleCallback, callback)
                        found = true
                    }
                }
            }
        }

        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching { app.get(data).document }.getOrNull() ?: return false
        var found = false

        collectCandidateUrls(document).forEach { candidate ->
            if (tryExtractor(candidate, data, subtitleCallback, callback)) found = true
        }

        return found
    }
}
