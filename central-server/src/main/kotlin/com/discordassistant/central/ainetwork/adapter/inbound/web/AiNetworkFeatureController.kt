package com.discordassistant.central.ainetwork.adapter.inbound.web

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
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
