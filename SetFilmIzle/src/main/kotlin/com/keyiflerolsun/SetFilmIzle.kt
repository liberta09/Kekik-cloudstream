package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://setfilmizle.com"
    override var name = "SetFilmIzle"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
}
