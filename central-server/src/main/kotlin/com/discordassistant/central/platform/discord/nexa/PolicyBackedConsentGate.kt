package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import com.discordassistant.central.global.privacy.ConsentGate
import com.discordassistant.central.global.privacy.ConsentRevokedException
import com.discordassistant.central.global.privacy.ProcessingStage
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * 실제 consent 경로([ConsentPolicyPort]) 뒤로 통합된 동의 게이트(NEXA-P17 보안 enforcement seam, platform 어댑터).
 *
 * security-reviewer 지적: [ConsentGate] 가 in-memory **평행 구현**이라 실제 live consent(`ConsentPolicyPort` →
 * 길드 활성/옵트아웃/채널 스코프 합성)와 분리돼 있었다. 이 어댑터는 둘을 **단일 consent 판정**으로 묶는다 —
 * 발화 파이프라인의 동의 검사가 ingestion 과 **같은** `ConsentPolicyPort` 를 읽으므로, 동의 철회/옵트아웃/채널
 * 비허용이 speech-emit seam 에서 **실제로** 차단된다(별도 grant() 상태가 아니라 정책 원본을 매 호출 조회).
 *
 * [subjectPseudonym] 은 speech-emit seam 이 [pseudonymOf] 로 만든 비가역 token이다. raw Discord 식별자는
 * process-local 보호 lookup에만 짧게 남고 DB 예약/outbox에는 token만 저장된다. 재시작 뒤 lookup이 없으면 과거 예약은
 * 복원하지 않고 fail-closed한다.
 * 동의가 없으면(또는 관찰/발화 미허용) [ConsentRevokedException] 으로 fail-closed 차단한다(grant/revoke 의 메모리
 * 상태가 아니라 정책 원본 기준 — 철회와 동시에 효력, scheduler tick 비대기).
 *
 * 발화 단계별 의미:
 *  - [ProcessingStage.SPEECH_GENERATION]: 관찰 동의가 있어야 후보 생성을 시작한다(관찰 없이는 발화 불가).
 *  - [ProcessingStage.EXTERNAL_GLM_REQUEST]: **발화** 동의(speechAllowed)까지 있어야 외부 전송 직전을 통과한다 —
 *    OBSERVE_ONLY(관찰만 허용) 채널은 여기서 차단되어 외부 전송이 0 이 된다(hard block 유지).
 *
 * 순수성 경계: platform 어댑터 — application 포트([ConsentPolicyPort])와 global 동의 게이트만 본다.
 */
class PolicyBackedConsentGate(
    private val consentPolicy: ConsentPolicyPort,
) : ConsentGate() {
    /**
     * [subjectPseudonym] token을 process-local 보호 lookup으로 해석해 [ConsentPolicyPort] 로 **live** 동의를 조회하고,
     * [stage] 위험 지점에서 허용되지 않으면 [ConsentRevokedException] 으로 차단한다. 생성 단계는 관찰 동의,
     * 외부 전송 단계는 발화 동의까지 요구한다. 키 형식이 깨졌으면(파싱 실패) 보수적으로 차단한다(fail-closed).
     */
    override fun checkAllowed(
        subjectPseudonym: String,
        stage: ProcessingStage,
    ) {
        val decision =
            resolveSubjectToken(subjectPseudonym)?.let { (guildId, userId, channelId) ->
                consentPolicy.observationDecision(guildId = guildId, userId = userId, channelId = channelId)
            } ?: throw ConsentRevokedException(subjectPseudonym = subjectPseudonym, stage = stage)

        val allowed =
            when (stage) {
                ProcessingStage.SPEECH_GENERATION -> decision.observationAllowed
                ProcessingStage.EXTERNAL_GLM_REQUEST -> decision.speechAllowed
                // 그 외 단계(INGESTION/TRAINING_EXPORT)도 관찰 동의 기준으로 안전 판정.
                else -> decision.observationAllowed
            }
        if (!allowed) {
            throw ConsentRevokedException(subjectPseudonym = subjectPseudonym, stage = stage)
        }
    }

    companion object {
        private const val MAX_ACTIVE_TOKENS: Int = 20_000
        private val activeSubjects =
            object : LinkedHashMap<String, Triple<Long, Long, Long>>(256, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<Long, Long, Long>>?): Boolean =
                    size > MAX_ACTIVE_TOKENS
            }

        /**
         * speech-emit seam 이 (guild, user, channel) 로부터 역산할 수 없는 동의 token을 만든다.
         * raw scope는 현재 process의 bounded lookup에만 남고 영속 저장소에는 token만 전달된다.
         */
        fun pseudonymOf(
            guildId: Long,
            userId: Long,
            channelId: Long,
        ): String {
            val guildRef = ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId, guildId)
            val userRef = ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId, userId)
            val channelRef = ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId, channelId)
            val token = "consent:" + sha256("$guildRef:$userRef:$channelRef")
            synchronized(activeSubjects) {
                activeSubjects[token] = Triple(guildId, userId, channelId)
            }
            return token
        }

        /** 같은 process에서 만든 token만 raw consent scope로 해석한다. DB token 단독으로는 역산할 수 없다. */
        fun resolveSubjectToken(token: String): Triple<Long, Long, Long>? = synchronized(activeSubjects) { activeSubjects[token] }

        private fun sha256(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
