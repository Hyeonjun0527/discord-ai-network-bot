package com.discordassistant.central.licensing.application.port

import java.time.Instant

/**
 * 유저가 서비스에 처음 가입(최초 provider 등록 = 계정 연결)한 시각을 제공하는 아웃바운드 포트.
 * 체험 시계의 시작점(ADR 0005, P3: 유저 가입 시점부터 3개월). 구현은 provider 도메인을 조회한다.
 */
fun interface UserFirstSeenPort {
    /** [userId]의 최초 가입 시각. 미가입(앱만 설치)이면 null. */
    fun firstSeenAt(userId: Long): Instant?
}
