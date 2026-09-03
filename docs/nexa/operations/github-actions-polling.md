# GitHub Actions polling policy

GitHub API quota is an operational resource. Do not watch CI by running tight `gh run view`,
`gh pr checks`, or Octokit loops. Default polling must be at least 8 minutes apart unless a human
explicitly chooses emergency live monitoring.

Use the bounded helper:

```bash
scripts/gh-run-watch-safe.sh RUN_ID
```

Defaults:

- repo: `Hyeonjun0527/discord-ai-network-bot`
- interval: `480` seconds
- max checks: `30`
- one GitHub API-backed run lookup per interval

Emergency override is explicit and noisy:

```bash
GH_RUN_WATCH_ALLOW_FAST=true GH_RUN_WATCH_INTERVAL_SECONDS=60 scripts/gh-run-watch-safe.sh RUN_ID
```

Prefer non-GitHub checks when possible:

- central deploy health: `scripts/diagnose-central-ops.sh`
- remote service state: `ssh ssh.yeon.world 'systemctl status actions.runner.Hyeonjun0527-discord-ai-network-bot.discord-prod-01.service --no-pager'`
- deployed image and local health: `ssh ssh.yeon.world 'cd /srv/central-server && docker compose ps'`

If GitHub returns a 403 rate-limit or secondary-rate-limit response, stop polling and wait. Retrying
immediately is a failure mode, not a recovery strategy.
