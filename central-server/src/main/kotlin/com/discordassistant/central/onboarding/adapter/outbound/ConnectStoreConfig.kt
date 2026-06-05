package com.discordassistant.central.onboarding.adapter.outbound

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 웹 ‘토큰 받기’ 온보딩의 단발성 상태 저장소를 싱글톤 빈으로 등록한다.
 *
 * [ConnectStateStore](OAuth state)·[ProviderSelectionStore](후보 선택)는 서버 메모리 + SecureRandom +
 * TTL + 1회 take 로직을 가진 단발성 저장소다. 컨트롤러 생성자에서 `= ConnectStateStore()` 로 매번 new 하지
 * 않고(흐름 간 상태 공유), 여기서 한 인스턴스를 빈으로 만들어 [ProviderConnectOnboardingService] 가 주입받는다.
 * 기본 TTL/clock(SecureRandom 포함) 동작은 그대로다.
 */
@Configuration
class ConnectStoreConfig {
    @Bean
    fun connectStateStore(): ConnectStateStore = ConnectStateStore()

    @Bean
    fun providerSelectionStore(): ProviderSelectionStore = ProviderSelectionStore()
}
