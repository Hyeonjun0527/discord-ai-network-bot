package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent

/**
 * JDA 이벤트 → conversation 정규화 이벤트 변환 포트(NEXA-P03-T002).
 *
 * platform/discord/nexa 는 **어댑터 계층**이라 JDA 타입에 의존해도 된다(discord-adapter-boundary.md). 단,
 * 이 변환의 **출력은 순수 도메인 [NormalizedDiscordEvent]** 여야 한다 — 결과 객체 어디에도 JDA 참조가 남지 않는다
 * (불변식 1: JDA 객체는 어댑터 경계를 넘지 않는다).
 *
 * 변환은 두 단계로 나눈다(KISS·테스트 가능성):
 *  1. **추출(extract)**: JDA 이벤트에서 필요한 원시 필드만 읽어 어댑터-로컬 스냅샷([I], JDA-free)으로 만든다.
 *  2. **매핑(toEvent)**: 스냅샷 → 도메인 이벤트. 순수 함수라 JDA 없이 단위 테스트할 수 있다.
 * [map] 은 이 둘을 합친 편의 메서드다. 스냅샷이 JDA-free 이므로 매핑 로직은 JDA mock 없이 검증된다.
 *
 * **acceptance(T002)**: [map] 의 반환은 순수 도메인 이벤트이며, 입력 스냅샷([I])은 어댑터-로컬 타입이라 JDA 참조를
 * 운반하지 않는다 — 도메인은 JDA 를 보지 않는다.
 *
 * @param J JDA 이벤트 타입(어댑터 입력).
 * @param I JDA-free 어댑터-로컬 스냅샷 타입(추출 산출물).
 */
interface DiscordEventMapper<J : Any, I : Any> {
    /** JDA 이벤트에서 필요한 원시 필드만 읽어 JDA-free 스냅샷으로 추출한다(유일하게 JDA 를 보는 단계). */
    fun extract(jdaEvent: J): I

    /** 스냅샷을 순수 도메인 정규화 이벤트로 매핑한다(JDA-free 순수 함수). */
    fun toEvent(snapshot: I): NormalizedDiscordEvent

    /** 추출 + 매핑 편의 메서드. 반환은 순수 도메인 이벤트(JDA 참조 없음). */
    fun map(jdaEvent: J): NormalizedDiscordEvent = toEvent(extract(jdaEvent))
}
