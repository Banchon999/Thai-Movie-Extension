package com.demos.hd25

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TwentyFiveHDPlugin : BasePlugin() {
    override fun load() {
        // Serves the muxed ZMDB download links through the app's shared HTTP client.
        TwentyFiveHDProvider.installDownloads()
        registerMainAPI(TwentyFiveHDProvider())
    }
}
