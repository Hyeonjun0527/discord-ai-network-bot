"""sslutil.ssl_context() 단위 테스트 — certifi CA 사용·폴백 동작 보장."""
import ssl

from provider_agent import sslutil


def test_ssl_context_returns_verifying_context() -> None:
    ctx = sslutil.ssl_context()
    assert isinstance(ctx, ssl.SSLContext)
    # 기본 검증 컨텍스트는 인증서 검증 + 호스트네임 확인이 켜져 있어야 한다(MITM 방지).
    assert ctx.verify_mode == ssl.CERT_REQUIRED
    assert ctx.check_hostname is True


def test_ssl_context_uses_certifi_bundle() -> None:
    """certifi 가 있으면 그 CA 묶음이 로드되어 신뢰 앵커가 비어 있지 않다(frozen 번들 핵심)."""
    import certifi

    ctx = sslutil.ssl_context()
    # certifi 묶음을 로드한 컨텍스트는 다수의 루트 CA 를 가진다.
    assert ctx.cert_store_stats()["x509_ca"] > 0
    # certifi 경로가 실제 존재하는 파일이어야 한다.
    assert certifi.where()


def test_ssl_context_falls_back_when_certifi_missing(monkeypatch) -> None:
    """certifi import 가 실패해도 시스템 기본 컨텍스트로 폴백한다(개발 환경)."""
    import builtins

    real_import = builtins.__import__

    def fake_import(name, *args, **kwargs):
        if name == "certifi":
            raise ImportError("simulated missing certifi")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", fake_import)
    ctx = sslutil.ssl_context()
    assert isinstance(ctx, ssl.SSLContext)
    assert ctx.verify_mode == ssl.CERT_REQUIRED
