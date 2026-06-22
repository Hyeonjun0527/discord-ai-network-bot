package com.discordassistant.central.global.crypto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P17-T005: 메시지 암호화 키 회전 — 구키 폐기 전 모든 활성 ciphertext 접근 가능성 검증. */
class VersionedFieldCryptoTest {
    private fun cryptoV1() = VersionedFieldCrypto(KeyRing.of(activeVersion = 1, rawKeys = mapOf(1 to "key-v1")))

    private fun cryptoV1V2() = VersionedFieldCrypto(KeyRing.of(activeVersion = 2, rawKeys = mapOf(1 to "key-v1", 2 to "key-v2")))

    @Test
    fun `encrypt then decrypt round trips`() {
        val c = cryptoV1()
        val ct = c.encrypt("비밀 설정")
        assertThat(ct).startsWith("enc2:1:")
        assertThat(c.decrypt(ct)).isEqualTo("비밀 설정")
    }

    @Test
    fun `old key ciphertext still decrypts after rotation`() {
        // v1 으로 암호화한 뒤 v2 로 회전해도 구키 ciphertext 가 복호된다(폐기 전 접근 가능성).
        val v1Ciphertext = cryptoV1().encrypt("과거 데이터")
        val rotated = cryptoV1V2()
        assertThat(rotated.decrypt(v1Ciphertext)).isEqualTo("과거 데이터")
        assertThat(rotated.versionOf(v1Ciphertext)).isEqualTo(1)
    }

    @Test
    fun `lazy reencrypt moves old ciphertext to active version`() {
        val v1Ciphertext = cryptoV1().encrypt("마이그레이션 대상")
        val rotated = cryptoV1V2()
        val migrated = rotated.reencryptToActive(v1Ciphertext)
        assertThat(rotated.versionOf(migrated)).isEqualTo(2)
        assertThat(rotated.decrypt(migrated)).isEqualTo("마이그레이션 대상")
    }

    @Test
    fun `cannot revoke while ciphertext on that version remains`() {
        val rotated = cryptoV1V2()
        val v1Ciphertext = cryptoV1().encrypt("아직 v1")
        // v1 ciphertext 가 남아 있으면 v1 폐기 불가(acceptance — 잔존 검증).
        assertThat(rotated.canRevoke(1, listOf(v1Ciphertext))).isFalse()
        // 재암호화 후에는 v1 으로 암호화된 것이 없어 폐기 가능.
        val migrated = rotated.reencryptToActive(v1Ciphertext)
        assertThat(rotated.canRevoke(1, listOf(migrated))).isTrue()
    }

    @Test
    fun `revoked key ciphertext fails closed on decrypt`() {
        val v1Ciphertext = cryptoV1().encrypt("폐기될 데이터")
        val ring = KeyRing.of(activeVersion = 2, rawKeys = mapOf(1 to "key-v1", 2 to "key-v2"))
        val revokedRing = ring.revoke(1)
        val crypto = VersionedFieldCrypto(revokedRing)
        // 폐기된 버전 복호는 조용한 평문 폴백이 아니라 예외(fail-closed).
        assertThatThrownBy { crypto.decrypt(v1Ciphertext) }.isInstanceOf(RevokedKeyException::class.java)
    }

    @Test
    fun `legacy enc1 ciphertext decrypts via legacy slot`() {
        // FieldCrypto(enc1) 로 만든 ciphertext 를 legacyKey 슬롯으로 복호(점진 마이그레이션).
        FieldCrypto.configure("legacy-key")
        val legacy = FieldCrypto.encrypt("레거시 평문")
        FieldCrypto.configure(null) // 다른 테스트 격리(전역 상태 복원)
        val ring = KeyRing.of(activeVersion = 1, rawKeys = mapOf(1 to "key-v1"), legacyKey = "legacy-key")
        val crypto = VersionedFieldCrypto(ring)
        assertThat(crypto.decrypt(legacy)).isEqualTo("레거시 평문")
        assertThat(crypto.versionOf(legacy)).isEqualTo(KeyRing.LEGACY_VERSION)
    }

    @Test
    fun `active key cannot be revoked`() {
        val ring = KeyRing.of(activeVersion = 1, rawKeys = mapOf(1 to "key-v1"))
        assertThatThrownBy { ring.revoke(1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
