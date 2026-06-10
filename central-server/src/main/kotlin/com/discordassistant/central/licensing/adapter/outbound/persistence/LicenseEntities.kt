package com.discordassistant.central.licensing.adapter.outbound.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * licensing 도메인 JPA 엔티티(adapter/out). 스키마는 Flyway(V44~V46)가 소유, Hibernate 는 매핑만(ddl-auto=none).
 * 컬럼은 기본 snake_case 매핑. ADR 0005 — 풀 기여(ADR 0003)와 무관한 앱 제품 라이선스.
 */

@Entity
@Table(name = "user_license")
class LicenseEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(unique = true) var userId: Long = 0,
    var grantType: String = "NONE",
    var trialStartedAt: Instant = Instant.EPOCH,
    var grantedAt: Instant? = null,
    var revokedAt: Instant? = null,
    var refundFlag: Boolean = false,
    var banned: Boolean = false,
    var paddleCustomerId: String? = null,
    var paddleTransactionId: String? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "billing_event")
class BillingEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(unique = true) var eventId: String = "",
    var eventType: String = "",
    @Column(columnDefinition = "text") var raw: String? = null,
    var processedAt: Instant? = null,
    var error: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "license_audit")
class LicenseAuditEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var userId: Long = 0,
    var action: String = "",
    var actor: String = "",
    var detail: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)
