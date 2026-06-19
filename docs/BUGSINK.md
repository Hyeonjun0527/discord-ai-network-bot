# Bugsink 오류 수집

Bugsink는 Sentry SDK 호환 에러 트래커로 사용한다. 앱 코드는 Sentry SDK를 그대로 쓰고,
환경변수 DSN만 Bugsink 프로젝트 DSN으로 넣는다.

## 프로젝트 권장 분리

- `nexa-site`: Astro 공개 사이트 브라우저 오류
- `nexa-console`: Vite React 관리자 콘솔 오류
- `nexa-central`: Kotlin central-server 오류
- `nexa-agent`: Python provider-agent 오류

## 환경변수

```bash
BUGSINK_DSN=https://...
SENTRY_ENV=production
PUBLIC_BUGSINK_DSN=https://...
VITE_BUGSINK_DSN=https://...
```

- `BUGSINK_DSN`: Kotlin/Python/server 런타임용
- `PUBLIC_BUGSINK_DSN`: Astro 공개 사이트용
- `VITE_BUGSINK_DSN`: Vite React 콘솔용
- `SENTRY_DSN`은 기존 호환 alias로만 남긴다. Bugsink DSN이 있으면 Bugsink DSN이 우선한다.

## 샘플링

Bugsink는 error event 중심 도구라 traces/metrics를 보내지 않는다. 모든 SDK 설정은
`tracesSampleRate=0` 또는 `traces_sample_rate=0.0`을 기본으로 둔다.
