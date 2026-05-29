#!/usr/bin/env python3
"""
scripts/rekey_api_keys.py — SECRET_KEY 교체 시 저장된 API 키 재암호화 (#37).

guild_config.api_key_encrypted 에 들어 있는 모든 길드의 API 키를 **구 SECRET_KEY**
로 복호화한 뒤 **신 SECRET_KEY** 로 다시 암호화해 업데이트한다. SECRET_KEY 를
교체(rotate)할 때, 기존에 암호화된 키들이 복호화 불가가 되는 것을 막기 위한
일회성 마이그레이션 도구다.

동작 개요:
  1. DATABASE_URL 의 sqlite 파일을 직접(동기 sqlite3) 연다.
  2. guild_config 에서 api_key_encrypted 가 NULL 이 아닌 행을 모두 읽는다.
  3. 각 행을 crypto.decrypt_api_key(token, OLD_SECRET) 로 복호화한다.
       - 이미 신 키로 암호화돼 있거나 손상된 행은 복호화 실패 → 건너뛴다(스킵 집계).
  4. crypto.encrypt_api_key(plaintext, NEW_SECRET) 로 재암호화한다.
  5. --dry-run 이 아니면 UPDATE 로 새 토큰을 기록한다.

보안:
  * 평문 API 키·암호문·SECRET_KEY 를 **절대 출력하지 않는다**. guild_id 와
    성공/스킵 집계만 보고한다.
  * SECRET_KEY 는 환경 변수 또는 옵션으로만 받는다(쉘 히스토리 노출 주의).

사용법:
  # 환경 변수로 구/신 키를 주고 미리보기(dry-run)
  OLD_SECRET_KEY=... NEW_SECRET_KEY=... \\
      python scripts/rekey_api_keys.py --dry-run

  # 실제 적용 (DATABASE_URL 은 .env 또는 환경 변수에서)
  OLD_SECRET_KEY=... NEW_SECRET_KEY=... \\
      python scripts/rekey_api_keys.py

  # 키를 옵션으로도 줄 수 있음(권장하지 않음: 쉘 히스토리 노출)
  python scripts/rekey_api_keys.py --old-secret ... --new-secret ... --dry-run

옵션:
  --database-url URL   DATABASE_URL 대신 명시(미지정 시 환경 변수/기본값 사용).
  --old-secret S       구 SECRET_KEY(미지정 시 OLD_SECRET_KEY 환경 변수).
  --new-secret S       신 SECRET_KEY(미지정 시 NEW_SECRET_KEY 환경 변수).
  --dry-run            변경 없이 재암호화 가능 건수만 보고.

종료 코드:
  0 — 성공(모든 대상 처리 또는 변경 대상 없음)
  1 — 설정 오류(키 누락, DB 없음 등)
"""
from __future__ import annotations

import argparse
import os
import sqlite3
import sys
from pathlib import Path

# 패키지가 설치되지 않은 상태에서도 `python scripts/rekey_api_keys.py` 가 동작하도록
# src 레이아웃을 sys.path 에 보강한다(설치돼 있으면 무해).
_SRC = Path(__file__).resolve().parent.parent / "src"
if _SRC.is_dir() and str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from discord_assistant.crypto import (  # noqa: E402
    CryptoError,
    decrypt_api_key,
    encrypt_api_key,
)
from discord_assistant.storage import sqlite_path_from_database_url  # noqa: E402


def _resolve_database_url(cli_value: str | None) -> str:
    """CLI 옵션 → DATABASE_URL 환경 변수 → 기본값 순으로 DATABASE_URL 을 결정한다."""
    if cli_value:
        return cli_value
    return os.getenv("DATABASE_URL", "sqlite:///./data/discord_assistant.db").strip()


def _resolve_secret(cli_value: str | None, env_name: str) -> str | None:
    """CLI 옵션 → 환경 변수 순으로 비밀 값을 읽는다(없으면 None). 값은 출력하지 않는다."""
    if cli_value:
        return cli_value
    raw = os.getenv(env_name)
    return raw.strip() if raw and raw.strip() else None


