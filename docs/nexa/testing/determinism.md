# NEXA Deterministic Testing Convention

Status: baseline rule for NEXA work from `NEXA-P00-T015` onward.  
Scope: every new or materially changed NEXA test and every new NEXA production path under `central-server/`. Existing legacy call sites listed below are baseline debt, not permission to add more.

## Goal

NEXA tests must replay the same Discord events, provider decisions, timestamps, request IDs, and scheduler steps on every machine. A failing run should be explainable from fixture data, not from wall-clock timing, OS scheduling, or a lucky random value.

## Current baseline scan

Baseline command used on 2026-06-20:

```bash
grep -RInE 'Thread\.sleep|Instant\.now|LocalDateTime\.now|LocalDate\.now|OffsetDateTime\.now|ZonedDateTime\.now|System\.currentTimeMillis|System\.nanoTime|UUID\.randomUUID|Random\(|kotlin\.random|runBlocking|delay\(' \
  central-server/src/test \
  central-server/src/main/kotlin/com/discordassistant/central
```

Observed direct-time or direct-random usage exists in older code and tests:

| Category | Existing examples | NEXA handling |
| --- | --- | --- |
| Good fixed-clock test | `central-server/src/test/kotlin/com/discordassistant/central/network/AiNetworkFoundationServiceTest.kt` uses `Instant.now(fixedClock)` | Prefer this style. |
| Direct wall-clock tests | `ProviderRegistrationPersistenceTest.kt`, `PersistenceTest.kt` use `Instant.now()` | Convert when touched; do not copy. |
| Fixed sleeps in tests | `ProviderRegistrationTest.kt`, `ProviderSessionTest.kt` use `Thread.sleep(...)` | Baseline debt; new NEXA tests must not add fixed sleeps. |
| Direct production wall-clock | `DiscordGatewayStatus.kt`, `ChannelAiProfileService.kt`, `QuotaService.kt`, `UsageService.kt`, `AutoRespondChannelRegistry.kt`, `WebSearch.kt` | Existing baseline only; new NEXA services inject `Clock` or explicit instants. |
| Direct IDs and timing | `AskCommandHandler.kt`, `RequestOrchestrator.kt`, routing domain services use `UUID.randomUUID()` or system timers | Inject ID and time providers for new decision logic. |
| Benchmark or metrics timers | `RoutingBenchmarkTest.kt` and command metrics use `System.nanoTime()` | Allowed only for monotonic duration measurement, never for behavior decisions or fixture assertions. |
| Seeded simulation randomness | `RoutingSimulationBenchmarkReportTest.kt` uses `Random(seed)` | Acceptable when the seed is fixed and recorded. |

## Rules

### 1. Wall-clock time

- New NEXA production code must not call `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()`, `OffsetDateTime.now()`, `ZonedDateTime.now()`, or `System.currentTimeMillis()` directly.
- Production code must receive `java.time.Clock`, an explicit `Instant`, or a domain-specific time provider at the boundary.
- Tests must use `Clock.fixed(...)`, a mutable fake clock, or literal `Instant.parse(...)` values.
- Test assertions must compare exact timestamps. Avoid “now-ish” windows such as `isAfter(now.minusSeconds(...))` unless the test is explicitly about metrics tolerance.

```kotlin
val clock = Clock.fixed(
    Instant.parse("2026-01-01T00:00:00Z"),
    ZoneOffset.UTC,
)

val result = service.handle(event, clock)
assertThat(result.createdAt).isEqualTo(Instant.now(clock))
```

### 2. Monotonic durations

- `System.nanoTime()` is allowed only for metrics, tracing, and benchmark duration measurement.
- It must not choose providers, expire quotas, order Discord events, or drive assertions in functional NEXA tests.
- If decision logic needs elapsed time, inject a small ticker or clock abstraction and advance it explicitly in tests.

### 3. UUIDs and randomness

- New NEXA decision logic must not call `UUID.randomUUID()` or create an unseeded `Random()` directly.
- Inject an ID generator or random source at the boundary.
- Deterministic tests must use a fixed UUID sequence or `Random(seed)` and record the seed in the fixture or test name.
- `SecureRandom` remains valid for security tokens and cryptographic material, but code that needs deterministic tests must wrap it behind a port and substitute a deterministic fake in tests.

```kotlin
class RequestFactory(
    private val clock: Clock,
    private val newRequestId: () -> UUID,
) {
    fun create(prompt: String): RequestDraft = RequestDraft(
        id = newRequestId(),
        prompt = prompt,
        createdAt = Instant.now(clock),
    )
}

val ids = ArrayDeque(
    listOf(UUID.fromString("00000000-0000-0000-0000-000000000001")),
)
val factory = RequestFactory(clock) { ids.removeFirst() }
```

### 4. Schedulers and async work

- New NEXA tests must not depend on the real wall-clock scheduler or uncontrolled background work.
- Use a manual executor, fake scheduler, coroutine test scheduler, or explicit advancement hook.
- Fire-and-forget tasks must expose lifecycle control so a test can start, flush, and stop them deterministically.
- Integration tests may wait for external readiness only through observable state with bounded failure messages; they must not hide fixed sleeps inside helper code.

### 5. Sleep and delay

- New NEXA tests must not use `Thread.sleep(...)`, fixed-duration `delay(...)`, or real-time Awaitility polling to make races “probably pass.”
- Replace sleeps with one of:
  - direct method calls on the unit under test,
  - fake clock or scheduler advancement,
  - event-driven completion such as a completed future, channel receive, or latch triggered by the system under test,
  - an explicit readiness probe for external processes, with a short timeout and diagnostic failure text.
- Existing `Thread.sleep` uses in `ProviderRegistrationTest.kt` and `ProviderSessionTest.kt` are tracked as baseline debt. When those tests are edited for NEXA work, replace the sleep rather than extending it.

### 6. Fixture metadata

Every replayable NEXA fixture that depends on time, order, or randomness must declare:

- base instant and zone,
- deterministic ID sequence or UUID seed,
- random seed and generator type,
- scheduler advancement steps,
- expected event order when concurrent inputs are modeled.

## Review and enforcement

Before marking a NEXA task `VERIFIED`, review changed central code with this scan or a narrower equivalent:

```bash
grep -RInE 'Thread\.sleep|delay\(|Instant\.now\(\)|LocalDate(Time)?\.now\(|OffsetDateTime\.now\(|ZonedDateTime\.now\(|System\.currentTimeMillis|System\.nanoTime|UUID\.randomUUID|Random\(' \
  central-server/src/main/kotlin/com/discordassistant/central \
  central-server/src/test/kotlin
```

For now, enforcement is code review plus the task-specific verification evidence. When a stable NEXA package or fixture module exists, add a static guard that fails on new direct wall-clock, unseeded random, direct UUID, and fixed-sleep usage outside explicitly whitelisted metrics or benchmark paths.

## Transition rule

Existing direct wall-clock, UUID, random, scheduler, and sleep call sites are allowed only as captured baseline. A NEXA task that touches one of those files must either convert the touched path to controlled time or ID generation, or record a follow-up task with the exact file and reason conversion could not happen safely in the same change.
