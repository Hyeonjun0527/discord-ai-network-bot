package com.discordassistant.central.participation.adapter.inbound.rollout

import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.application.rollout.CanaryAutoHaltService
import com.discordassistant.central.participation.application.rollout.OperatorAlertPort
import com.discordassistant.central.participation.application.rollout.PendingActionCancellationPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Canary 자동 중단 유스케이스 배선(NEXA-P18-T023, participation 인바운드 어댑터).
 *
 * 순수 application 서비스 [CanaryAutoHaltService](그 자체는 @Component 아님)를 wired 포트와 함께 빈으로 조립한다.
 * 이 빈은 **flag-gated 아니다**: 호출자(자율 전송 flag 뒤의 [CanaryAutoHaltMonitor])가 없으면 무해하게 idle 이고,
 * 다른 경로(관리자 수동 중단 등)가 재사용할 수 있게 항상 존재시킨다. 실제 강등을 유발하는 것은 flag-gated 모니터뿐이다.
 */
@Configuration
class CanaryAutoHaltConfig {
    /** 강등·pending 취소·운영자 알림을 원자적 의도로 수행하는 자동 중단 서비스. */
    @Bean
    fun canaryAutoHaltService(
        shadowModeStore: ShadowModeStorePort,
        cancellation: PendingActionCancellationPort,
        operatorAlert: OperatorAlertPort,
    ): CanaryAutoHaltService = CanaryAutoHaltService(shadowModeStore, cancellation, operatorAlert, Clock.systemUTC())
}
