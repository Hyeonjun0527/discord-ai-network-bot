"""HTTPS TLS 인증서 검증용 SSL 컨텍스트 (frozen 번들 안전).

PyInstaller 로 묶인 앱(.app/.exe)은 시스템 OpenSSL CA 경로가 비어 있어, 기본 SSL 컨텍스트로 HTTPS 를
열면 ``CERTIFICATE_VERIFY_FAILED: unable to get local issuer certificate`` 가 난다(실증: Gemini
``generativelanguage.googleapis.com`` 호출 실패). 번들된 **certifi** CA 묶음을 명시해 이를 해결한다.

이 모듈은 그 SSL 컨텍스트 생성 한 가지 책임만 가진다(SRP). 같은 패턴이 connection/updater/agent 등에
인라인으로 흩어져 있던 것을 새/수정 코드에서 한곳으로 모은다(DRY). certifi 가 없으면 시스템 기본으로
폴백한다(개발 환경 등 — 시스템 CA 가 있는 경우).
"""
from __future__ import annotations

import ssl


def ssl_context() -> ssl.SSLContext:
    """certifi CA 묶음을 쓰는 기본 TLS 검증 컨텍스트. certifi 부재 시 시스템 기본으로 폴백."""
    try:
        import certifi

        return ssl.create_default_context(cafile=certifi.where())
    except Exception:  # noqa: BLE001 - certifi 없으면(개발 환경 등) 시스템 기본 CA 사용
        return ssl.create_default_context()
