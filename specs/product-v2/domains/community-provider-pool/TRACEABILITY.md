# 추적성 매트릭스 (TRACEABILITY)

> 5분서(requirements/domain-model/screens/navigation/api)가 `docs/ROADMAP_REMOTE_AGENT.md`
> Phase B(차수 13~32, 항목 301~674)를 구현하기에 충분/정합한지 감사한 결과의 **추적표**다.
> 본 문서는 감사 산출물이며 5분서를 수정하지 않는다(읽기 전용 대조). 결함 목록은 감사 보고
> 메시지를 참조. 작성 기준일 2026-05-30.
>
> 표기: ✅ 충분한 근거 / ⚠️ 부분/약함 / ❌ 근거 없음 또는 깨진 참조.

---

## (a) 674 로드맵 차수 13~32 ↔ 근거 문서/섹션 매핑

| 차수 | 항목 | 주제 | 근거 문서·섹션 | 상태 | 비고 |
|---|---|---|---|---|---|
| 13 | 301~316 | ADR 0003·도메인 확정·19단계·10상태·부담수준·공정성 초안·보안·프라이버시 A/B/C·명령 카탈로그 | domain-model §1.1~2,§6,§8; requirements §1~2; README 전반 | ✅ | ADR 0003 자체는 "작성 예정"(미존재) — 301 산출물 부재 |
| 14 | 317~340 | 데이터 모델·스토리지 스키마·dataclass·enum·마이그레이션·CRUD·가드 | domain-model §4(엔티티16),§5(값객체),§6(상태),§9.4(가드 335) | ✅ | 마이그레이션/인덱스(333·334·339)는 도메인 모델이 의도적으로 비-범위 |
| 15 | 341~352 | 모델 부담 수준 분류·휴리스틱·오버라이드·RESTRICTED·기본값·라벨·`/models`빌더 | domain-model §2.9,§4.10,§5.9,DM-R-05; screens §3.7,SCR-407; api §5.2 | ✅ | |
| 16 | 353~372 | 등록/승인 라이프사이클·토큰 발급·동의·중복방지·재등록·audit | requirements §4 SCN-03,REQ-505/506; domain-model §4.6,§6.1; screens SCR-601/602/603,504; navigation FLOW-060~064,053; api §5.7,§7.1~7.3 | ✅ | |
| 17 | 373~394 | provider_hello/status·세션·상태전이·heartbeat·capability·멀티세션 레지스트리 | domain-model §2.6~2.7,§4.7,§6.2~6.3; navigation FLOW-04.x,§10.2/10.4; api §8.2~8.6 | ✅ | 상태머신 ID 명칭 불일치(P1-1) **해결**: `…State` 통일 |
| 18 | 395~414 | 채널/역할/모델수준 서버 정책·합집합·캐시·audit·검증 | requirements REQ-502~504,DM-R-03/04; domain-model §4.2~4.4; screens SCR-502/503; navigation FLOW-051/052; api §6 | ✅ | 정책 캐시(408)는 requirements REQ-705 에만 약하게 |
| 19 | 415~436 | 기여 정책(모델별 역할/채널/한도/동시/시간/긴프롬프트/요청자범위)·즉시반영·"의무없음" | requirements REQ-507,REQ-603; domain-model §2.10,§4.9; screens SCR-605/606/607; navigation FLOW-068~06A; api §5.13,§7.7~7.9 | ✅ | |
| 20 | 437~450 | 요청 무게 휴리스틱·필요수준 매핑·다운그레이드·경계값 | domain-model §2.11,§5.10(RequestWeight); navigation §8.5; api §9.2 | ✅ | P1-5 **해결**: `DM-V-RequestWeight` enum(light/medium/heavy)·경계값·매핑 확정(§5.10) |
| 21 | 451~472 | 10단계 필터 파이프라인·RESTRICTED 특수필터·후보0/권한부족 신호·사유기록·메트릭 | domain-model §8.1~8.7,§4.12; navigation §8.6~8.7; api §9.3~9.4 | ✅ | 필터 단계 번호와 브리프 19단계 매핑 일관 |
| 22 | 473~490 | 공정성 점수·가산/감산·우선순위·heavy 낭비·동점분산·튜닝 | domain-model §8.8~8.9,DM-R-07; requirements REQ-510/604; navigation §8.8~8.9; api §9.5 | ✅ | |
| 23 | 491~514 | 요청 상태머신·request_id·큐·타임아웃·fallback·temporarily_unavailable·rejected·진행표시 | domain-model §4.11,§4.14,§6.4,DM-R-07b; requirements REQ-511/512,§8 EX; screens SCR-907~910; navigation §9,§10.3; api §9.6~9.10,§8.7~8.11 | ✅ | |
| 24 | 515~538 | 수동/자동 보호(CPU/GPU/메모리/배터리/절전/네트워크/동시/시간/길이/반복실패) | requirements REQ-603; domain-model §4.16,§6.6,DM-R-06; screens SCR-608~610,709; navigation FLOW-06C/06D; api §8.4,§8.10 | ✅ | 절전→offline(525) 도메인 전이표에 명시(§6.1) |
| 25 | 539~550 | 유저 명령 `/ask`·`/models`·`/my-usage`·`/privacy`·권한별표시·풀안내·거절·i18n·help·쿨다운 | screens SCR-401~411,§10.B; navigation FLOW-05.x; api §5.1~5.4b | ✅ | help(547)·쿨다운(548) **해결**: SCR-410/411·API-CMD-HELP 추가. i18n(546)은 잔여(P2) |
| 26 | 551~568 | 관리자 명령·상세보기·헬스요약·강제제어·일괄내보내기·audit·알림 | screens SCR-501~510,804; navigation FLOW-050~056; api §5.5/5.6/5.12,§6,§7,§11 | ✅ | |
| 27 | 569~586 | 프로바이더 명령·소유권·미연결동작·즉시반영·토큰재발급·docs-drift 가드 | screens SCR-601~611; navigation FLOW-06x; api §5.7~5.13,§7 | ✅ | docs-drift 가드(583)는 requirements REQ-708 에 약하게 |
| 28 | 587~600 | 프라이버시 모드 A/B/C·텍스트빌더·관리자로그·고지빈도·프롬프트최소화 | requirements REQ-514/702,DM-R-09; domain-model §2.14; screens SCR-405/409/510/911; navigation FLOW-057; api §6.8 | ✅ | 모드 A/B/C 실제 문구 screens §4.5 에 구체화 |
| 29 | 601~614 | usage/contribution 기록·집계·공정성지표·리포트·retention·민감내용미포함 | requirements REQ-513,DM-R-10; domain-model §2.13,§4.15,§3.6; screens SCR-408/508/806; navigation §8.12; api §10 | ✅ | |
| 30 | 615~632 | 보안 하드닝(금지행위·outbound·토큰·화이트리스트·크기상한·SSRF·격리·권한상승·ratelimit·마스킹·TLS) | requirements REQ-701,§9.6; domain-model §9.1~9.6,DM-R-10; api §3,§8 보안불변식,§13 | ✅ | rate limit(627) **해결**: REQ-701 명문화·ERR-RATE-LIMITED·SCR-411 |
| 31 | 633~656 | 테스트(분류·필터·점수·상태머신·fallback·한도·정책·보호·프라이버시·보안·CRUD·통합·권한) | requirements §9 수용기준,§2.5 SC-1~7 | ⚠️ | 테스트 항목은 요구사항 수용기준에 대응하나 명세 5분서의 직접 책임 아님(구현 단계) |
| 32 | 657~674 | 문서·배포·env·ADR 채택·데모·메트릭·롤백·패키징·성능·보안리뷰·PR | requirements REQ-708,§1.4; README 로드맵 매핑 | ⚠️ | 운영/배포 산출물은 명세 범위 밖(정상). ADR 0003(663)은 미작성 |

