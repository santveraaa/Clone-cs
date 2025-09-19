package com.dramaid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Dramaid : MainAPI() {
    override var mainUrl = "https://dramaid.nl"
    override var name = "DramaId"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        fun getType(t: String?): TvType {
            return when {
                t?.contains("Movie", true) == true -> TvType.Movie
                t?.contains("Anime", true) == true -> TvType.Anime
                else -> TvType.AsianDrama
            }
        }
    }

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Drama Terbaru",
        "&order=latest" to "Baru Ditambahkan",
        "&status=&type=&order=popular" to "Drama Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/series/?page=$page${request.data}").document
        val home = document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperDramaLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            val match = Regex("$mainUrl/(.+)-ep.+").find(uri)
            if (match != null && match.groupValues.size > 1) {
                "$mainUrl/series/${match.groupValues[1]}"
            } else {
                uri
            }
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = getProperDramaLink(this.selectFirst("a.tip")?.attr("href") ?: return null)
        val title = this.selectFirst("h2[itemprop=headline]")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.select("img:last-child").attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.select("div.thumb img:last-child").attr("src"))
        val tags = document.select(".genxed > a").map { it.text() }
        val type = document.selectFirst(".info-content .spe span:contains(Tipe:)")?.ownText()
        val year = Regex("\\d, ([0-9]*)").find(
            document.selectFirst(".info-content > .spe > span > time")?.text()?.trim() ?: ""
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            document.select(".info-content > .spe > span:nth-child(1)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".entry-content > p").text().trim()

        val episodes = document.select(".eplister > ul > li").mapNotNull { episodeElement ->
            val anchor = episodeElement.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(anchor.attr("href"))
            val episodeTitle = episodeElement.selectFirst("a > .epl-title")?.text() ?: anchor.text()

            val episodeNumber = Regex("""(?:Episode|Eps)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(episodeTitle)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            newEpisode(link) {
                this.name = episodeTitle
                this.episode = episodeNumber
            }
        }.reversed()

        val recommendations =
            document.select(".listupd > article[itemscope=itemscope]").mapNotNull { rec ->
                rec.toSearchResult()
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            getType(type),
            episodes = episodes
        ) {
            posterUrl = poster
            this.year = year
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private data class Sources(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("default") val default: Boolean?
    )

    private data class Tracks(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("kind") val type: String,
        @JsonProperty("default") val default: Boolean?
    )

    private suspend fun invokeDriveSource(
        url: String,
        name: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val server = app.get(url).document.selectFirst(".picasa")?.nextElementSibling()?.data()
            ?: return

        val sourceText = server.substringAfter("sources: [").substringBefore("],")
        val source = "[$sourceText]"
        val trackersText = server.substringAfter("tracks:[").substringBefore("],")
            .replace("//language", "")
            .replace("file", "\"file\"")
            .replace("label", "\"label\"")
            .replace("kind", "\"kind\"")
        val trackers = "[$trackersText]"

        tryParseJson<List<Sources>>(source)?.forEach { sourceItem ->
            sourceCallback(
                ExtractorLink(
                    name,
                    "Drive",
                    fixUrl(sourceItem.file),
                    "https://motonews.club/",
                    getQualityFromName(sourceItem.label),
                    sourceItem.type.equals("hls", true)
                )
            )
        }

        tryParseJson<List<Tracks>>(trackers)?.forEach { track ->
            subCallback.invoke(
                SubtitleFile(
                    if (track.label.contains("Indonesia")) "${track.label}n" else track.label,
                    track.file
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val sources = document.select(".mobius > .mirror > option").mapNotNull {
            val value = it.attr("value")
            if (value.isNotBlank()) {
                try {
                    fixUrl(Jsoup.parse(base64Decode(value)).select("iframe").attr("src"))
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }

        sources.map { source ->
            source.replace("https://ndrama.xyz", "https://www.fembed.com")
        }.forEach { processedSource ->
            when {
                processedSource.contains("motonews") -> invokeDriveSource(
                    processedSource,
                    name,
                    subtitleCallback,
                    callback
                )
                else -> loadExtractor(processedSource, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }
}