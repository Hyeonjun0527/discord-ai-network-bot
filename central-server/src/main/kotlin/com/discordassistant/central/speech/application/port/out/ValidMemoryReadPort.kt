package com.discordassistant.central.speech.application.port.out

/**
 * speech 가 P07 social memory 의 **현재-유효 기억을 읽기만** 하는 아웃바운드 포트(NEXA-P14-T008).
 *
 * socialmemory 의 current-valid retrieval(P07-T018)을 speech 어휘([CandidateMemory])로 읽는다 — 구현 adapter
 * 안에서만 socialmemory 타입을 참조하고 speech application/domain 은 socialmemory 타입을 import 하지 않는다.
 * 포트는 유효 기억만 노출할 책임을 지고, selector(T008)가 conflicted/expired/deleted 를 한 번 더 제외한다.
 */
interface ValidMemoryReadPort {
    /**
     * [guildId]·[targetPseudonym] 스코프의 후보 기억을 confidence 내림차순으로 돌려준다(최대 [limit] 개 후보).
     * 가명 키만 받고, 원문이 아닌 추출된 주장만 노출한다(payload 최소화).
     */
    fun candidateMemories(
        guildId: Long,
        targetPseudonym: String?,
        limit: Int,
    ): List<CandidateMemory>
}

/** 읽어 온 후보 기억(NEXA-P14-T008). 주장 + provenance + confidence + 유효 상태. */
data class CandidateMemory(
    val claim: String,
    val provenance: String,
    val confidence: Double,
    val status: MemoryStatus,
) {
    init {
        require(claim.isNotBlank()) { "claim 은 비어 있을 수 없다" }
        require(provenance.isNotBlank()) { "provenance 는 비어 있을 수 없다" }
        require(confidence in 0.0..1.0) { "confidence 는 [0,1] 범위여야 한다: $confidence" }
    }
}

/**
 * 기억 유효 상태(NEXA-P14-T008). speech 는 [VALID]·[DECAYING] 만 발화에 쓰고, 나머지는 제외한다.
 * (P07 valid/decay 만 사용 — 만료/충돌/삭제/옵트아웃 제외.)
 */
enum class MemoryStatus {
    /** 현재 유효. */
    VALID,

    /** 감쇠 중이지만 아직 유효(decay). 낮은 가중으로 사용 가능. */
    DECAYING,

    /** 만료됨 — 제외. */
    EXPIRED,

    /** 충돌(supersession 등)로 무효 — 제외. */
    CONFLICTED,

    /** 삭제/옵트아웃 — 제외. */
    DELETED,
    ;

    /** 발화에 사용할 수 있는 유효 상태인가(valid/decay 만). */
    val isUsable: Boolean
        get() = this == VALID || this == DECAYING
}
