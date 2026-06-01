package com.discordassistant.central.dashboard

import com.discordassistant.central.network.AiNetworkFeatureGate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai-network/features")
class AiNetworkFeatureController(
    private val featureGate: AiNetworkFeatureGate,
) {
    @GetMapping
    fun features() = featureGate.snapshot()
}
