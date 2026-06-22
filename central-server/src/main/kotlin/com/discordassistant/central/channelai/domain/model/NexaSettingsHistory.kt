package com.discordassistant.central.channelai.domain.model

import java.time.Instant

/**
 * 길드 NEXA 설정의 version history 와 rollback 의 **순수 결정 코어**(NEXA-P18-T016, 순수 도메인).
 *
 * 길드의 NEXA 설정(참여 강도·발화 빈도 같은 운영 가능 값)이 바뀔 때마다 한 version 을 append 한다. rollback 은
 * **이전 version 의 값으로 새 version 을 더하는** 전방 복구다(deliverable T016 — version history 와 이전값 복구).
 * 과거 version 을 지우지 않으므로 audit 가 보존된다.
 *
 * **acceptance(T016) — 동의 철회 상태는 단순 설정 rollback 으로 되돌릴 수 없다**:
 * [rollBackTo] 는 [NexaSettingsSnapshot.consentRevoked] 가 **현재 true 면 rollback 을 거부**한다
 * ([ConsentLockedException]). 동의 철회는 설정 토글이 아니라 사용자 권리 상태라, "예전 설정으로 되돌리기" 로
 * 다시 켜질 수 없다(되살리려면 별도 명시 재동의 경로 — privacy/consent 도메인). 즉 설정 rollback 은 동의가 살아
 * 있을 때만 의미를 갖고, 철회된 동의는 절대 설정 시간여행으로 복원되지 않는다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 타입만. version 목록은 호출자(서비스)가 SSOT 에서 로드해 넘긴다.
 */
object NexaSettingsHistory {
    /**
     * [history](append-only, version 오름차순)에서 [targetVersion] 의 값으로 rollback 한 **새 snapshot** 을 만든다.
     * 새 version 번호는 현재 최신 + 1(전방 복구 — 과거를 지우지 않는다).
     *
     * 거부 규칙:
     *  - [history] 가 비어 있거나 [targetVersion] 이 없으면 [SettingsRollbackException](되돌릴 대상 부재).
     *  - 현재(최신) snapshot 의 [NexaSettingsSnapshot.consentRevoked] 가 true 면 [ConsentLockedException]
     *    (acceptance T016 — 동의 철회 상태는 단순 설정 rollback 으로 되돌릴 수 없다).
     *
     * 복구되는 값은 [NexaSettingsSnapshot.values] 뿐이다 — [NexaSettingsSnapshot.consentRevoked] 는 **항상 현재
     * 상태를 유지**한다(과거 "동의 살아있던" 상태를 끌어오지 않는다).
     */
    fun rollBackTo(
        history: List<NexaSettingsSnapshot>,
        targetVersion: Int,
        actor: String,
        at: Instant,
    ): NexaSettingsSnapshot {
        val current =
            history.maxByOrNull { it.version }
                ?: throw SettingsRollbackException("설정 history 가 비어 있다 — rollback 대상 부재")
        if (current.consentRevoked) {
            throw ConsentLockedException(
                "동의 철회 상태는 단순 설정 rollback 으로 되돌릴 수 없다(version=${current.version}) — 명시 재동의 필요",
            )
        }
        val target =
            history.firstOrNull { it.version == targetVersion }
                ?: throw SettingsRollbackException("rollback 대상 version 이 없다: $targetVersion")
        // 전방 복구: target 의 *값* 으로 새 version 을 더한다. consentRevoked 는 현재 상태(false)를 유지한다.
        return NexaSettingsSnapshot(
            version = current.version + 1,
            values = target.values,
            consentRevoked = current.consentRevoked,
            actor = actor,
            reason = "rollback-to-v$targetVersion",
            at = at,
        )
    }
}

/**
 * 한 길드 NEXA 설정의 한 version snapshot(순수 도메인 값 객체·불변). version history 의 한 행이다.
 *
 * [consentRevoked] 는 설정 값이 아니라 사용자 권리 상태의 미러다 — rollback 가드가 이걸 본다(acceptance T016).
 * [values] 는 운영 가능한 저카디널리티 설정(키→안정 코드/숫자 문자열) 맵으로, 원문/PII 를 담지 않는다.
 */
data class NexaSettingsSnapshot(
    /** version 번호(1부터 증가, append-only). */
    val version: Int,
    /** 운영 설정 값(키→저카디널리티 값). 원문/PII 비포함. */
    val values: Map<String, String>,
    /** 이 시점에 길드 동의가 철회 상태인가 — true 면 설정 rollback 으로 되돌릴 수 없다(acceptance T016). */
    val consentRevoked: Boolean,
    /** 이 version 을 만든 주체 식별 코드(원문 user id 아님). */
    val actor: String,
    /** 변경 사유(저카디널리티 코드/짧은 설명). */
    val reason: String,
    val at: Instant,
) {
    init {
        require(version >= 1) { "version 은 1 이상이어야 한다: $version" }
        require(actor.isNotBlank()) { "actor 는 비어 있을 수 없다" }
    }
}

/** 설정 rollback 불변식 위반(NEXA-P18-T016) — 대상 version 부재 등. */
class SettingsRollbackException(
    message: String,
) : RuntimeException(message)

/**
 * 동의 철회 잠금(NEXA-P18-T016 acceptance). 동의 철회 상태에서 설정 rollback 을 시도하면 던진다 — 철회는 단순
 * 설정 시간여행으로 되돌릴 수 없다(명시 재동의 경로만).
 */
class ConsentLockedException(
    message: String,
) : RuntimeException(message)
