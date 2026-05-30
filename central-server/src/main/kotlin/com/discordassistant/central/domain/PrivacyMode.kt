package com.discordassistant.central.domain

/**
 * 프라이버시 처리 주체 표시 모드 (specs: `DM-V-PrivacyMode`). 기본 [C_ADMIN_ONLY].
 *
 * 공유/Pool 모드에서 질문이 프로바이더 PC 로 전송될 수 있으므로 처리 주체 노출 정책을 정한다.
 */
enum class PrivacyMode {
    /** 익명: 모델 수준만 표시. */
    A_ANONYMOUS,

    /** 부분 공개: 커뮤니티 프로바이더 처리 + 처리 위치. */
    B_PARTIAL,

    /** 관리자만 provider 식별, 일반 유저에겐 풀 처리로만 표시(기본·추천). */
    C_ADMIN_ONLY,
    ;

    companion object {
        val DEFAULT = C_ADMIN_ONLY
    }
}
