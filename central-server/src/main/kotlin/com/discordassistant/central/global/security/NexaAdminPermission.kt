package com.discordassistant.central.global.security

/**
 * NEXA 관리자 권한 분리(NEXA-P17-T006, security·RBAC).
 * SSOT: [admin-permissions.md](../../../../../../../../specs/product-v2/nexa/admin-permissions.md).
 *
 * 조회·설정·live 전환·데이터 export·삭제·모델 승인을 각각 독립 권한으로 분리한다. 권한 검사는 역할이 아니라
 * 개별 [NexaAdminPermission] 으로 한다 — 단일 Discord 관리자 종류에 모든 고위험 권한이 자동으로 쏠리지 않게.
 */
enum class NexaAdminPermission(
    val highRisk: Boolean,
) {
    /** NEXA 설정·상태 조회(읽기 전용). */
    VIEW_SETTINGS(highRisk = false),

    /** 페르소나·자유지침·채널 모드 등 설정 변경. */
    EDIT_SETTINGS(highRisk = false),

    /** shadow ↔ live 전환(실제 발화 송출 on/off). */
    TOGGLE_LIVE(highRisk = true),

    /** 사용자 데이터 export 실행. */
    EXPORT_DATA(highRisk = true),

    /** 삭제 요청 orchestration 실행. */
    DELETE_DATA(highRisk = true),

    /** 학습 model 승인·배포. */
    APPROVE_MODEL(highRisk = true),

    ;

    companion object {
        /** 고위험 권한 집합(어느 단일 역할도 이 전부를 가질 수 없다). */
        val HIGH_RISK: Set<NexaAdminPermission> = entries.filter { it.highRisk }.toSet()
    }
}

/**
 * NEXA 관리 역할(NEXA-P17-T006). 각 역할은 권한 부분집합을 갖는다. 어떤 단일 역할도 [NexaAdminPermission.HIGH_RISK]
 * 전부를 갖지 않는다(acceptance — admin-permissions.md 불변식 2).
 */
enum class NexaAdminRole(
    val permissions: Set<NexaAdminPermission>,
) {
    VIEWER(setOf(NexaAdminPermission.VIEW_SETTINGS)),
    OPERATOR(setOf(NexaAdminPermission.VIEW_SETTINGS, NexaAdminPermission.EDIT_SETTINGS)),
    LIVE_OPERATOR(
        setOf(
            NexaAdminPermission.VIEW_SETTINGS,
            NexaAdminPermission.EDIT_SETTINGS,
            NexaAdminPermission.TOGGLE_LIVE,
        ),
    ),
    DATA_OFFICER(
        setOf(
            NexaAdminPermission.VIEW_SETTINGS,
            NexaAdminPermission.EXPORT_DATA,
            NexaAdminPermission.DELETE_DATA,
        ),
    ),
    MODEL_APPROVER(
        setOf(
            NexaAdminPermission.VIEW_SETTINGS,
            NexaAdminPermission.APPROVE_MODEL,
        ),
    ),

    ;

    companion object {
        /**
         * Discord `MANAGE_SERVER` 관리자에게 자동 부여되는 **기본** 역할. 고위험 권한 0개([OPERATOR]) —
         * Discord 관리자 한 종류에 모든 고위험 권한을 자동 부여하지 않는다(acceptance). 고위험 권한은 명시적
         * 역할 부여로만 얻는다.
         */
        val DISCORD_MANAGER_DEFAULT: NexaAdminRole = OPERATOR
    }
}
