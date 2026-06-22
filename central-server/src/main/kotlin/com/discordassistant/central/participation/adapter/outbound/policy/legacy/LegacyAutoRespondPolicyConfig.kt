package com.discordassistant.central.participation.adapter.outbound.policy.legacy

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * legacy 자동응답 정책을 participation [SocialPolicyPort] 구현 bean 으로 노출한다(NEXA-P15-T003).
 *
 * **acceptance(T003) — JDA listener 가 legacy 와 NEXA 를 동시에 직접 호출하지 않는다**:
 * 기존 channelai 자동응답 결정 로직은 이미 [LegacyAutoRespondPolicy](P09-T006)가 **수정 없이** policy 계약
 * ([com.discordassistant.central.participation.application.port.out.SocialPolicyPort])으로 미러한다. 이 config 는
 * 그 미러를 bean 으로 등록해 P15 파이프라인이 **포트로** 소비할 수 있게 한다 — 즉 legacy 결정 경로가 adapter 뒤로
 * 들어간다. 실제 JDA `onMessageReceived` 의 직접 legacy 호출(flag OFF 경로)은 한 줄도 바뀌지 않는다(회귀 0):
 * NEXA participation 이 활성(flag ON)인 (guild, channel)에서만 파이프라인이 이 포트를 쓰고, OFF 면 JDA 가 기존
 * legacy 경로만 직접 탄다 — 같은 메시지에 둘이 동시에 직접 발화하지 않는다.
 *
 * bean qualifier 로 `legacyAutoRespondPolicy` 를 둬, learned/ONNX/gRPC 정책 bean 들과 구분해 fallback 체인 구성에서
 * 명시적으로 선택할 수 있게 한다.
 */
@Configuration
class LegacyAutoRespondPolicyConfig {
    @Bean(name = ["legacyAutoRespondPolicy"])
    fun legacyAutoRespondPolicy(): LegacyAutoRespondPolicy = LegacyAutoRespondPolicy()
}
