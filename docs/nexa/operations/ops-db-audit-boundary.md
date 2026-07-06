# Operations DB audit boundary

운영 DB 점검은 두 lane으로 분리한다.

## Read-only audit lane

자동 실행 가능한 범위:

- `.github/workflows/central-deploy.yml` post-health policy audit
- `scripts/diagnose-central-ops.sh` `ops policy audit`

두 경로 모두 `psql` 실행 시 다음을 강제한다.

```bash
PGOPTIONS='-c default_transaction_read_only=on'
psql -v ON_ERROR_STOP=1 ...
```

따라서 audit SQL에 `INSERT`/`UPDATE`/`DELETE`/DDL이 섞이면 운영 DB가 쓰기 전에 실패한다. 이 lane은 `SELECT COUNT(*)`
형태의 불변식 검증만 허용한다.

현재 hard-fail 불변식:

- `missing_auto_respond_allow_list`
- `broken_auto_respond_behavior`
- `stale_routing_policy_channel_ai`

## Repair/migration lane

운영 DB를 쓰는 작업은 read-only audit lane에 넣지 않는다.

- schema 변경: 새 Flyway migration 파일로만 추가한다. 이미 적용된 migration은 수정하지 않는다.
- data repair: 별도 SQL/runbook으로 작성하고, 대상 row 수·rollback/backup 근거·실행자·실행 시각을 기록한다.
- deploy workflow의 post-health audit은 repair를 시도하지 않는다. 실패하면 배포를 빨간색으로 만들고 사람이 repair lane을
  선택하게 한다.
- `scripts/diagnose-central-ops.sh`의 `CENTRAL_OPS_REPAIR_RUNNER=true`는 GitHub runner systemd 서비스만 재시작한다.
  운영 DB write repair 옵션이 아니다.

수동 repair를 해야 할 때의 최소 절차:

1. read-only audit으로 실패 불변식과 count를 확인한다.
2. repair SQL을 transaction으로 작성한다.
3. 실행 전 대상 row count를 `SELECT`로 캡처한다.
4. `BEGIN` 후 최소 범위 update/insert를 수행하고 affected row count를 확인한다.
5. 같은 audit을 재실행해 count가 0인지 확인한다.
6. repair 근거를 해당 작업 문서나 incident note에 남긴다.
