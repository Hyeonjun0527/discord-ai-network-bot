package com.discordassistant.central.speech.application.context

import com.discordassistant.central.speech.application.port.out.CandidateMemory
import com.discordassistant.central.speech.application.port.out.ValidMemoryReadPort
import com.discordassistant.central.speech.domain.model.MemoryRef
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 유효 기억 selector(NEXA-P14-T008, application).
 *
 * P07 current-valid retrieval 에서 **소수의 기억**을 provenance·confidence 로 선택한다. valid/decay 만 쓰고
 * 만료·충돌·삭제·옵트아웃은 제외한다.
 *
 * **acceptance(T008) — conflicted/expired/deleted 기억이 제외된다**: selector 는 [CandidateMemory.status] 가
 * usable(valid/decay)이 아닌 원소를 명시적으로 제거하고, confidence 하한 미만도 거른다(약한 근거 차단). 따라서
 * 무효 상태 기억은 어떤 경우에도 [MemoryRef] 로 패킷에 들어가지 않는다.
 */
class MemoryContextSelector(
    private val memoryReader: ValidMemoryReadPort,
) {
    /**
     * [guildId]·[targetPseudonym] 스코프의 유효 기억을 confidence 내림차순으로 최대 [SpeechScenePacket.MAX_MEMORY_REFS]
     * 개 고른다. usable 상태가 아니거나 [minConfidence] 미만이면 제외한다.
     */
    fun select(
        guildId: Long,
        targetPseudonym: String?,
        minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    ): List<MemoryRef> {
        require(minConfidence in 0.0..1.0) { "minConfidence 는 [0,1] 범위여야 한다: $minConfidence" }
        return memoryReader
            .candidateMemories(guildId, targetPseudonym, FETCH_LIMIT)
            .asSequence()
            .filter { it.status.isUsable } // 만료/충돌/삭제/옵트아웃 제외(acceptance T008).
            .filter { it.confidence >= minConfidence } // 약한 근거 차단.
            .sortedByDescending { it.confidence }
            .take(SpeechScenePacket.MAX_MEMORY_REFS)
            .map { it.toRef() }
            .toList()
    }

    private fun CandidateMemory.toRef(): MemoryRef = MemoryRef(claim = claim, provenance = provenance, confidence = confidence)

    companion object {
        /** 약한 근거 차단 기본 하한. */
        const val DEFAULT_MIN_CONFIDENCE: Double = 0.4

        /** 포트에서 미리 가져올 후보 수(필터 후 상한까지 추림). */
        const val FETCH_LIMIT: Int = 32
    }
}
