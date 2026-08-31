package com.liberta09

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MainAPI
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
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
            val results = runCatching { app.get(url).document.select("article, .film-box, .movie-item, .poster, .movie, .film, .item").mapNotNull { it.toSearchResult() }.distinctBy { it.url } }.getOrNull().orEmpty()
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .film h1, .movie-title, .entry-title, .single-title, .post-title")?.text()?.trim()
            ?: document.title().substringBefore("|").trim().takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image'], .film img, .movie img, .poster img, .single-poster img")?.let { element ->
            if (element.tagName() == "meta") element.attr("content")
            else element.attr("data-src").ifBlank { element.attr("data-lazy-src").ifBlank { element.attr("data-original").ifBlank { element.attr("src") } } }
        })
        val plot = document.selectFirst(".description, .plot, .summary, .synopsis, .film-description, .entry-content p")?.text()?.trim()
        val tags = document.select("a[href*='/tur/'], .genre a, .genres a, .category a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(document.text())?.value?.toIntOrNull()
        val episodes = document.select("a[href]").mapNotNull { a ->
            val text = a.text().trim()
            val href = fixUrlNull(a.attr("href"))
            val match = Regex("(?:S|Sezon\\s*)?(\\d+)?\\s*(?:x|[.]?Bölüm|Episode)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
            if (href != null && match != null) newEpisode(href) { name = text; episode = match.groupValues.lastOrNull()?.toIntOrNull() } else null
        }.distinctBy { it.data }
        val isSeries = episodes.isNotEmpty() || document.text().contains("sezon", true) || document.text().contains("bölüm", true) || url.contains("/dizi/")
        return if (isSeries) newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster; plot = plot; this.tags = tags; this.year = year
        } else newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; plot = plot; this.tags = tags; this.year = year
        }
    }

    private fun normalizeUrl(value: String, baseUrl: String): String? {
        var s = value.trim()
        repeat(3) {
            s = s.replace("&amp;", "&").replace("\\/", "/").replace("\\u002F", "/").replace("\\u003A", ":").replace("\\u003D", "=")
                .removePrefix("\\\"").removeSuffix("\\\"").removePrefix("'").removeSuffix("'").trim()
            s = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        }
        if (s.isBlank() || s.startsWith("javascript:", true) || s == "about:blank" || s == "#") return null
        return fixUrlNull(s, baseUrl)
    }

    private fun addDecodedCandidate(value: String, baseUrl: String, out: MutableSet<String>) {
        normalizeUrl(value, baseUrl)?.let { out.add(it) }
        val compact = value.trim().removePrefix("'").removeSuffix("'").removePrefix("\"").removeSuffix("\"")
        if (compact.length >= 16 && compact.length % 4 == 0) {
            runCatching { String(Base64.getDecoder().decode(compact)) }.getOrNull()?.let { decoded ->
                normalizeUrl(decoded, baseUrl)?.let { out.add(it) }
            }
        }
    }

    private fun isPlayerUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "videa.hu", "vk.com", "vkvideo.ru", "ok.ru", "streamtape", "mixdrop", "dood", "filemoon", "vidmoly",
            "uqload", "streamwish", "voe.", "vidplay", "filelions", "vidhide", "vidsrc", "vidcloud", "upstream",
            "embed", "player", ".m3u8", ".mp4", ".mkv", ".webm", ".mpd"
        ).any { lower.contains(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching { app.get(data, referer = mainUrl) }.getOrNull() ?: return false
        val document = response.document
        val raw = response.text
        val candidates = linkedSetOf<String>()

        document.select("iframe, video, source, a[href], [data-src], [data-url], [data-video], [data-player], [data-embed], [data-embed-url]").forEach { element ->
            element.attributes().forEach { attr -> addDecodedCandidate(attr.value, data, candidates) }
            addDecodedCandidate(element.text(), data, candidates)
        }

        document.select("script:not([src]), script[src]").forEach { script ->
            val scriptText = script.data().ifBlank { script.html() }
            Regex("(?:https?:)?//[^\\\"'\\s<>\\\\]+|(?:https?:)?\\\\/\\\\/[^\\\"'\\s<>]+|(?:https?:)?\\\\u002F\\\\u002F[^\\\"'\\s<>]+|[A-Za-z0-9+/]{40,}={0,2}")
                .findAll(scriptText)
                .forEach { addDecodedCandidate(it.value, data, candidates) }
        }

        Regex("(?:https?:)?//[^\\\"'\\s<>]+|https?%3A%2F%2F[^\\\"'\\s<>]+|https?%253A%252F%252F[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE)
            .findAll(raw)
            .forEach { addDecodedCandidate(it.value, data, candidates) }

        val playerCandidates = candidates.filter { isPlayerUrl(it) }.distinct()
        var linksFound = false
        playerCandidates.forEach { candidate ->
            runCatching {
                val before = linksFound
                val matched = loadExtractor(candidate, data, subtitleCallback) { link ->
                    linksFound = true
                    callback(link)
                }
                if (!matched && (candidate.endsWith(".m3u8", true) || candidate.endsWith(".mp4", true) || candidate.endsWith(".mpd", true))) {
                    callback(ExtractorLink(name, name, candidate, data, 0, candidate.endsWith(".m3u8", true)))
                    linksFound = true
                }
                linksFound = linksFound || before
            }
        }

        return linksFound
    }
}
