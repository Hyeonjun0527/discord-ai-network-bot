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

## requestId 상관관계

데스크톱 앱(provider-agent)과 관리자 콘솔은 central-server HTTP 요청마다 `X-Request-Id`를 생성해 보낸다.
central-server는 같은 헤더를 응답에 되돌리고 MDC 로그와 Bugsink scope에 넣는다.

- 데스크톱/콘솔 오류 태그: `app`, `platform` 또는 `environment`, `appVersion`, `apiEndpoint`, `httpStatus`, `requestId`, `serverBaseUrl`
- API 오류 태그: `app=api`, `service=nexa-api`, `endpoint`, `method`, `httpStatus`, `requestId`

같은 장애를 볼 때는 Bugsink에서 `requestId`로 검색한다. 데스크톱/콘솔의 API 실패 이벤트와
central-server의 5xx 예외 이벤트가 같은 `requestId`를 가지면 한 요청에서 이어진 문제다.
