package com.discordassistant.central.licensing.adapter.inbound.web

import com.discordassistant.central.licensing.application.LicenseFunnel
import com.discordassistant.central.licensing.application.LicenseService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 라이선스 운영 지표(어드민 대시보드). 인증은 AiNetworkApiSecurityFilter 가 처리한다. */
@RestController
@RequestMapping("/api/ai-network/license")
class LicenseAdminController(
    private val licenses: LicenseService,
) {
    @GetMapping("/funnel")
    fun funnel(): LicenseFunnel = licenses.funnel()
}
