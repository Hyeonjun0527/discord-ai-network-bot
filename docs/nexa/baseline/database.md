# NEXA database migration baseline (T018)

Status: `REVIEW` candidate; depends on T017 human security gate.  
Audit date: 2026-06-20.  
Scope: `central-server/src/main/resources/db/migration/**`, Spring Boot/Flyway configuration, Testcontainers test definition, and the currently running local compose Postgres container when present. No production credentials or `.env*` values were read.

## Current migration chain

- Versioned SQL files: 49 (`V1__init.sql` through `V49__user_imagine_post_confirm.sql`).
- Ordering rule: numeric Flyway version order, not filesystem lexicographic order (`V10` can appear before `V1` in plain `find` output).
- Runtime owner: Flyway. Hibernate DDL is disabled by `spring.jpa.hibernate.ddl-auto: none`.
- Default local/dev database: H2 PostgreSQL mode; production/compose database: PostgreSQL 16 via `DB_URL`.
- `spring.flyway.baseline-on-migrate: true` is enabled; do not use that as a substitute for checking `flyway_schema_history` on non-empty databases.

## Clean PostgreSQL application evidence

A temporary `postgres:16-alpine` container was started with an empty `central` database. Then `central-server/gradlew bootRun` was launched against it with `SERVER_PORT=0` and `DISCORD_ENABLED=false`, letting the application-owned Flyway runner apply migrations.

Observed result:

```text
Flyway database: PostgreSQL 16.14
Successfully applied 49 migrations to schema "public", now at version v49
flyway_schema_history success rows: 49, max installed_rank: 49, max version: 49
first versions: 1:init, 2:guild defaults, 3:provider schedule, 4:guild welcome, 5:channel ai profile
last versions: 45:billing event, 46:license audit, 47:user affinity, 48:channel ai auto respond, 49:user imagine post confirm
public base tables after migration: 49
```

A direct `PostgresFlywayIntegrationTest` run was also attempted. It did not reach migrations because the local Testcontainers Docker client selected a broken `/var/run/docker.sock` strategy even though Docker Desktop containers were available. The clean-DB evidence above therefore uses the same application Flyway runner against an explicitly started PostgreSQL 16 container, not raw `psql` SQL replay.

## Existing runtime schema comparison

The repository deploy compose files (`central-server/docker-compose.yml`, `central-server/deploy/compose.remote.yml`) both use PostgreSQL 16 and let the Spring application run Flyway on startup. The only live schema inspected in this task was the currently running local compose container `central-server-db-1`; remote production was not queried because that requires operational credentials and should be a separate human-approved check.

| Runtime source | Success rows | Max version | Public tables | Difference from current repo |
| --- | ---: | ---: | ---: | --- |
| Clean temporary PostgreSQL 16 via current `bootRun` | 49 | 49 | 49 | Baseline target. |
| Existing local compose `central-server-db-1` | 33 | 33 | 40 | Missing V34-V49: onboarding, custom instruction, opt-out, XP/level, NEXA defaults, encrypted preview widening, prompt sets, provider-role removal, daily limit, expert forwarding, licensing/billing/audit, affinity, auto-respond, imagine confirmation. Rebuild/restart with the current application image should let Flyway advance it; do not edit old migrations. |

## Rollback and repair policy

1. Migration files already applied anywhere must be immutable. Fixes are new `V{n+1}__...sql` migrations only.
2. Application rollback means rolling back the container image, not automatically rolling back schema. If a new migration is not backward compatible with the previous image, document the required manual database restore before deploy.
3. Database rollback for destructive migration failure is restore-from-backup (`pg_dump`/volume snapshot) as described in `central-server/docs/RUNBOOK.md`; Flyway has no down-migration chain in this repository.
4. Before any production promotion that includes DB changes, check `flyway_schema_history` on the target DB for failed rows, unexpected gaps, and current max version.
5. Keep `ddl-auto=none`; schema drift must be expressed as SQL migrations and verified by central build plus a PostgreSQL clean-apply check when Docker is available.

## Migration manifest and source checksums

