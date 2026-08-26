plugins {
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language =  "tr"
    description = "SetFilmIzle eklentisi"
    authors = listOf("liberta09")
    status = 1
    tvTypes = listOf("TvType.Movie", "TvType.TvSeries")
}
