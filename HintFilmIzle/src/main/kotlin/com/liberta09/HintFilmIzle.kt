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
import org.jsoup.nodes.Document
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
        "$mainUrl/tur/tarih-filmleri" to "Tarih",
        "$mainUrl/takvim" to "Film Takvimi",
        "$mainUrl/film?tarih=2025" to "2025 Filmleri",
        "$mainUrl/netflix-izle" to "Netflix",
        "$mainUrl/yapim/india" to "Hint Filmleri",
        "$mainUrl/yapim/south-korea" to "Kore Filmleri"
    )

    private fun Element.card(): Element {
        var current: Element? = this
        repeat(8) {
            val value = current ?: return@repeat
            if (value.selectFirst("a[href*='/film/'], a[href*='/dizi/']") != null && value.selectFirst("img") != null) {
                return value
            }
            current = value.parent()
        }
        return this
    }

    private fun imageUrl(image: Element): String? {
        val raw = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-litespeed-src"),
            image.attr("src")
        ).firstOrNull { it.isNotBlank() } ?: return null
        return fixUrlNull(raw)
    }

    private fun extractYear(text: String): Int? =
        Regex("\\b(?:19|20)\\d{2}\\b").find(text)?.value?.toIntOrNull()

    private fun extractScore(text: String): Double? =
        Regex("(?<!\\d)(?:10(?:[.,]0)?|[0-9][.,][0-9])(?!\\d)")
            .findAll(text)
            .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
            .firstOrNull { it in 0.0..10.0 }

    private fun extractImdbScore(text: String): Double? =
        Regex("IMDb\\s*Puanı\\s*[:]?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

    private fun Element.toSearchResult(): SearchResponse? {
        val container = card()
        val anchor = if (tagName() == "a" && attr("href").contains("/film/")) {
            this
        } else {
            container.selectFirst("a[href*='/film/'], a[href*='/dizi/']") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val title = listOf(
            container.selectFirst("h1,h2,h3,h4,h5,.title,.name,.film-name,.movie-title")?.text(),
            container.selectFirst("img")?.attr("alt"),
            anchor.attr("title"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        val text = container.text()
        val year = extractYear(text)
        val score = extractImdbScore(text) ?: extractScore(text)
        val poster = container.selectFirst("img")?.let(::imageUrl)
        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            posterUrl = poster
            this.year = year
            this.score = score?.let(Score::from10)
        }
    }

    private fun extractResults(document: Document): List<SearchResponse> =
        document.select("article, .film-box, .movie-item, .film-item, .movie, .film, .item, .poster")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(60)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else {
            val clean = request.data.removeSuffix("/")
            val q = clean.indexOf('?')
            if (q >= 0) clean.substring(0, q) + "/page/$page" + clean.substring(q)
            else "$clean/page/$page"
        }
        val document = app.get(pageUrl).document
        val results = extractResults(document)
        val hasNext = document.select("a[href]").any {
            it.attr("href").contains("/page/") ||
                it.text().contains("Son", true) ||
                it.text().contains("İleri", true) ||
                it.text().trim() == ">"
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
        for (url in urls) {
            val results = runCatching { extractResults(app.get(url).document) }.getOrNull().orEmpty()
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun meta(document: Document, selector: String): String? =
        document.selectFirst(selector)?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching { app.get(url).document }.getOrNull() ?: return null
        val text = document.text().replace(Regex("\\s+"), " ").trim()
        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: meta(document, "meta[property='og:title']")
            ?: document.title().substringBefore("|").trim()

        val poster = meta(document, "meta[property='og:image']")?.let(::fixUrlNull)
            ?: document.selectFirst("img")?.let(::imageUrl)
        val plot = document.select("p")
            .map { it.text().trim() }
            .filter { it.length >= 60 }
            .maxByOrNull { it.length }
            ?: meta(document, "meta[name='description']")
            ?: meta(document, "meta[property='og:description']")
        val tags = document.select("a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = extractYear(text)
        val score = extractImdbScore(text) ?: extractScore(text)
        val isSeries = url.contains("/dizi/") || text.contains("sezon", true) || text.contains("bölüm", true)

        return newMovieLoadResponse(title, url, if (isSeries) TvType.TvSeries else TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.score = score?.let(Score::from10)
        }
    }

    private fun isTrailer(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be") ||
            lower.contains("youtube-nocookie.com") || lower.contains("facebook.com") ||
            lower.contains("twitter.com") || lower.contains("x.com")
    }

    private fun normalizePlayerUrl(value: String, baseUrl: String): String? {
        val cleaned = value.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .removePrefix("\\\"")
            .removeSuffix("\\\"")
            .removePrefix("'")
            .removeSuffix("'")
        if (cleaned.isBlank() || cleaned == "about:blank" || cleaned.startsWith("javascript:")) return null
        return fixUrlNull(cleaned, baseUrl)?.takeIf { !isTrailer(it) }
    }

    private fun collectPlayerUrls(document: Document, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()

        // Sitenin TEKPART oynatıcısı için kullanılan ana alan.
        document.select("a[data-frame], [data-frame]").forEach { element ->
            normalizePlayerUrl(element.attr("data-frame"), baseUrl)?.let { result += it }
        }

        // Eski/yeni player işaretlemelerini de destekle.
        document.select("iframe, embed, video, source, a[href]").forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-lazy-src"),
                element.attr("data-litespeed-src"),
                element.attr("data-frame"),
                element.attr("data-player"),
                element.attr("data-video"),
                element.attr("data-video-src"),
                element.attr("data-embed"),
                element.attr("data-embed-url"),
                element.attr("data-url"),
                element.attr("href")
            ).forEach { value ->
                normalizePlayerUrl(value, baseUrl)?.let { url ->
                    val lower = url.lowercase()
                    if (element.tagName() != "a" ||
                        lower.contains("videa.hu") || lower.contains("vk.com") || lower.contains("vkvideo.ru") ||
                        lower.contains("ok.ru") || lower.contains("streamtape") || lower.contains("mixdrop") ||
                        lower.contains("dood") || lower.contains("filemoon") || lower.contains("vidmoly") ||
                        lower.contains("uqload") || lower.contains("streamwish") || lower.contains("voe.") ||
                        lower.contains("vidplay") || lower.contains("filelions") || lower.contains("player")) {
                        result += url
                    }
                }
            }
        }

        // Player URL'si JavaScript içine gömülüyse onu da yakala.
        document.select("script:not([src])").forEach { script ->
            Regex("(?:https?:)?//[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE)
                .findAll(script.data())
                .mapNotNull { normalizePlayerUrl(it.value, baseUrl) }
                .forEach { url ->
                    val lower = url.lowercase()
                    if (lower.contains("videa.hu") || lower.contains("vk.com") || lower.contains("vkvideo.ru") ||
                        lower.contains("ok.ru") || lower.contains("streamtape") || lower.contains("mixdrop") ||
                        lower.contains("dood") || lower.contains("filemoon") || lower.contains("vidmoly") ||
                        lower.contains("uqload") || lower.contains("streamwish") || lower.contains("voe.") ||
                        lower.contains("vidplay") || lower.contains("filelions") || lower.contains("player")) {
                        result += url
                    }
                }
        }

        return result.toList()
    }

    private fun collectMediaUrls(document: Document, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()
        document.select("video[src], source[src], iframe[src], embed[src]").forEach { element ->
            normalizePlayerUrl(element.attr("src"), baseUrl)?.let { result += it }
        }
        document.select("script:not([src])").forEach { script ->
            Regex("https?://[^\\\"'\\s<>]+(?:m3u8|mp4)(?:\\?[^\\\"'\\s<>]+)?", RegexOption.IGNORE_CASE)
                .findAll(script.data())
                .forEach { result += it.value }
        }
        return result.toList()
    }

    private suspend fun tryExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isTrailer(url)) return false
        var found = false
        runCatching {
            loadExtractor(url, referer, subtitleCallback) { link ->
                found = true
                callback(link)
            }
        }

        // Bazı player sayfaları kaynağı HTML/JS içinde doğrudan veriyor.
        runCatching {
            val playerDocument = app.get(url, referer = referer).document
            collectMediaUrls(playerDocument, url).forEach { media ->
                runCatching {
                    loadExtractor(media, url, subtitleCallback) { link ->
                        found = true
                        callback(link)
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
        val document = runCatching { app.get(data, referer = mainUrl).document }.getOrNull() ?: return false
        var found = false

        for (player in collectPlayerUrls(document, data)) {
            if (tryExtractor(player, data, subtitleCallback, callback)) found = true
        }

        if (!found) {
            for (media in collectMediaUrls(document, data)) {
                if (tryExtractor(media, data, subtitleCallback, callback)) found = true
            }
        }

        return found
    }
}