---

## (b) 19개 명령 ↔ REQ/SCR/FLOW/API 커버리지표

> 핵심 발견(원본): REQ·SCR·FLOW·API ID 가 분서 간 서로 다른 번호체계로 어긋났다. 아래 표의
> "타분서 인용·미정의" 열은 정정 **이전** 상태를 보존한 감사 기록이다. **정정 후** 실제 ID 는
> §(c) 정정 매핑표를 따른다(2026-05-30 reconcile 로 깨진 참조 해소).

| 명령 | REQ(req.md 정의) | REQ(screens/api 인용·미정의) | SCR(screens 정의) | SCR(api 인용) | FLOW(navigation 정의) | FLOW(req.md 인용) | API(api.md 정의) | API(screens/nav 인용·미정의) |
|---|---|---|---|---|---|---|---|---|
| `/ask` | REQ-510 | REQ-539,543,544,545 | SCR-401~404 | SCR-401/403/404 | FLOW-040,080 | FLOW-05/08 | API-CMD-ASK | API-ASK,API-ROUTE |
| `/models` | (REQ-509) | REQ-540,349 | SCR-407 | SCR-410❌ | FLOW-047 | — | API-CMD-MODELS | API-MODELS,API-POOL-CAPABILITY |
| `/my-usage` | REQ-513 | REQ-541,604 | SCR-408 | SCR-411❌ | FLOW-048 | — | API-CMD-MY-USAGE | API-MY-USAGE,API-USAGE-QUERY |
| `/privacy` | REQ-514 | REQ-542,594 | SCR-409 | SCR-412❌ | FLOW-049 | — | API-CMD-PRIVACY | API-PRIVACY |
| `/llm-settings` | REQ-502 | REQ-405,551 | SCR-501 | SCR-501 | FLOW-050 | FLOW-02 | API-CMD-LLM-SETTINGS | API-LLM-SETTINGS |
| `/llm-allow-channel` | REQ-503 | REQ-395,552 | SCR-502 | SCR-502 | FLOW-051 | FLOW-02 | API-CMD-LLM-ALLOW-CHANNEL | API-ALLOW-CHANNEL,API-CH-ALLOW |
| `/llm-deny-channel` | REQ-503 | REQ-396,553 | SCR-502 | SCR-502 | FLOW-051 | FLOW-02 | API-CMD-LLM-DENY-CHANNEL | API-DENY-CHANNEL,API-CH-DENY |
| `/llm-role-policy` | REQ-504 | REQ-399~404,554 | SCR-503 | SCR-503 | FLOW-052 | FLOW-02 | API-CMD-LLM-ROLE-POLICY | API-ROLE-POLICY |
| `/providers` | REQ-515 | REQ-555,559,560 | SCR-504/505/507/508 | SCR-510❌ | FLOW-053/055 | FLOW-03 | API-CMD-PROVIDERS | API-PROVIDER-DETAIL,API-POOL-HEALTH |
| `/provider-approve` | REQ-506 | REQ-356,556 | SCR-504 | SCR-511❌ | FLOW-053 | FLOW-03 | API-CMD-PROVIDER-APPROVE | API-PROVIDER-APPROVE |
| `/provider-remove` | REQ-515 | REQ-357,557,561 | SCR-506 | SCR-512❌ | FLOW-054 | — | API-CMD-PROVIDER-REMOVE | API-PROVIDER-REMOVE |
| `/provider-join` | REQ-505 | REQ-353,368,569 | SCR-601 | SCR-520❌ | FLOW-060 | FLOW-03 | API-CMD-PROVIDER-JOIN | API-PROVIDER-JOIN |
| `/provider-leave` | REQ-603 | REQ-517,570 | SCR-611 | SCR-523❌ | FLOW-06E | FLOW-10 | API-CMD-PROVIDER-LEAVE | API-PROVIDER-LEAVE |
| `/provider-pause` | REQ-603 | REQ-515,519,571 | SCR-609 | SCR-522❌ | FLOW-06C | FLOW-10 | API-CMD-PROVIDER-PAUSE | API-PROVIDER-PAUSE |
| `/provider-resume` | REQ-603 | REQ-516,572 | SCR-610 | SCR-522❌ | FLOW-06D | FLOW-10 | API-CMD-PROVIDER-RESUME | API-PROVIDER-RESUME |
| `/provider-status` | REQ-515 | REQ-518,430,573 | SCR-608 | SCR-521❌ | (조회) | FLOW-10 | API-CMD-PROVIDER-STATUS | API-PROVIDER-STATUS,API-SESSION-SNAPSHOT |
| `/provider-models` | REQ-507 | REQ-415,344,574 | SCR-605 | SCR-524❌ | FLOW-068 | — | API-CMD-PROVIDER-MODELS | API-PROVIDER-MODELS |
| `/provider-limit` | REQ-507 | REQ-416,420~424,575 | SCR-607 | SCR-524❌ | FLOW-06A | — | API-CMD-PROVIDER-LIMIT | API-PROVIDER-LIMIT |
| `/provider-scope` | REQ-507 | REQ-417,425,576 | SCR-606 | SCR-524❌ | FLOW-069 | — | API-CMD-PROVIDER-SCOPE | API-PROVIDER-SCOPE |

