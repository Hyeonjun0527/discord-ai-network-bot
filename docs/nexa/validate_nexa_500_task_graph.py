#!/usr/bin/env python3
from __future__ import annotations
import sys
from collections import defaultdict, deque
from pathlib import Path
import yaml

path = Path(sys.argv[1] if len(sys.argv) > 1 else 'nexa_500_task_graph.yaml')
data = yaml.safe_load(path.read_text(encoding='utf-8'))
tasks = data.get('tasks', [])
errors = []

if len(tasks) != 500:
    errors.append(f'expected 500 tasks, got {len(tasks)}')
ids = [t.get('id') for t in tasks]
if len(set(ids)) != len(ids):
    errors.append('duplicate task IDs detected')
by_id = {t['id']: t for t in tasks if t.get('id')}
for task in tasks:
    for dep in task.get('depends_on', []):
        if dep not in by_id:
            errors.append(f"{task['id']}: missing dependency {dep}")
    required = ['program', 'title', 'status', 'kind', 'recommended_paths', 'deliverable', 'acceptance', 'verification', 'human_gate']
    for key in required:
        if key not in task:
            errors.append(f"{task.get('id')}: missing field {key}")

indegree = {task_id: 0 for task_id in by_id}
adj = defaultdict(list)
for task in tasks:
    for dep in task.get('depends_on', []):
        adj[dep].append(task['id'])
        indegree[task['id']] += 1
queue = deque([task_id for task_id, degree in indegree.items() if degree == 0])
visited = 0
while queue:
    node = queue.popleft()
    visited += 1
    for nxt in adj[node]:
        indegree[nxt] -= 1
        if indegree[nxt] == 0:
            queue.append(nxt)
if visited != len(by_id):
    errors.append('dependency cycle detected')

for p in data.get('programs', []):
    count = sum(1 for t in tasks if t.get('program') == p.get('id'))
    if count != 25:
        errors.append(f"program {p.get('id')} expected 25 tasks, got {count}")
    gate_id = f"NEXA-{p.get('id')}-T025"
    gate = by_id.get(gate_id)
    if not gate or gate.get('kind') != 'review' or not gate.get('human_gate'):
        errors.append(f"program {p.get('id')} missing human review gate at T025")

if errors:
    print('INVALID')
    for error in errors:
        print(f'- {error}')
    raise SystemExit(1)
print(f"VALID: {len(tasks)} tasks, {len(data.get('programs', []))} programs, DAG acyclic")
