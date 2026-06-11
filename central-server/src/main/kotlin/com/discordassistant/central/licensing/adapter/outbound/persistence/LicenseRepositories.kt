package com.discordassistant.central.licensing.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * licensing 도메인 Spring Data JPA 리포지토리(adapter/out). 파생 쿼리만 사용(JPQL 없음).
 * 동시성/원자 갱신이 필요한 흐름은 application 서비스가 트랜잭션으로 감싼다.
 */

interface LicenseRepository : JpaRepository<LicenseEntity, Long> {
    fun findByUserId(userId: Long): LicenseEntity?

    fun countByGrantType(grantType: String): Long

    /** Paddle customer 로 역조회(환불 webhook 에 custom_data 가 없을 때 매칭). */
    fun findByPaddleCustomerId(paddleCustomerId: String): List<LicenseEntity>
}

interface BillingEventRepository : JpaRepository<BillingEventEntity, Long> {
    fun findByEventId(eventId: String): BillingEventEntity?

    fun existsByEventId(eventId: String): Boolean
}

interface LicenseAuditRepository : JpaRepository<LicenseAuditEntity, Long>
