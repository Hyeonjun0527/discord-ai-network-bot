package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.participation.application.model.ModelApprovalAuditPort
import com.discordassistant.central.participation.application.model.ModelApprovalService
import com.discordassistant.central.participation.application.model.ShadowModelRegistry
import com.discordassistant.central.participation.application.port.out.ShadowModelRegistryPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 모델 승인 application 빈 배선(NEXA-P19-T020). 새 코드만 — 기존 빈 정의를 바꾸지 않는다.
 *
 * [ShadowModelRegistry] 는 기존 [ShadowModelRegistryPort] 어댑터(JpaShadowModelRegistryStore)를 그대로 쓰고,
 * [ModelApprovalService] 는 audit 포트(JpaModelApprovalAuditStore)를 받는다. 승인은 자동 배포가 아니라 SHADOW→
 * APPROVED 게이트일 뿐이다(LIVE 선택·rollout 은 별도 운영 경로).
 */
@Configuration
class ModelApprovalConfig {
    @Bean
    fun nexaShadowModelRegistry(store: ShadowModelRegistryPort): ShadowModelRegistry = ShadowModelRegistry(store)

    @Bean
    fun nexaModelApprovalService(
        registry: ShadowModelRegistry,
        audit: ModelApprovalAuditPort,
    ): ModelApprovalService = ModelApprovalService(registry, audit)
}
