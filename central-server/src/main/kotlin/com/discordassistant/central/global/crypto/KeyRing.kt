package com.discordassistant.central.global.crypto

import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

/**
 * 버전 있는 암호화 키 묶음(NEXA-P17-T005, security·key rotation). 기존 [FieldCrypto](단일 키) 는 건드리지 않고
 * 회전 메커니즘을 새 코드로 추가한다([key-rotation.md](../../../../../../../../docs/nexa/security/key-rotation.md)).
 *
 * - **active**: 신규 암호화에 쓰는 단 하나의 키 버전.
 * - **retired**: 더는 암호화에 안 쓰지만 과거 ciphertext 복호를 위해 남은 키 버전.
 * - **revoked**: keyring 에서 제거(폐기)된 버전 — 그 버전 ciphertext 는 복호 불가([RevokedKeyException]).
 *
 * 키 raw bytes 는 외부로 노출하지 않는다(버전 번호만). 키는 raw 문자열을 SHA-256 으로 파생한 AES 키로 보관한다
 * (FieldCrypto 와 동일 파생 — 레거시 `enc1:` 호환).
 */
class KeyRing private constructor(
    val activeVersion: Int,
    private val keys: Map<Int, SecretKeySpec>,
    private val revoked: Set<Int>,
) {
    /** 알려진(복호 가능) 키 버전 — active + retired(revoked 제외). */
    fun knownVersions(): Set<Int> = keys.keys.toSet()

    /** [version] 이 폐기됐는지. */
    fun isRevoked(version: Int): Boolean = version in revoked

    /** active 키(암호화용). */
    fun activeKey(): SecretKeySpec = keys.getValue(activeVersion)

    /**
     * [version] 의 복호 키를 돌려준다. 폐기된 버전이면 [RevokedKeyException], 모르는 버전이면 [UnknownKeyVersionException].
     */
    fun keyFor(version: Int): SecretKeySpec {
        if (version in revoked) throw RevokedKeyException(version)
        return keys[version] ?: throw UnknownKeyVersionException(version)
    }

    /**
     * [version] 키를 폐기한 새 keyring 을 만든다. active 키는 폐기할 수 없다(회전으로 retired 가 된 뒤에만 폐기).
     * 호출 전 [com.discordassistant.central.global.crypto.VersionedFieldCrypto.canRevoke] 로 잔존 ciphertext 0 을 확인해야 한다.
     */
    fun revoke(version: Int): KeyRing {
        require(version != activeVersion) { "active 키 버전($version)은 폐기할 수 없다 — 먼저 회전하라" }
        require(version in keys) { "모르는 키 버전은 폐기할 수 없다: $version" }
        return KeyRing(
            activeVersion = activeVersion,
            keys = keys - version,
            revoked = revoked + version,
        )
    }

    companion object {
        /** 레거시 `enc1:`(버전 없음) ciphertext 가 매핑되는 가상 버전 번호. */
        const val LEGACY_VERSION: Int = 0

        private fun deriveKey(raw: String): SecretKeySpec =
            SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)), "AES")

        /**
         * raw 키 문자열 맵으로 keyring 을 만든다. [activeVersion] 이 맵에 있어야 한다.
         * [legacyKey] 가 주어지면 [LEGACY_VERSION] 슬롯으로 등록해 레거시 ciphertext 를 복호할 수 있게 한다.
         */
        fun of(
            activeVersion: Int,
            rawKeys: Map<Int, String>,
            legacyKey: String? = null,
        ): KeyRing {
            require(rawKeys.isNotEmpty()) { "keyring 은 최소 한 개의 키를 가져야 한다" }
            require(activeVersion in rawKeys) { "active 버전($activeVersion)이 keyring 에 없다" }
            require(activeVersion != LEGACY_VERSION) { "LEGACY_VERSION 은 active 가 될 수 없다" }
            val derived = rawKeys.mapValues { (_, raw) -> deriveKey(raw) }.toMutableMap()
            if (legacyKey != null && legacyKey.isNotBlank()) {
                derived[LEGACY_VERSION] = deriveKey(legacyKey)
            }
            return KeyRing(activeVersion, derived.toMap(), emptySet())
        }
    }
}

/** 폐기된 키 버전으로 암호화된 ciphertext 복호 시도(fail-closed — 조용한 평문 폴백 금지). */
class RevokedKeyException(
    val version: Int,
) : RuntimeException("키 버전 $version 은 폐기됨 — 복호 불가(폐기 전 재암호화 누락)")

/** keyring 에 없는 키 버전 참조. */
class UnknownKeyVersionException(
    val version: Int,
) : RuntimeException("알 수 없는 키 버전: $version")
