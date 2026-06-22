package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.global.privacy.ConsentGate
import com.discordassistant.central.global.privacy.ConsentRevokedException
import com.discordassistant.central.global.privacy.ProcessingStage

/**
 * 실제 consent 경로([ConsentPolicyPort]) 뒤로 통합된 동의 게이트(NEXA-P17 보안 enforcement seam, platform 어댑터).
 *
 * security-reviewer 지적: [ConsentGate] 가 in-memory **평행 구현**이라 실제 live consent(`ConsentPolicyPort` →
 * 길드 활성/옵트아웃/채널 스코프 합성)와 분리돼 있었다. 이 어댑터는 둘을 **단일 consent 판정**으로 묶는다 —
 * 발화 파이프라인의 동의 검사가 ingestion 과 **같은** `ConsentPolicyPort` 를 읽으므로, 동의 철회/옵트아웃/채널
 * 비허용이 speech-emit seam 에서 **실제로** 차단된다(별도 grant() 상태가 아니라 정책 원본을 매 호출 조회).
 *
 * [subjectPseudonym] 은 speech-emit seam 이 [pseudonymOf] 로 만든 `guildId:userId:channelId` 합성 키다 —
 * [checkAllowed] 가 이를 분해해 [ConsentPolicyPort.observationDecision] 으로 **그 시점** 동의 상태를 묻는다.
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
     * [subjectPseudonym](=`guildId:userId:channelId`)을 분해해 [ConsentPolicyPort] 로 **live** 동의를 조회하고,
     * [stage] 위험 지점에서 허용되지 않으면 [ConsentRevokedException] 으로 차단한다. 생성 단계는 관찰 동의,
     * 외부 전송 단계는 발화 동의까지 요구한다. 키 형식이 깨졌으면(파싱 실패) 보수적으로 차단한다(fail-closed).
     */
    override fun checkAllowed(
        subjectPseudonym: String,
        stage: ProcessingStage,
    ) {
        val decision =
            decodeKey(subjectPseudonym)?.let { (guildId, userId, channelId) ->
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

    private fun decodeKey(subjectPseudonym: String): Triple<Long, Long, Long>? {
        val parts = subjectPseudonym.split(KEY_DELIMITER)
        if (parts.size != KEY_PARTS) return null
        val guildId = parts[0].toLongOrNull() ?: return null
        val userId = parts[1].toLongOrNull() ?: return null
        val channelId = parts[2].toLongOrNull() ?: return null
        return Triple(guildId, userId, channelId)
    }

    companion object {
        private const val KEY_DELIMITER = ":"
        private const val KEY_PARTS = 3

        /**
         * speech-emit seam 이 (guild, user, channel) 로부터 [checkAllowed] 가 분해 가능한 합성 가명 키를 만든다 —
         * 동의 판정의 단일 키(이 게이트와 seam 이 같은 형식을 공유).
         */
        fun pseudonymOf(
            guildId: Long,
            userId: Long,
            channelId: Long,
        ): String = "$guildId$KEY_DELIMITER$userId$KEY_DELIMITER$channelId"
    }
}
