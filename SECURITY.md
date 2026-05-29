# 보안 정책 (Security Policy)

## 지원 버전

이 프로젝트는 졸업 프로젝트 MVP이며, 보안 수정은 항상 `main` 브랜치의 최신 코드에만 적용됩니다.

| 버전 | 지원 여부 |
| --- | --- |
| `main` (최신) | ✅ |
| 그 외 태그/브랜치 | ❌ |

## 취약점 신고 (Reporting a Vulnerability)

보안 취약점을 발견하면 **공개 이슈로 등록하지 마세요.** 악용 가능성이 있는 정보를 공개 트래커에 올리면 사용자가 위험해집니다.

대신 다음 절차를 따라주세요.

1. GitHub 저장소의 **Security → Report a vulnerability**(Private Vulnerability Reporting) 기능으로 비공개 신고를 합니다.
2. 위 기능을 사용할 수 없으면 저장소 소유자(`Hyeonjun0527`)에게 비공개로 연락합니다.
3. 신고에는 다음을 포함해주세요.
   - 영향을 받는 컴포넌트(봇 / 대시보드 / LLM 연동 등)
   - 재현 단계 또는 PoC
   - 예상되는 영향 범위

가능하면 **3영업일 이내**에 접수 확인을, **14일 이내**에 1차 대응 계획을 회신하는 것을 목표로 합니다. 수정 배포 전까지는 책임 있는 공개(coordinated disclosure)에 협조해주세요.

## 비밀 값과 암호화 주의사항

이 프로젝트는 여러 민감한 값을 다룹니다. 운영 환경에 배포하기 전 반드시 확인하세요.

### `SECRET_KEY`

- API 키 암호화(Fernet)에 사용되는 핵심 비밀 값입니다.
- 기본값은 `change-me-in-production`이며, **이 값을 그대로 운영에 사용하면 안 됩니다.**
  앱 시작 시 기본값이 감지되면 경고 로그가 출력됩니다.
- 충분히 긴 임의 문자열로 교체하세요. 예시:

  ```bash
  python -c "import secrets; print(secrets.token_urlsafe(48))"
  ```

- `SECRET_KEY`를 변경하면 **기존에 암호화된 API 키를 복호화할 수 없습니다.** 키 교체 시 저장된 API 키를 다시 등록해야 합니다.

### `DISCORD_BOT_TOKEN`

- Discord 봇 토큰은 봇 계정의 전체 권한을 가집니다.
- `.env` 파일에만 보관하고, **절대 커밋하거나 공유하지 마세요.** `.env`는 `.gitignore`에 포함되어 있습니다.
- 토큰이 노출되었다면 즉시 Discord Developer Portal에서 토큰을 재발급(Regenerate)하세요.

### 프로바이더 API 키 (OpenAI / Anthropic)

- `/settings` 패널에서 입력한 OpenAI·Anthropic API 키는 `SECRET_KEY` 기반 Fernet 대칭 암호화로 SQLite에 저장됩니다.
- 데이터베이스 파일(`data/discord_assistant.db`)과 백업도 비밀로 취급하세요. 암호화되어 있어도 `SECRET_KEY`가 함께 유출되면 복호화가 가능합니다.

### 기본값 경고

운영 배포 전 다음 기본값을 반드시 점검하세요.

- `SECRET_KEY=change-me-in-production` → **교체 필수**
- `DISCORD_BOT_TOKEN=replace-with-...` → 실제 토큰으로 교체
- 대시보드를 사용할 경우 `dashboard/backend/.env`의 OAuth 클라이언트 시크릿·세션 키도 기본값이 아닌지 확인

## 권장 운영 수칙

- 비밀 값은 환경변수 또는 시크릿 매니저로 주입하고, 저장소에 남기지 않습니다.
- 봇에 부여하는 Discord 권한은 필요한 최소 범위로 제한합니다(README의 권한 목록 참고).
- 의존성 보안 업데이트를 주기적으로 반영합니다(`pip list --outdated`).
