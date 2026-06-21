# ArchUnit rules baseline

- Snapshot date: 2026-06-20 KST
- Branch: `feat/nexa-p00-t001-baseline`
- Commit inspected: `a0e2118b`
- Source: `central-server/src/test/kotlin/com/discordassistant/central/arch/ArchitectureTest.kt`
- Test report: `central-server/build/test-results/test/TEST-com.discordassistant.central.arch.ArchitectureTest.xml`
- Dependency: `central-server/build.gradle.kts` uses `com.tngtech.archunit:archunit-junit5:1.3.0`.

## Scope

`ArchitectureTest` imports production classes under `com.discordassistant.central` and excludes tests via
`ImportOption.DoNotIncludeTests` (`ArchitectureTest.kt:14-17`). This baseline records only currently active
rules. Historical comments such as removed `persistenceStaysLow` are **not** active ArchUnit rules and must not
be treated as current regressions.

## Current result

Command executed:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server test --no-daemon --console=plain \
  --tests 'com.discordassistant.central.arch.ArchitectureTest'
```

Result:

- Gradle: `BUILD SUCCESSFUL`
- XML report: `tests=9`, `failures=0`, `errors=0`, `skipped=0`
- Existing ArchUnit violations at this baseline: **0**

## Active rules

| Rule | Source lines | Intent | Baseline result |
| --- | --- | --- | --- |
| `sharedKernelIsPure` | `ArchitectureTest.kt:21-35` | `..central.shared..` must not depend on application, adapter, platform, Spring, JPA, or JDA packages. | PASS |
| `controllersLiveInWebLayers` | `ArchitectureTest.kt:42-51` | `*Controller` classes must live only in `..adapter.inbound.web..` or `..central.dev..`. | PASS |
| `controllersDoNotTouchPersistence` | `ArchitectureTest.kt:55-62` | Controllers must not depend on `..adapter.outbound.persistence..`. | PASS |
| `controllersDoNotInjectRepositories` | `ArchitectureTest.kt:64-71` | Controllers must not depend on classes ending with `Repository`. | PASS |
| `servicesNotInWebLayers` | `ArchitectureTest.kt:74-84` | Spring `@Service` classes must not live in web, dashboard, or dev packages. | PASS |
| `migratedDomainsArePure` | `ArchitectureTest.kt:90-113` | Migrated domain layers must not depend on application, adapter, infrastructure, Spring, JPA, or JDA. | PASS |
| `migratedPersistenceInAdapterOutbound` | `ArchitectureTest.kt:116-135` | JPA `@Entity` classes in migrated domains must live in `..adapter.outbound.persistence..`. | PASS |
| `routingDomainHasNoInfrastructure` | `ArchitectureTest.kt:140-154` | Routing domain must not depend on routing application/adapter, JPA, Spring Web/Data, or JDA. | PASS |
| `routingApplicationDoesNotDependOnAdapter` | `ArchitectureTest.kt:157-164` | Routing application must not depend on routing adapter implementations. | PASS |

## Baseline separation rule for NEXA

When adding NEXA architecture rules, do not weaken or silently rewrite the rules above just to make new work pass.
Separate outcomes as follows:

1. If one of the 9 baseline rules fails without touching that rule's intended boundary, treat it as a regression.
2. If a new NEXA rule fails while the 9 baseline rules still pass, record it as a new NEXA violation, not an existing baseline issue.
3. If the repository intentionally changes package boundaries, update this document in the same change as the ArchUnit rule and explain why the old baseline no longer applies.
4. Keep historical removed rules out of pass/fail counts unless they are reintroduced as active `@ArchTest` fields.

## Verification command for this baseline

Use the wrapper when checking this snapshot during the NEXA plan:

```bash
./scripts/nexa-verify.sh central
```

For a focused ArchUnit-only check, use the explicit Gradle command from the “Current result” section.
