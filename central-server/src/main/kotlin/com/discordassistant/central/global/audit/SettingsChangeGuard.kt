package com.discordassistant.central.global.audit

/**
 * 설정 변경 optimistic lock + before/after audit(NEXA-P17-T007, security).
 *
 * 동시 관리자 변경 충돌을 버전 비교로 막고, 적용된 변경의 before/after 를 [SettingsChangeAudit] 로 남긴다.
 * stale 대시보드(오래된 버전을 들고 있는 클라이언트)가 최신 live 설정을 덮어쓰지 못하게 한다(acceptance T007).
 *
 * 순수 로직: Spring/JPA/JDA 미참조. 영속 버전·저장은 호출부(application/adapter)가 담당하고, 이 가드는 충돌
 * 판정·audit 레코드 생성만 한다(DRY·테스트 가능).
 */
class SettingsChangeGuard {
    /**
     * [expectedVersion](클라이언트가 읽은 버전)이 [currentVersion](현재 저장된 버전)과 다르면 충돌이므로
     * [StaleSettingsWriteException] 으로 거부한다(stale write 차단). 일치하면 변경을 적용한 것으로 보고
     * before/after audit 레코드와 다음 버전을 돌려준다.
     */
    fun <T> applyOrReject(
        settingKey: String,
        actor: String,
        expectedVersion: Long,
        currentVersion: Long,
        before: T,
        after: T,
    ): SettingsChangeResult<T> {
        if (expectedVersion != currentVersion) {
            throw StaleSettingsWriteException(
                settingKey = settingKey,
                expectedVersion = expectedVersion,
                currentVersion = currentVersion,
            )
        }
        val audit =
            SettingsChangeAudit(
                settingKey = settingKey,
                actor = actor,
                fromVersion = currentVersion,
                toVersion = currentVersion + 1,
                before = before?.toString() ?: "null",
                after = after?.toString() ?: "null",
            )
        return SettingsChangeResult(newVersion = currentVersion + 1, audit = audit)
    }
}

/** 충돌 없이 적용된 설정 변경 결과(다음 버전 + before/after audit). */
data class SettingsChangeResult<T>(
    val newVersion: Long,
    val audit: SettingsChangeAudit,
)

/** 설정 변경 1건의 before/after 감사 레코드(불변). 원문 비밀은 호출부가 마스킹해 넘긴다고 가정한다. */
data class SettingsChangeAudit(
    val settingKey: String,
    val actor: String,
    val fromVersion: Long,
    val toVersion: Long,
    val before: String,
    val after: String,
)

/** stale write 충돌 — 클라이언트가 읽은 버전이 현재와 다르다(최신 설정 덮어쓰기 차단). */
class StaleSettingsWriteException(
    val settingKey: String,
    val expectedVersion: Long,
    val currentVersion: Long,
) : RuntimeException(
        "설정 '$settingKey' 변경 충돌: 기대 버전 $expectedVersion, 현재 $currentVersion — 다시 불러와 재시도하라",
    )
