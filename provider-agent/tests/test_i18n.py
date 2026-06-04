"""데스크톱 앱 i18n 로더(공유 문구 SSOT 의 agent 생성본) 테스트."""

from __future__ import annotations

from provider_agent import i18n


def test_t_returns_each_language() -> None:
    assert i18n.t("alreadyRunning", "ko") == "이미 실행 중입니다."
    assert i18n.t("alreadyRunning", "en") == "Already running."
    assert i18n.t("alreadyRunning", "ja") == "すでに実行中です。"


def test_fallback_unknown_lang_and_key() -> None:
    # 미지원 언어 → 감지(테스트 환경은 보통 ko/en) 후 폴백. 적어도 빈 값이 아니어야.
    assert i18n.t("saveTokenFirst", "fr")
    # 없는 키 → 키 자체 반환
    assert i18n.t("no.such.key", "ko") == "no.such.key"


def test_all_agent_keys_have_three_locales() -> None:
    table = i18n._table()
    assert table, "i18n_messages.json 로드 실패"
    for key, langs in table.items():
        for loc in ("ko", "en", "ja"):
            assert langs.get(loc, "").strip(), f"{loc} 누락: {key}"
