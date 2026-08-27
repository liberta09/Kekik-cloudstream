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

    // Site üzerindeki gerçek menü/kategori yolları.
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
            val hasMovieLink = value.selectFirst("a[href*='/film/'], a[href*='/dizi/']") != null
            val hasImage = value.selectFirst("img") != null
            if (hasMovieLink && hasImage) return value
            current = value.parent()
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

    private fun extractImdbScore(text: String): Double? =
        Regex("IMDb\\s*Puanı\\s*[:]?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }

    private fun imageUrl(image: Element): String? {
        val direct = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-litespeed-src"),
            image.attr("src")
        ).firstOrNull { it.isNotBlank() }
        if (!direct.isNullOrBlank()) return fixUrlNull(direct)

        val srcSet = image.attr("srcset").trim()
        if (srcSet.isNotBlank()) {
            val best = srcSet.split(',')
                .map { it.trim().substringBefore(' ') }
                .lastOrNull { it.isNotBlank() }
            if (!best.isNullOrBlank()) return fixUrlNull(best)
        }
        return null
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val container = card()
        val link = if (tagName() == "a" &&
            (attr("href").contains("/film/") || attr("href").contains("/dizi/"))
        ) this else container.selectFirst("a[href*='/film/'], a[href*='/dizi/']") ?: return null

        val href = fixUrlNull(link.attr("href").trim()) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val title = listOf(
            container.selectFirst("h1,h2,h3,h4,h5,.title,.name,.film-name,.movie-title")?.text(),
            container.selectFirst("img")?.attr("alt"),
            link.attr("title"),
            link.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        val cardText = container.text()
        val year = extractYear(cardText)
        val score = extractImdbScore(cardText) ?: extractScore(cardText)
        val poster = container.selectFirst("img")?.let(::imageUrl)

        return newMovieSearchResponse(
            title,
            href,
            if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie
        ) {
            posterUrl = poster
            this.year = year
            this.score = score?.let(Score::from10)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildPageUrl(request.data, page)
        val document = app.get(pageUrl).document
        val results = extractResults(document)
        val hasNext = document.select("a[href]").any { anchor ->
            val text = anchor.text().trim()
            val href = anchor.attr("href")
            href.contains("/page/") ||
                text.contains("Son", true) ||
                text.contains("İleri", true) ||
                text == ">"
        }
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    private fun buildPageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val clean = base.removeSuffix("/")
        val queryIndex = clean.indexOf('?')
        return if (queryIndex >= 0) {
            val path = clean.substring(0, queryIndex).removeSuffix("/")
            val query = clean.substring(queryIndex)
            "$path/page/$page$query"
        } else {
            "$clean/page/$page"
        }
    }

    private fun extractResults(document: Document): List<SearchResponse> =
        document.select("article, .film-box, .movie-item, .film-item, .movie, .film, .item, .poster")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(60)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/film?s=$encoded",
            "$mainUrl/film-izle?s=$encoded"
        )
        for (searchUrl in urls) {
            val results = runCatching { extractResults(app.get(searchUrl).document) }
                .getOrNull()
                .orEmpty()
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun valueFromMeta(document: Document, selector: String): String? =
        document.selectFirst(selector)?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching { app.get(url).document }.getOrNull() ?: return null
        val pageText = document.text().replace(Regex("\\s+"), " ").trim()

        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: valueFromMeta(document, "meta[property='og:title']")
            ?: document.title().substringBefore("|").trim().takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").substringBefore("-izle").replace('-', ' ')

        val poster = valueFromMeta(document, "meta[property='og:image']")?.let(::fixUrlNull)
            ?: document.selectFirst("img")?.let(::imageUrl)

        val plot = document.select("p")
            .map { it.text().trim() }
            .filter { it.length >= 60 && !it.contains("Hint Film izleme sitemizde", true) }
            .maxByOrNull { it.length }
            ?: valueFromMeta(document, "meta[name='description']")
            ?: valueFromMeta(document, "meta[property='og:description']")

        val tags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = extractYear(pageText)
        val imdbScore = extractImdbScore(pageText) ?: extractScore(pageText)
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
            this.score = imdbScore?.let(Score::from10)
        }
    }

    private fun isTrailerHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") ||
            lower.contains("youtu.be") ||
            lower.contains("youtube-nocookie.com") ||
            lower.contains("vimeo.com") && lower.contains("trailer") ||
            lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("x.com")
    }

    private fun isPlayerLabel(text: String): Boolean =
        Regex("^TEKPART(?:\\s*\\d+)?\\s*\\+?$", RegexOption.IGNORE_CASE)
            .matches(text.trim())

    private fun targetElement(document: Document, raw: String): Element? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (value.startsWith("#")) return document.getElementById(value.removePrefix("#"))
        return document.getElementById(value.removePrefix("#"))
    }

    private fun addAttributeUrls(element: Element, candidates: MutableSet<String>) {
        val attributes = listOf(
            "src", "href", "data-src", "data-url", "data-link", "data-iframe",
            "data-video", "data-embed", "data-player", "data-content", "data-href",
            "data-lazy-src", "data-litespeed-src", "srcdoc", "value"
        )
        attributes.forEach { attribute ->
            val raw = element.attr(attribute).trim()
            val fixed = fixUrlNull(raw)
            if (fixed != null && fixed != mainUrl &&
                (fixed.startsWith("http://") || fixed.startsWith("https://"))) {
                candidates += fixed
            }
        }

        listOf(
            "onclick", "data-url", "data-link", "data-iframe", "data-video",
            "data-embed", "data-player", "data-content", "srcdoc"
        ).forEach { attribute ->
            val raw = element.attr(attribute)
            if (raw.isBlank()) return@forEach
            Regex("(?:https?://[^\\\"'()\\s<>]+|/[^\\\"'()\\s<>]+)", RegexOption.IGNORE_CASE)
                .findAll(raw)
                .forEach { match ->
                    fixUrlNull(match.value)?.let { candidates += it }
                }
        }
    }

    private fun collectPanelUrls(document: Document): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()

        // TEKPART sekmelerinin kendisini ve gösterdiği paneli takip et.
        document.select("a,button,[role=button],[data-toggle=tab],[data-bs-toggle=tab]")
            .filter { isPlayerLabel(it.text()) }
            .forEach { button ->
                addAttributeUrls(button, candidates)

                val targetRefs = listOf(
                    button.attr("href"),
                    button.attr("data-target"),
                    button.attr("data-bs-target"),
                    button.attr("data-tab"),
                    button.attr("data-pane"),
                    button.attr("aria-controls")
                ).filter { it.isNotBlank() }

                targetRefs.forEach { ref ->
                    targetElement(document, ref)?.let { target ->
                        target.select("iframe,video,source,embed,object,a,button,[role=button],[onclick]")
                            .forEach { addAttributeUrls(it, candidates) }
                        addAttributeUrls(target, candidates)
                    }
                }

                // Bazı sürümlerde player paneli butonun kardeş/ebeveyn düğümünde.
                var parent = button.parent()
                repeat(3) {
                    parent?.select("iframe,video,source,embed,object,[onclick],[data-player],[data-embed],[data-video]")
                        ?.forEach { addAttributeUrls(it, candidates) }
                    parent = parent?.parent()
                }
            }

        return candidates
    }

    private fun isPlayerCandidate(element: Element): Boolean {
        val marker = listOf(
            element.text(), element.attr("class"), element.attr("id"),
            element.attr("data-player"), element.attr("data-embed"),
            element.attr("data-video"), element.attr("data-content"),
            element.attr("onclick")
        ).joinToString(" ").lowercase()
        return marker.contains("tekpart") || marker.contains("player") ||
            marker.contains("embed") || marker.contains("video") || marker.contains("part")
    }

    private fun collectCandidateUrls(document: Document): List<String> {
        val candidates = linkedSetOf<String>()
        candidates += collectPanelUrls(document)

        document.select("iframe, video, source, embed, object, a[href], button, [role=button], [onclick], [data-player], [data-embed], [data-video]")
            .filter { element ->
                element.tagName() in setOf("iframe", "video", "source", "embed", "object") ||
                    isPlayerCandidate(element)
            }
            .forEach { element -> addAttributeUrls(element, candidates) }

        // Script içindeki player/iframe URL'leri.
        document.select("script").forEach { script ->
            Regex("(?:https?://[^\\\"'\\s<>]+|/[^\\\"'\\s<>]+)", RegexOption.IGNORE_CASE)
                .findAll(script.data())
                .forEach { match ->
                    fixUrlNull(match.value)?.let { candidates += it }
                }
        }

        return candidates.filterNot(::isTrailerHost).distinct()
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
            loadExtractor(url, referer, subtitleCallback) { link ->
                found = true
                callback(link)
            }
        }

        runCatching {
            val child = app.get(url, referer = referer).document
            collectCandidateUrls(child).forEach { nested ->
                if (!isTrailerHost(nested)) {
                    runCatching {
                        loadExtractor(nested, url, subtitleCallback) { link ->
                            found = true
                            callback(link)
                        }
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

        // Önce TEKPART panelleri: tam film player kaynaklarının en doğrudan yolu.
        val prioritized = collectPanelUrls(document).toList()
        prioritized.forEach { candidate ->
            if (tryExtractor(candidate, data, subtitleCallback, callback)) found = true
        }

        // Sonra sayfadaki diğer gerçek player/embed kaynakları.
        collectCandidateUrls(document)
            .filterNot { it in prioritized }
            .forEach { candidate ->
                if (tryExtractor(candidate, data, subtitleCallback, callback)) found = true
            }

        return found
    }
}
