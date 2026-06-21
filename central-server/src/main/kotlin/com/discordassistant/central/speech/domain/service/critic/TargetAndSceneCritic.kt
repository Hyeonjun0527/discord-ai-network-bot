package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechTarget

/**
 * 대상·장면 적합성 비평가(NEXA-P14-T020, 순수 도메인 서비스).
 *
 * 후보가 선택된 target/thread 에 맞는지, **다른 대화(cross-thread)** 를 끌어오지 않는지 평가한다. 후보가 현재 장면에
 * 없는 외부 thread/대상 가명 키를 언급하면(다른 스레드 끌어오기) 탈락시킨다. 가명 키는 minimizer(T005)가 부여한
 * "thread_*"·"user_*" 형태라 이를 패턴으로 잡는다.
 *
 * **acceptance(T020) — cross-thread reference fixture 가 탈락한다**: 후보 텍스트에서 가명 thread/user 키를 뽑아,
 * 현재 [SpeechScenePacket.focusThreadKey]·[SpeechTarget.pseudonymKey]·recentTurns 화자에 **없는** 키가 있으면
 * [CriticReason.TARGET_OR_SCENE_MISMATCH] 으로 탈락한다. 장면 안의 키만 언급하면 통과한다.
 *
 * 순수성: Spring/JPA/JDA 미참조. speech 도메인 타입·표준 타입만.
 */
class TargetAndSceneCritic : SpeechCritic {
    override fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict {
        val text = candidate.joined
        if (text.isBlank()) return CriticVerdict.ACCEPTED

        val sceneKeys = sceneKeys(packet)
        val referencedKeys = PSEUDONYM_KEY.findAll(text).map { it.value }.toSet()
        val foreign = referencedKeys.filter { it !in sceneKeys }
        return if (foreign.isNotEmpty()) {
            CriticVerdict.reject(CriticReason.TARGET_OR_SCENE_MISMATCH)
        } else {
            CriticVerdict.ACCEPTED
        }
    }

    /** 현재 장면에 정당하게 등장하는 가명 키 집합(focus thread + target + 최근 화자). */
    private fun sceneKeys(packet: SpeechScenePacket): Set<String> {
        val keys = mutableSetOf(packet.focusThreadKey)
        packet.target
            .takeIf { it.kind != SpeechTarget.Kind.NONE }
            ?.pseudonymKey
            ?.let { keys += it }
        packet.recentTurns.forEach { keys += it.speakerLabel }
        return keys
    }

    companion object {
        /** minimizer 가 부여하는 가명 키 형태("thread_3"·"user_12"). 외부 키 끌어오기 탐지에 쓴다. */
        private val PSEUDONYM_KEY = Regex("\\b(?:thread|user)_\\d+\\b")
    }
}
