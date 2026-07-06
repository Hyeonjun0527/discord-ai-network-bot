package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.DiscordExecutorPort
import com.discordassistant.central.actionruntime.application.port.out.SpeechContentResolver
import com.discordassistant.central.platform.discord.DiscordBot
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * NEXA 자율 전송의 **JDA 전송 어댑터 배선**(NEXA-P13-T015/T017, platform/discord 어댑터).
 *
 * [DiscordExecutorPort] 의 실 구현([JdaDiscordExecutor])을 빈으로 등록한다 — JDA 는 [DiscordBot] 이 소유하므로 이
 * platform 경계에서만 조립한다(actionruntime 은 포트만 안다, 헥사고날). 실제 poll→execute 유스케이스 배선은
 * actionruntime 인바운드 어댑터([com.discordassistant.central.actionruntime.adapter.inbound.scheduler.AutonomousSendConfig])
 * 가 이 포트 빈을 주입받아 조립한다.
 *
 * 단일 flag `central.nexa.autonomous-send.enabled` 뒤에 있고 기본 부재(=OFF)라, 운영자가 켜기 전에는 이 빈이 생성되지
 * 않는다(전송 어댑터 미배선 = 니아 자율 발화 없음). LIVE/CANARY 에서만 executor 가 실제 호출된다(P09 hard block 은
 * [com.discordassistant.central.actionruntime.application.execution.ActionExecutionService] 가 담당).
 */
@Configuration
@ConditionalOnProperty(name = ["central.nexa.autonomous-send.enabled"], havingValue = "true")
class JdaDiscordExecutorConfig {
    /**
     * 실제 전송 어댑터(활성 JDA + content resolver). JDA 는 **첫 전송 시점에** provider 로 지연 해석한다 — 빈 생성
     * 시점(startup)엔 JDA 가 아직 비동기 연결 전일 수 있어, 그때 해석하면 앱 시작이 실패한다. 미기동 상태로 전송을
     * 시도하면 [DiscordBot.requireActiveJda] 가 명확히 실패하고 스케줄러가 흡수해 다음 tick 에 재시도한다.
     */
    @Bean
    @ConditionalOnMissingBean(DiscordExecutorPort::class)
    fun nexaDiscordExecutorPort(
        discordBot: DiscordBot,
        speechContentResolver: SpeechContentResolver,
    ): DiscordExecutorPort = JdaDiscordExecutor({ discordBot.requireActiveJda() }, speechContentResolver)
}