> 결론: 19개 명령 모두 각 분서에 **존재는 한다(기능 커버리지 100%)**. 그러나 명령을 잇는
> REQ/SCR/FLOW/API ID 가 분서마다 다른 번호체계여서 **추적 사슬이 끊겨 있다**. ❌ 표시는
> api.md 가 인용한 SCR ID 가 screens.md 정의에 존재하지 않는 경우.

---

## (c) 깨진 참조 / 불일치 ID 목록 — **정정 완료 반영(2026-05-30 reconcile)**

> 아래 표의 "상태" 열: ✅ 해결됨(정합 완료) / ⚠️ 잔여. 정정 작업으로 5분서의 깨진 참조를
> README §"ID 정합 규칙(SSOT)" 대로 실제 정의 ID 로 치환했다. 정정 매핑 요약은 §(c-8).

### c-1. 상태머신 ID 명칭 불일치 (정의처 ≠ 참조처) — ✅ 해결됨

| 정의(domain-model.md) | 인용(navigation.md) | 정정 | 상태 |
|---|---|---|---|
| `DM-S-AgentConnState` (§6.3) | `DM-S-AgentConn` | →`DM-S-AgentConnState`(§2.4,§7,§10.4,11-C 전부) | ✅ |
| `DM-S-ProviderSessionState` (§6.2) | `DM-S-ProviderSession` | →`DM-S-ProviderSessionState`(§2.4,§10.2) | ✅ |
| `DM-S-ApprovalState` (§4.6) | `DM-S-Approval` | →`DM-S-ApprovalState`(§5,§10.5,11-B) | ✅ |
| `DM-S-ProviderHealth` (§4.16,§6.6) | — | domain-model 정의를 `DM-S-ProviderHealthState` 로 교정(§4.16·§6.6) | ✅ |

