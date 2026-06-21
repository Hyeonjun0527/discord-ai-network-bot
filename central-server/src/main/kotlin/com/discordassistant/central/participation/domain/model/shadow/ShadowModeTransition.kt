package com.discordassistant.central.participation.domain.model.shadow

import java.time.Instant

/**
 * shadow 단계 전이 규칙·감사(NEXA-P09-T007, 순수 도메인 서비스·불변 값).
 *
 * **acceptance(T007) — 상태 전이는 승인 권한과 audit 를 요구한다**:
 * - **승인 권한**: [transition] 은 [ShadowApprovalAuthority] 를 요구한다 — 권한 없는 주체는 전이할 수 없다.
 * - **audit**: 모든 성공 전이는 [ShadowModeAudit] 한 건을 만든다(누가·언제·무엇에서·무엇으로·사유). 호출자가
 *   이를 영속화한다.
 * - **위험 상향 가드**: 실제 전송을 켜는 상향([ShadowMode.allowsRealSend] false→true, 즉 CANARY/LIVE 진입)은
 *   더 강한 권한([ShadowApprovalAuthority.canEnableRealSend])을 요구한다. shadow 영역 안(OFF~SHADOW_PREDICT)
 *   이동은 일반 운영 권한으로 충분하다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 타입·표준 java.time 만.
 */
object ShadowModeTransition {
    /**
     * [from] → [to] 전이를 [authority] 권한으로 시도한다. 허용되면 audit 를 만들어 돌려주고, 권한 부족이면
     * [IllegalArgumentException] 을 던진다(fail-closed). 같은 단계로의 전이(no-op)는 금지한다(audit 노이즈 방지).
     */
    fun transition(
        from: ShadowMode,
        to: ShadowMode,
        authority: ShadowApprovalAuthority,
        guildPseudonym: String,
        actorId: String,
        reason: String,
        at: Instant,
    ): ShadowModeAudit {
        require(from != to) { "같은 단계로는 전이할 수 없다: $from" }
        require(reason.isNotBlank()) { "전이 사유(reason)는 비어 있을 수 없다" }
        require(authority.canManageShadow) { "shadow 단계 전이에는 운영 권한이 필요하다(actor=$actorId)" }
        // 실제 전송을 새로 켜는 상향은 더 강한 권한 필요(shadow→real send 진입 가드).
        val enablesRealSend = !from.allowsRealSend && to.allowsRealSend
        require(!enablesRealSend || authority.canEnableRealSend) {
            "실제 전송 활성화($from→$to)에는 별도 승인 권한이 필요하다(actor=$actorId)"
        }
        return ShadowModeAudit(
            guildPseudonym = guildPseudonym,
            actorId = actorId,
            from = from,
            to = to,
            reason = reason,
            enabledRealSend = enablesRealSend,
            at = at,
        )
    }
}

/**
 * shadow 단계 전이 승인 권한(순수 도메인 값 객체). 운영자 신원에서 유도된 권한 플래그만 담는다(원문/토큰 비포함).
 * [com.discordassistant.central.global.security.DashboardActor] 같은 인증 주체를 어댑터가 이 권한으로 번역한다.
 */
data class ShadowApprovalAuthority(
    /** shadow 영역(OFF~SHADOW_PREDICT) 단계 관리 권한이 있는가. */
    val canManageShadow: Boolean,
    /** 실제 전송(CANARY/LIVE) 활성화 권한이 있는가 — 더 강한 승인. */
    val canEnableRealSend: Boolean,
) {
    companion object {
        /** 권한 없음(어떤 전이도 불가). fail-closed 기본. */
        val NONE: ShadowApprovalAuthority = ShadowApprovalAuthority(canManageShadow = false, canEnableRealSend = false)
    }
}

/**
 * shadow 단계 전이 감사 레코드(순수 도메인 값 객체·불변). 누가·언제·무엇에서·무엇으로·왜 단계를 바꿨는지 남긴다
 * (acceptance T007 — audit). 어댑터가 영속화한다(원문 비포함 — 가명·안정 코드만).
 */
data class ShadowModeAudit(
    val guildPseudonym: String,
    val actorId: String,
    val from: ShadowMode,
    val to: ShadowMode,
    val reason: String,
    val enabledRealSend: Boolean,
    val at: Instant,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(actorId.isNotBlank()) { "actorId 는 비어 있을 수 없다" }
        require(reason.isNotBlank()) { "reason 은 비어 있을 수 없다" }
    }
}