def rekey(
    *,
    database_url: str,
    old_secret: str,
    new_secret: str,
    dry_run: bool,
) -> tuple[int, int]:
    """저장된 모든 API 키를 구→신 SECRET_KEY 로 재암호화한다.

    반환값은 ``(rekeyed, skipped)`` 집계다. ``skipped`` 는 구 키로 복호화에
    실패한(이미 신 키이거나 손상된) 행 수다. 평문/암호문은 일절 출력하지 않는다.
    """
    db_path = sqlite_path_from_database_url(database_url)
    if db_path == ":memory:":
        # :memory: 는 프로세스마다 빈 DB라 재암호화 대상이 없다.
        print("DATABASE_URL=:memory: — 재암호화할 영속 데이터가 없습니다.")
        return (0, 0)

    abs_path = Path(db_path).expanduser()
    if not abs_path.exists():
        raise FileNotFoundError(f"SQLite DB 파일을 찾을 수 없습니다: {abs_path}")

    rekeyed = 0
    skipped = 0
    with sqlite3.connect(str(abs_path)) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            "SELECT guild_id, api_key_encrypted FROM guild_config "
            "WHERE api_key_encrypted IS NOT NULL AND api_key_encrypted != ''"
        ).fetchall()

        for row in rows:
            guild_id = int(row["guild_id"])
            token = str(row["api_key_encrypted"])
            try:
                plaintext = decrypt_api_key(token, old_secret)
            except CryptoError:
                # 구 키로 복호화 불가 → 이미 신 키로 암호화됐거나 손상된 행. 건너뛴다.
                skipped += 1
                print(f"[skip] guild {guild_id}: 구 SECRET_KEY 로 복호화 실패 — 건너뜁니다.")
                continue

            new_token = encrypt_api_key(plaintext, new_secret)
            # 평문은 즉시 폐기(참조 해제). 출력하지 않는다.
            del plaintext

            if dry_run:
                print(f"[dry-run] guild {guild_id}: 재암호화 가능")
            else:
                conn.execute(
                    "UPDATE guild_config SET api_key_encrypted = ? WHERE guild_id = ?",
                    (new_token, guild_id),
                )
                print(f"[ok] guild {guild_id}: 재암호화 완료")
            rekeyed += 1

        if not dry_run:
            conn.commit()

    return (rekeyed, skipped)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="SECRET_KEY 교체 시 저장된 API 키를 재암호화합니다 (#37).",
    )
    parser.add_argument(
        "--database-url",
        default=None,
        help="DATABASE_URL(미지정 시 환경 변수/기본값).",
    )
    parser.add_argument(
        "--old-secret",
        default=None,
        help="구 SECRET_KEY(미지정 시 OLD_SECRET_KEY 환경 변수). 쉘 히스토리 노출 주의.",
    )
    parser.add_argument(
        "--new-secret",
        default=None,
        help="신 SECRET_KEY(미지정 시 NEW_SECRET_KEY 환경 변수). 쉘 히스토리 노출 주의.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="변경 없이 재암호화 가능 건수만 보고합니다.",
    )
    args = parser.parse_args(argv)

    old_secret = _resolve_secret(args.old_secret, "OLD_SECRET_KEY")
    new_secret = _resolve_secret(args.new_secret, "NEW_SECRET_KEY")
    if not old_secret or not new_secret:
        print(
            "오류: 구/신 SECRET_KEY 가 모두 필요합니다. "
            "OLD_SECRET_KEY / NEW_SECRET_KEY 환경 변수 또는 "
            "--old-secret / --new-secret 옵션으로 지정하세요.",
            file=sys.stderr,
        )
        return 1
    if old_secret == new_secret:
        print(
            "오류: 구 SECRET_KEY 와 신 SECRET_KEY 가 동일합니다. 재암호화할 필요가 없습니다.",
            file=sys.stderr,
        )
        return 1

    database_url = _resolve_database_url(args.database_url)

    try:
        rekeyed, skipped = rekey(
            database_url=database_url,
            old_secret=old_secret,
            new_secret=new_secret,
            dry_run=args.dry_run,
        )
    except FileNotFoundError as exc:
        print(f"오류: {exc}", file=sys.stderr)
        return 1

    mode = "(dry-run) " if args.dry_run else ""
    print(f"\n{mode}완료: 재암호화 {rekeyed}건, 건너뜀 {skipped}건.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