### c-2. SCR ID: api.md 가 인용하나 screens.md 에 정의 없음 — ✅ 해결됨(api.md 치환)

| api.md 구 인용 | screens.md 실제 정의(치환 후) | 상태 |
|---|---|---|
| SCR-410 (`/models`) | SCR-407 | ✅ |
| SCR-411 (`/my-usage`) | SCR-408 | ✅ |
| SCR-412 (`/privacy`) | SCR-409(유저)·SCR-510(관리자 설정 §6.8) | ✅ |
| SCR-510 (`/providers`목록·§5.6/§7.5) | SCR-504(목록)·SCR-507(헬스)·SCR-508(사용량) | ✅(privacy 정책 SCR-510 충돌 해소) |
| SCR-511/512/513 (승인/제거/상세) | SCR-504/506/505 | ✅ |
| SCR-520~524 (join/status/pause/resume/leave/models 등) | SCR-601/608/609/610/611/605~607 | ✅ |
| 웹 대시보드 로그·상세 인용 | SCR-801~808 로 정합(§11) | ✅ |

### c-3. SCR ID: navigation.md 가 screens.md 와 다른 번호 사용 — ✅ 해결됨(navigation 치환)

| navigation 구 인용 | screens 정의(치환 후) | 상태 |
|---|---|---|
| SCR-400,500 (정의 없음) | SCR-401 입력·SCR-501 설정홈 | ✅ |
| SCR-405 "권한부족" | SCR-406(권한부족); SCR-405=Pool처리안내 라벨로 정정 | ✅ |
| SCR-406 "Provider 없음" | SCR-901 | ✅ |
| SCR-407 "timeout/fallback실패" | SCR-909(timeout)/SCR-910(fallback 실패) | ✅ |
| SCR-410/411/412 (models/usage/privacy) | SCR-407/408/409 | ✅ |
| SCR-508/509 (라우팅/프라이버시) | SCR-509(라우팅)·SCR-510(프라이버시) | ✅ |
| SCR-601~613 (프로바이더 13개) | SCR-601~611(11개)로 정합(join/토큰/대기/연결/모델/범위/한도/상태/pause/resume/leave) | ✅ |

