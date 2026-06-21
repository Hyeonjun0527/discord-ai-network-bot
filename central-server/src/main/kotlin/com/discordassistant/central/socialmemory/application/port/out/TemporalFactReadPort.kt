package com.discordassistant.central.socialmemory.application.port.out

import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact

/**
 * 사실 읽기 아웃바운드 포트(NEXA-P07-T018). 한 guild 스코프의 후보 사실(상태 무관)을 읽어 랭킹·다양성·필터는
 * 도메인 서비스(T019/T020)가 적용한다 — 어댑터는 가져오기만, 정책은 도메인.
 *
 * 순수 application: 도메인 타입만 본다 — JPA/JDA 타입 미참조.
 */
fun interface TemporalFactReadPort {
    /** [guildPseudonym] 스코프의 사실 후보(과거 회상 대비 상태/구간 무관 전체). 필터는 랭킹이 적용한다. */
    fun findCandidates(guildPseudonym: String): List<TemporalFact>
}
