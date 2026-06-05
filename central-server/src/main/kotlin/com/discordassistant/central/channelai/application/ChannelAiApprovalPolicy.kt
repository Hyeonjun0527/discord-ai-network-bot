package com.discordassistant.central.channelai.application

import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.knowledge.application.KnowledgeSafety
import com.discordassistant.central.shared.ContentSafety.HIGH_RISK_SAFETY_LEVELS
import org.springframework.stereotype.Component

/**
 * 승인 필요 여부 결정 — 순수 결정 협력자(저장소·TX 없음, 입력만으로 판정).
 *
 * 위험/민감/장문/대형 헌법 분류와 승인 사유 폴백을 추출 전과 1바이트 불변으로 보존한다.
 * `requireApproval=true` 면 항상 검토 필요 불변식도 그대로 유지한다.
 */
@Component
class ChannelAiApprovalPolicy {
    fun approvalDecision(
        requestedApproval: Boolean,
        behavior: AiBehaviorVersionEntity,
        displayName: String,
    ): ApprovalDecision {
        // 위험/민감/장문/대형 헌법 분류는 requireApproval 여부와 무관하게 항상 계산한다.
        // (#1 이후 대시보드 wizard 는 항상 requireApproval=true 로 들어오는데, 예전처럼 여기서 즉시
        //  return 해 버리면 위험 분류가 죽고 proposal reason 이 전부 "manual approval requested" 가 돼
        //  검토 요약의 위험 집계(risky_instruction_pending 등)가 비어 버린다. 구체 위험 사유를 우선 보존.)
        val customInstruction = behavior.customInstruction.orEmpty()
        val text =
            listOf(
                displayName,
                behavior.purpose,
                behavior.tone,
                behavior.answerLength,
                behavior.constitution.orEmpty(),
                behavior.safetyLevel,
                customInstruction,
            ).joinToString("\n").lowercase()
        val riskReason =
            when {
                behavior.safetyLevel.lowercase() in HIGH_RISK_SAFETY_LEVELS -> "high risk safety level"
                behavior.answerLength.equals("long", ignoreCase = true) -> "long answer mode can increase provider load"
                // 위험어는 KnowledgeSafety.RISKY_INSTRUCTION_TERMS 단일 출처를 공유한다(OnboardingAnalyzer 와 동기화 — S2).
                KnowledgeSafety.looksRiskyInstruction(text) -> "risky channel AI instruction requires review"
                // 자유 지침에 토큰/비밀번호/개인키 같은 민감정보가 들어오면 자동 승인하지 않고 검토 큐로 보낸다.
                customInstruction.looksSensitive() -> "custom instruction contains sensitive material requires review"
                behavior.constitution.orEmpty().length > SAFE_CONSTITUTION_CHARS -> "large constitution requires review"
                else -> null
            }
        // 명시적 승인 요청이면 항상 검토 필요(required=true). 사유는 구체 위험 분류를 우선하고,
        // 위험 요소가 없으면 일반 "manual approval requested" 로 폴백한다.
        if (requestedApproval) {
            return ApprovalDecision(required = true, reason = riskReason ?: "manual approval requested")
        }
        return ApprovalDecision(required = riskReason != null, reason = riskReason)
    }

    /** 자유 지침 민감 판정은 프롬프트 렌더러와 동일한 정규식 단일 출처를 공유한다(드리프트 금지). */
    private fun String.looksSensitive(): Boolean {
        val text = trim()
        if (text.isBlank()) return false
        return ChannelAiPromptRenderer.SENSITIVE_PROMPT_PATTERNS.any { it.containsMatchIn(text) }
    }

    internal companion object {
        const val SAFE_CONSTITUTION_CHARS = 1200
    }
}

data class ApprovalDecision(
    val required: Boolean,
    val reason: String?,
)