### c-4. API ID: screens.md / navigation.md 인용이 api.md 정의에 없음 — ✅ 해결됨(치환)

> screens·navigation 의 약식/미정의 API ID 를 모두 api.md §1.3 의 `API-CMD-*`/`API-REST-*`/
> `API-INT-*`/`API-WS-*` 정의 ID 로 치환했다(§(c-8) 매핑표). 검증: nav/screens 가 인용하는
> 모든 `API-*` ID 가 api.md 에 정의 존재(0 missing). 추가 신규 정의: `API-CMD-HELP`(/help).

### c-5. REQ ID: 세 분서가 서로 다른 번호체계 — ✅ 해결됨(절번호로 재매핑)

| 분서 | 구 방식 | 정정 | 상태 |
|---|---|---|---|
| requirements.md | 절번호 REQ-501~515/601~606/701~708 | (정의처, rate limit 보강: REQ-701) | ✅ |
| screens.md | 로드맵 항목번호(REQ-539/353/373 등) | requirements 절번호 REQ-5xx/6xx/7xx 로 전수 재매핑 | ✅ |
| api.md | 로드맵 항목번호(REQ-373/451/569 등) | 〃 | ✅ |
| navigation.md | 임의 라운드값(REQ-160/280 등) | 〃 | ✅ |

→ 세 분서의 모든 REQ 참조가 requirements 정의 집합(REQ-501~515/601~606/701~708) 내로 정합
(검증: out-of-range 0건). 대응이 1:1 이 아닌 항목은 가장 가까운 요구사항 절번호로 연결하고
괄호 주석을 덧붙였다(예: 로드맵 627 rate limit → REQ-701).

### c-6. FLOW ID: requirements ↔ navigation 번호 불일치 — ✅ 해결됨(2자리 재정렬)

navigation 의 3자리/16진 FLOW(FLOW-040~049,050~057,060~06E,070~07A,080,090)를 **2자리 일련번호
+ 하위 `.n`** 으로 재정렬해 requirements §10.5 의 상위 앵커와 일치시켰다.

| requirements §10.5 앵커 | navigation 정정 후 |
|---|---|
| FLOW-01 봇 설치 | FLOW-01 |
| FLOW-02 서버 정책 | FLOW-02(.1 채널 .2 역할 .3 승인 .4 제거 .5 헬스 .6 라우팅 .7 프라이버시) |
| FLOW-03 등록·승인 | FLOW-03(.1~.12) |
| FLOW-04 Agent 연결 | FLOW-04(.0~.7) |
| FLOW-05 유저 질문 접수 | FLOW-05(.1~.8) |
| FLOW-08 요청 라우팅 | FLOW-08(.1~.13) |
| FLOW-09 응답 반환 | FLOW-09 |
| FLOW-10 pause/resume | FLOW-10(.1 pause .2 resume .3 leave) |
| FLOW-11 오프라인 | FLOW-11(.1 끊김 .2 세션만료 .3 수동종료) |
| FLOW-12 실패·fallback | FLOW-12 |

### c-7. 기타 모순/공백 — 대부분 해결, ADR 잔여

