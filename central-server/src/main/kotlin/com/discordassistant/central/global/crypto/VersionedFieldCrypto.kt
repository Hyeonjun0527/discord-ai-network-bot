package com.discordassistant.central.global.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * 버전 있는 필드 암호화(NEXA-P17-T005, security·key rotation). 키 회전·lazy re-encryption·폐기 안전성을 제공한다.
 * 기존 [FieldCrypto] 는 무변경 — 이 클래스는 [KeyRing] 으로 회전 가능한 새 경로다.
 *
 * - 형식: `enc2:<keyVersion>:` + base64(iv(12B) ‖ GCM ciphertext+tag).
 * - 레거시 `enc1:`(버전 없음) ciphertext 는 [KeyRing.LEGACY_VERSION] 키로 복호한다(점진 마이그레이션).
 * - revoked 버전 ciphertext 복호는 [RevokedKeyException] 으로 실패(조용한 평문 폴백 금지 — fail-closed).
 *
 * **acceptance(T005) — 구키 폐기 전 모든 활성 ciphertext 접근 가능성 검증**:
 *  - 회전(새 active 추가) 후에도 구키 ciphertext 가 [decrypt] 로 복호된다.
 *  - [reencryptToActive] 로 구키 ciphertext 를 active 로 옮기고, [versionOf] 로 active 임을 확인한다.
 *  - [canRevoke] 가 "그 버전으로 암호화된 ciphertext 가 더 없는지" 를 호출자가 일괄 확인하게 돕는다.
 *  - revoke 후 그 버전 ciphertext 복호는 [RevokedKeyException] 으로 실패한다.
 */
class VersionedFieldCrypto(
    private val keyRing: KeyRing,
) {
    private val rng = SecureRandom()

    /** 평문을 active 키로 암호화한다(`enc2:<active>:` 접두). */
    fun encrypt(plain: String?): String? {
        if (plain == null) return null
        return encryptWith(plain, keyRing.activeVersion, keyRing.activeKey())
    }

    /** ciphertext 를 해당 버전 키로 복호한다. 레거시/버전 ciphertext 를 모두 처리한다. */
    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        val (version, raw) = parse(stored) ?: return stored // 알 수 없는 접두 — 레거시 평문으로 취급
        val key = keyRing.keyFor(version) // revoked → RevokedKeyException
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /**
     * ciphertext 가 active 가 아닌 버전(retired/legacy)으로 암호화돼 있으면 복호 후 active 로 재암호화해 돌려준다
     * (lazy re-encryption). 이미 active 면 그대로 돌려준다. 호출자가 결과를 재저장하면 점진 마이그레이션이 수렴한다.
     */
    fun reencryptToActive(stored: String?): String? {
        if (stored == null) return null
        val version = versionOf(stored) ?: return stored
        if (version == keyRing.activeVersion) return stored
        return encrypt(decrypt(stored))
    }

    /** ciphertext 의 키 버전을 돌려준다(레거시 → [KeyRing.LEGACY_VERSION], 비암호 → null). */
    fun versionOf(stored: String?): Int? {
        if (stored == null) return null
        return parse(stored)?.first
    }

    /**
     * [version] 을 폐기해도 안전한지 — 주어진 ciphertext 컬렉션에 그 버전으로 암호화된 것이 더 없는지 확인한다.
     * 호출자(배치)가 모든 활성 ciphertext 를 [reencryptToActive] 로 옮긴 뒤 이 검사로 폐기 가능성을 확정한다
     * (acceptance: 구키 폐기 전 잔존 ciphertext 0 검증).
     */
    fun canRevoke(
        version: Int,
        ciphertexts: Iterable<String?>,
    ): Boolean {
        require(version != keyRing.activeVersion) { "active 버전은 폐기 대상이 될 수 없다" }
        return ciphertexts.none { versionOf(it) == version }
    }

    private fun encryptWith(
        plain: String,
        version: Int,
        key: javax.crypto.spec.SecretKeySpec,
    ): String {
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$PREFIX$version:" + Base64.getEncoder().encodeToString(iv + ct)
    }

    /** 접두를 파싱해 (버전, raw bytes) 를 돌려준다. enc2:/enc1: 만 인식, 그 외는 null(레거시 평문). */
    private fun parse(stored: String): Pair<Int, ByteArray>? =
        when {
            stored.startsWith(PREFIX) -> {
                val rest = stored.removePrefix(PREFIX)
                val sep = rest.indexOf(':')
                if (sep <= 0) {
                    null
                } else {
                    val version = rest.substring(0, sep).toIntOrNull() ?: return null
                    version to Base64.getDecoder().decode(rest.substring(sep + 1))
                }
            }
            stored.startsWith(LEGACY_PREFIX) ->
                KeyRing.LEGACY_VERSION to Base64.getDecoder().decode(stored.removePrefix(LEGACY_PREFIX))
            else -> null
        }

    companion object {
        private const val PREFIX = "enc2:"
        private const val LEGACY_PREFIX = "enc1:"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        private const val ALGO = "AES/GCM/NoPadding"
    }
}
