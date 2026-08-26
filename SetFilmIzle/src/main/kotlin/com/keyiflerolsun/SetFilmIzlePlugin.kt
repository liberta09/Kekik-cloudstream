package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SetFilmIzlePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(SetFilmIzle())
        registerExtractorAPI(FastPlayExtractor())
        registerExtractorAPI(SetPlayExtractor())
    }
}
