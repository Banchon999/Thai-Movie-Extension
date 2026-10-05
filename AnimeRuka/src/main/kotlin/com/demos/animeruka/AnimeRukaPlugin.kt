package com.demos.animeruka

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeRukaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeRukaProvider())
    }
}
