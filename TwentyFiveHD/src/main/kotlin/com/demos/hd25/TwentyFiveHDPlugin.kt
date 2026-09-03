package com.demos.hd25

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TwentyFiveHDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TwentyFiveHDProvider())
    }
}
