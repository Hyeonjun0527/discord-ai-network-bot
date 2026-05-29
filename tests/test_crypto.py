"""Unit tests for crypto.py — encrypt/decrypt API key helpers."""
from __future__ import annotations

import unittest

from discord_assistant.crypto import CryptoError, decrypt_api_key, encrypt_api_key


class TestEncryptDecrypt(unittest.TestCase):
    SECRET = "test-secret-key"

    def test_roundtrip_restores_original(self):
        original = "sk-abc123XYZ"
        token = encrypt_api_key(original, self.SECRET)
        recovered = decrypt_api_key(token, self.SECRET)
        self.assertEqual(recovered, original)

    def test_roundtrip_unicode_key(self):
        original = "sk-안녕하세요-unicode-key"
        token = encrypt_api_key(original, self.SECRET)
        recovered = decrypt_api_key(token, self.SECRET)
        self.assertEqual(recovered, original)

    def test_encrypt_produces_different_value(self):
        original = "sk-abc123"
        token = encrypt_api_key(original, self.SECRET)
        self.assertNotEqual(token, original)

    def test_two_encryptions_are_different(self):
        """Fernet uses a random IV, so two encryptions of the same plaintext differ."""
        original = "sk-abc123"
        token1 = encrypt_api_key(original, self.SECRET)
        token2 = encrypt_api_key(original, self.SECRET)
        # Both decrypt correctly, but the tokens themselves differ.
        self.assertNotEqual(token1, token2)
        self.assertEqual(decrypt_api_key(token1, self.SECRET), original)
        self.assertEqual(decrypt_api_key(token2, self.SECRET), original)

    def test_wrong_secret_raises_crypto_error(self):
        token = encrypt_api_key("sk-abc123", self.SECRET)
        with self.assertRaises(CryptoError):
            decrypt_api_key(token, "wrong-secret")

    def test_tampered_token_raises_crypto_error(self):
        token = encrypt_api_key("sk-abc123", self.SECRET)
        # Corrupt the last few bytes
        tampered = token[:-4] + "XXXX"
        with self.assertRaises(CryptoError):
            decrypt_api_key(tampered, self.SECRET)

    def test_empty_token_raises_crypto_error(self):
        with self.assertRaises(CryptoError):
            decrypt_api_key("", self.SECRET)

    def test_empty_api_key_roundtrip(self):
        """Empty string should encrypt/decrypt correctly."""
        token = encrypt_api_key("", self.SECRET)
        recovered = decrypt_api_key(token, self.SECRET)
        self.assertEqual(recovered, "")

    def test_crypto_error_is_value_error_subclass(self):
        """CryptoError must be a subclass of ValueError per module contract."""
        self.assertTrue(issubclass(CryptoError, ValueError))

    def test_different_secrets_produce_incompatible_tokens(self):
        token_a = encrypt_api_key("sk-abc", "secret-A")
        with self.assertRaises(CryptoError):
            decrypt_api_key(token_a, "secret-B")

    def test_new_token_uses_v2_scheme(self):
        # #81: 신규 암호화는 항상 v2 프리픽스를 단다(강한 KDF 경로).
        token = encrypt_api_key("sk-abc123", self.SECRET)
        self.assertTrue(token.startswith("v2:"))

    def test_legacy_v1_token_still_decrypts(self):
        # #81 하위호환: 과거 스킴(프리픽스 없는 base64(sha256(secret)) Fernet 토큰)으로
        # 저장된 기존 DB 토큰이 새 코드에서도 그대로 복호화돼야 한다(마이그레이션 불필요).
        import base64
        import hashlib

        from cryptography.fernet import Fernet

        original = "sk-legacy-stored-key"
        v1_key = base64.urlsafe_b64encode(hashlib.sha256(self.SECRET.encode()).digest())
        v1_token = Fernet(v1_key).encrypt(original.encode()).decode()  # 프리픽스 없음 = v1
        self.assertFalse(v1_token.startswith("v2:"))
        self.assertEqual(decrypt_api_key(v1_token, self.SECRET), original)

    def test_legacy_v1_token_wrong_secret_raises(self):
        import base64
        import hashlib

        from cryptography.fernet import Fernet

        v1_key = base64.urlsafe_b64encode(hashlib.sha256(b"right-secret").digest())
        v1_token = Fernet(v1_key).encrypt(b"sk-abc").decode()
        with self.assertRaises(CryptoError):
            decrypt_api_key(v1_token, "wrong-secret")


if __name__ == "__main__":
    unittest.main()