| Version | File | Description | SHA-256 | Lines |
| ---: | --- | --- | --- | ---: |
| 1 | `V1__init.sql` | init | `f113fa0249a127ded70213ddff12d4f3b60da3006064133dde67078c37ee6892` | 83 |
| 2 | `V2__guild_defaults.sql` | guild defaults | `fa9ed7312f2e18d94689904b88d32b6545aa989c239ea4a6745849b3c43117b7` | 3 |
| 3 | `V3__provider_schedule.sql` | provider schedule | `92b620e2ff3235c04f5f4404add89ff5e7a47626245c65b4918db8f7dd152a40` | 10 |
| 4 | `V4__guild_welcome.sql` | guild welcome | `03f34b0d3b3a59b8abdd2fdbf15d17c8f43a05a8308ce9ba77c90f7fd1eab35a` | 2 |
| 5 | `V5__channel_ai_profile.sql` | channel ai profile | `705e7efc7afed2c1c3269fb97d0dab48b13a9aa17545f7bfcff30b703899e27b` | 9 |
| 6 | `V6__contribution_guild_scope.sql` | contribution guild scope | `52a55f8aab2e88dadacff25cae0d164cba8152a3d8e68f4943390e1b332706ab` | 14 |
| 7 | `V7__channel_ai_foundation.sql` | channel ai foundation | `d4de3823a7ad48c3cbec2e5f990145b34075f36b0cfbb61af27442575be32c20` | 98 |
| 8 | `V8__ai_network_foundation.sql` | ai network foundation | `a4a768b56ea7485208e1faed3c5673ae8c98083354ab22a8d2300c9537703dd0` | 250 |
| 9 | `V9__ai_network_events.sql` | ai network events | `2c948575c4793232903e9c37d050e000ea9675c1f9abd37d6e520737d55030c8` | 13 |
| 10 | `V10__channel_ai_routing_policy.sql` | channel ai routing policy | `4d21bfb6618c5894df8ad268ec8b06121f6e6fd04678bc47496e9dc433e8a1e8` | 18 |
| 11 | `V11__preset_import_channel_ai_link.sql` | preset import channel ai link | `1b74091af90c4b84362f24a247ba0759e624a32f18cdeb6635777de5d8017f36` | 15 |
| 12 | `V12__multi_response_synthesis_summary.sql` | multi response synthesis summary | `66299d1aa7b889e4f6d16ee75058abae61e8e5a68fde62954f4e854c2b23fc00` | 8 |
| 13 | `V13__multi_response_rag_context_snapshot.sql` | multi response rag context snapshot | `d9d4eb049c861799d38ffb4a40eba12b632d97b9fd38ed857e71aeb07c3c6912` | 8 |
| 14 | `V14__preset_routing_snapshot.sql` | preset routing snapshot | `35d24b458e669bc7faed21b1bda8be7179f1def0384415e521728712bf9f4eca` | 17 |
| 15 | `V15__ai_change_proposal_payload_hash.sql` | ai change proposal payload hash | `097fb3ea1c7c948b437b1a93756b671d6487ce592fbb9e99c08fe9449b572b79` | 2 |
| 16 | `V16__published_preset_slug.sql` | published preset slug | `83b22531304358b8669c1396f5a96e7504b3cc4a31f6f2cdb158ab401cf00858` | 9 |
| 17 | `V17__rag_index_domain.sql` | rag index domain | `c3cd62ec7ff3c8e2f8d1513599eae65027be0299ae1dca505960c4217f74f4d1` | 75 |
| 18 | `V18__preset_knowledge_slots.sql` | preset knowledge slots | `cc04a4c926a26c2eabe960eaf4a29e5174ad480f38b84dd9c29c61c8df0dbafe` | 5 |
| 19 | `V19__ai_feedback_review_fields.sql` | ai feedback review fields | `601452a10d7f76eea3742509976cd9a7474e5944e7d082887dfaccffc241eff1` | 5 |
| 20 | `V20__ai_change_proposal_routing_snapshot.sql` | ai change proposal routing snapshot | `43b4f6d4253c876df0596dd0c735c6b0e8147adaede487bcdfb8f63b6ddf3c31` | 1 |
| 21 | `V21__preset_import_source_revision.sql` | preset import source revision | `34b4c95e8f99b17b9286903b4edd278dea75a73d6a4cdb8a6bc3fef78c3c415b` | 8 |
| 22 | `V22__preset_report_reviewer.sql` | preset report reviewer | `e18d4390a5ff7dae5c4da7a53babb79986b39270c40ae38ed124ca44a7e1606d` | 4 |
| 23 | `V23__preset_revision_tags_examples.sql` | preset revision tags examples | `71001b500d9800c8e2b1033242814673a13314b370975f1c232656d2f906eeec` | 5 |
| 24 | `V24__preset_report_reason_fields.sql` | preset report reason fields | `df80fbec0a1042ffe1f90b0787163b1e4b7d85a05cae7fedcf4a12c78a57d9ce` | 7 |
| 25 | `V25__multi_response_policy_disable_reason.sql` | multi response policy disable reason | `70ffba541f3944edc916846ec433a3ebde9b40f70b2f6a222bd52974e8097a5f` | 5 |
| 26 | `V26__ai_admin_role.sql` | ai admin role | `881dbb5bb17fca9816ffc42af397d654d5804e409b51ee12b82276155f879390` | 10 |
| 27 | `V27__provider_durable_revocation.sql` | provider durable revocation | `8e16220a4f02fb95c81ab52178e46c7fa3dd4839e2e1a56c92691bd75b181e19` | 13 |
| 28 | `V28__query_indexes_audit.sql` | query indexes audit | `c397d805c636550b50ec184df5770d3252fcff9a47f099f6153fe27a5dbb2dd9` | 43 |
| 29 | `V29__blocklist.sql` | blocklist | `0239a002b339015829219244c5c8a46ff08785b97f58429dbbc5be4559a17b7a` | 12 |
| 30 | `V30__widen_text_columns.sql` | widen text columns | `4adb9ede3e8d431b47abaa54655323ef2824180f83763c1624fabc8c80f165d4` | 17 |
| 31 | `V31__drop_contribution_guild_default.sql` | drop contribution guild default | `4dca41b0dee653a7b8b8a0cc76b615099054cf9297be1904db7f621290a4c74b` | 7 |
| 32 | `V32__unique_single_row_policies.sql` | unique single row policies | `86177e8a3ad0ba7430824d083d48e7885da0b8a9f434a1d2c966b6e591767146` | 30 |
| 33 | `V33__merge_channel_ai_profile.sql` | merge channel ai profile | `62aed005242415d6ac6552daf6cbc008c9826d0229711342047263a95b6bb5a1` | 49 |
| 34 | `V34__guild_onboarding.sql` | guild onboarding | `ebd9dae4055639086076bbee926c56f4b7767aec0829ef3af8b2bd88401c39f2` | 31 |
| 35 | `V35__behavior_custom_instruction.sql` | behavior custom instruction | `971f837de31e63595300e4c8bc253447e7a808853b77a5d1fe276069ffa3a511` | 8 |
| 36 | `V36__guild_onboarding_opt_out.sql` | guild onboarding opt out | `6966beb5a624e85bcc37adc40591dd5d8ec06c36c2af9f66b04b711e06d823e2` | 12 |
| 37 | `V37__ai_xp_level.sql` | ai xp level | `f3018d6ae465dbca497e722598badbe6f1e8ee667edd48a82ec3ef5af3bd65f7` | 11 |
| 38 | `V38__nexa_rebrand_defaults.sql` | nexa rebrand defaults | `ceb3ce8c5ca48689ba97df8740d6f4c5c498d8862a3a370529c1d3cb4add9fec` | 4 |
| 39 | `V39__widen_content_preview_for_encryption.sql` | widen content preview for encryption | `bcf8c375d6b5a55d84e05ee781e755bd7982e2462991433a7ea3df1acb1c24a6` | 3 |
| 40 | `V40__global_prompt_set.sql` | global prompt set | `287e78f9d974e3581b1991e0ad7efebff37082ab5f21a9790df30d1669fa3cfc` | 17 |
| 41 | `V41__drop_provider_allowed_role.sql` | drop provider allowed role | `50e03d0c8e639fd2e053bab29eb24d2827ba12a3460c2ddb86107f695e32e895` | 4 |
| 42 | `V42__guild_default_daily_limit.sql` | guild default daily limit | `ab6fc5cfbdd893103a962650b4e6b4f6f9c719d5861f6c34513e451c30d003e0` | 3 |
| 43 | `V43__guild_expert_forward_channel.sql` | guild expert forward channel | `f177875a7102f6294e18f2025490171277aedbe69b5408cbd7c42395a7bad33f` | 3 |
| 44 | `V44__user_license.sql` | user license | `2ca694c0b2082ecdc4b86de88324fe869f654f766bc088711a7d97aa5775b642` | 19 |
| 45 | `V45__billing_event.sql` | billing event | `1dbbab60428510fb0a084cc629604ec942979d858317f41b3eece6e65b5db7cc` | 12 |
| 46 | `V46__license_audit.sql` | license audit | `9c167bfd0fafb57d6715719a6c95a8325b43e385700907bcc4a2aa21a060f5d9` | 11 |
| 47 | `V47__user_affinity.sql` | user affinity | `671e74741e010973304d6f051c11d99fb1f7c8129c45d3627074891f3dee8b22` | 12 |
| 48 | `V48__channel_ai_auto_respond.sql` | channel ai auto respond | `77268412cacd67c0e27d902b25c2133121e885ac8b26ec81a5b5a59f9cc5cb6a` | 3 |
| 49 | `V49__user_imagine_post_confirm.sql` | user imagine post confirm | `caf4e06ec242915e5931e16c466d9b190675d1daa63a3f34666e2dcd672b7fb8` | 7 |

## Verification commands for this task

- `./scripts/nexa-verify.sh docs`
- `./scripts/nexa-verify.sh central`

Because T018 depends on T017 and T017 is still human-gated `REVIEW`, this task should also remain `REVIEW` after automated verification. It can move to `VERIFIED` only after T017 is approved or the task graph dependency is revised.