| 항목 | 정정 결과 | 상태 |
|---|---|---|
| RequestWeight 값 | domain-model §5.10 에 enum `light/medium/heavy` 확정 + 매핑(light→light,medium→standard,heavy→heavy). api §9.2 정합 | ✅ |
| RolePolicy 키 | `DM-V-RoleId`(snowflake) 저장 키 확정, `DM-V-RoleTier`(§5.14) 파생 표현값으로 분리. api §4.10/§4.10b·§6.5/§6.6 정합 | ✅ |
| ProviderState `approved→online_idle` 트리거 | 표현 차이(경미), 동등 — 유지 | ✅(경미) |
| `DM-V-PrivacyMode` | domain-model §5.13 값 객체 신설(A_ANONYMOUS/B_PARTIAL/C_ADMIN_ONLY, 기본 C_ADMIN_ONLY). api/screens/nav 인용 정합 | ✅ |
| `contribution_log` 엔티티 | domain-model §4.17 `DM-E-ContributionLog`(프로바이더 관점) 신설, §4.15 `DM-E-UsageLog`(요청자 관점)와 분리. screens/nav 인용 정합 | ✅ |
| rate limit | requirements §7.1 REQ-701 에 명문화, screens SCR-411 쿨다운·api ERR-RATE-LIMITED 추가 | ✅ |
| /help·쿨다운 화면 | screens SCR-410(`/help`)·SCR-411(쿨다운) 신설, api API-CMD-HELP, navigation 흐름 반영 | ✅ |
| ADR 0003 | 파일 미작성("작성 예정", 로드맵 301/663) — 5분서가 근거로 인용하나 실재 안 함 | ⚠️ 잔여(P1) |

### c-8. 정정 매핑표 (구 인용 → 실제 정의 ID, 대표)

| 종류 | 구 인용(약식/미정의) | 정정(SSOT 정의 ID) |
|---|---|---|
| API | `API-ASK` | `API-CMD-ASK` |
| API | `API-ROUTE` | `API-INT-SELECT`/`API-INT-DISPATCH`/`API-INT-CREATE-REQUEST` |
| API | `API-POLICY-CHECK` | `API-INT-CREATE-REQUEST`(정책체크 포함) |
| API | `API-MODELS`/`API-MY-USAGE`/`API-PRIVACY` | `API-CMD-MODELS`/`API-CMD-MY-USAGE`/`API-CMD-PRIVACY` |
| API | `API-WS-AUTH` | `API-WS-PROVIDER-HELLO`+`API-WS-AUTH-OK`/`API-WS-AUTH-ERR` |
| API | `API-TOKEN-ISSUE`/`API-SESSION-CREATE` | `API-REST-PROVIDER-APPROVE`/`API-WS-AUTH-OK` |
| API | `API-CH-ALLOW`/`API-CH-DENY`/`API-ROLE-POLICY` | `API-CMD-LLM-ALLOW-CHANNEL`/`-DENY-CHANNEL`/`-ROLE-POLICY` |
| API | `API-DASH-*` | `API-REST-ADMIN-*`/`API-REST-USAGE-*`/`API-REST-LOG-*` |
| SCR | SCR-410/411/412 | SCR-407/408/409 |
| SCR | SCR-511/512/513, SCR-520~524 | SCR-505/506(상세/제거), SCR-601·608·609·610·611·605~607 |
| FLOW | FLOW-040/080/090 등(3자리) | FLOW-05.1/FLOW-08/FLOW-12 등(2자리) |
| DM-S | DM-S-AgentConn/ProviderSession/Approval/ProviderHealth | …Connstate→`DM-S-AgentConnState` 외 `…State` 접미사 |
| DM-E | `contribution_log` | `DM-E-ContributionLog` |
| REQ | 로드맵 항목번호(REQ-539/353/373 등) | requirements 절번호(REQ-510/505/508 등) |

### 잔여 미해결
- **ADR 0003 미작성**(P1): `docs/adr/0003-community-provider-pool.md` 실파일 부재. 5분서는 이미
  파일 경로를 가정해 인용(README §기타 보강) — ADR 본문 작성은 별도 작업.
