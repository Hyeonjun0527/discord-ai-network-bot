"""end-to-end: 합성 이벤트 → export 경계 → 세션 → 마스킹 예제 → 라벨 → split.

builder 가 fixture 로 실제 동작함을 증명한다(원문/실제 id 미포함 불변식 포함).
"""

from __future__ import annotations

from nexa_policy.data.export.boundary import ApprovedProjection, build_export_manifest
from nexa_policy.data.labels.action import ActionClass, label_action
from nexa_policy.data.labels.delay import label_delay
from nexa_policy.data.labels.social_act import label_social_act
from nexa_policy.data.labels.target import label_target
from nexa_policy.data.masking import LabelTargets, build_masked_example
from nexa_policy.data.schema import conform
from nexa_policy.data.sessionize import sessionize
from nexa_policy.data.split import Split, assert_no_guild_leakage, split_by_guild
from tests.conftest import make_event


def _multi_guild_events() -> list:
    events = []
    for g in range(12):
        events.append(
            make_event(event_id=f"q-{g}", time_ms=1_000, actor="human-1",
                       guild=f"guild-{g}", features={"is_question": True})
        )
        events.append(
            make_event(event_id=f"a-{g}", time_ms=3_000, actor="human-2",
                       guild=f"guild-{g}", kind="reply",
                       features={"reply_to_event_id": f"q-{g}", "char_len_bucket": 2})
        )
    return events


def test_full_pipeline_runs_and_labels() -> None:
    events = _multi_guild_events()

    # 1) export 보안 경계(eligibility).
    proj = ApprovedProjection(source="participation_training_projection_v1",
                              records=tuple(events))
    manifest = build_export_manifest(proj)
    assert manifest.included_count == len(events)

    # 2) 길드별로 세션화 → 마스킹 예제 → 라벨.
    examples = []
    for g in range(12):
        guild_events = [e for e in manifest.eligible if e.guild_pseudonym == f"guild-{g}"]
        sessions = sessionize(guild_events)
        assert sessions
        for sess in sessions:
            cut = sess.events[0].event_time_ms
            action = label_action(
                masked_actor="human-2", cut_time_ms=cut, window_ms=10_000,
                events=list(sess.events), is_observable=True,
            )
            action_event = next(
                (e for e in sess.events
                 if e.actor_pseudonym == "human-2" and e.event_time_ms > cut), None
            )
            target = label_target(action_event=action_event, prior_events=list(sess.events))
            delay = label_delay(
                masked_actor="human-2", cut_time_ms=cut, events=list(sess.events),
                session_end_ms=sess.end_ms, observed_full_window=True,
            )
            social = (
                label_social_act(action_event=action_event)
                if action_event is not None else None
            )
            ex = build_masked_example(
                guild_pseudonym=sess.guild_pseudonym,
                session_id=sess.session_id,
                masked_actor="human-2",
                cut_time_ms=cut,
                all_events=list(sess.events),
                target=LabelTargets(
                    action=action.to_dict(),
                    target=target.to_dict(),
                    delay=delay.to_dict(),
                    social_act=social.to_dict() if social else None,
                ),
            )
            examples.append(ex)
            assert action.action is ActionClass.SPEAK

    # 3) 길드 단위 split — 누출 없음.
    split_map = split_by_guild(examples, guild_of=lambda e: e.guild_pseudonym, seed=0)
    assert_no_guild_leakage(split_map, guild_of=lambda e: e.guild_pseudonym)
    assert sum(len(v) for v in split_map.values()) == len(examples)
    assert any(len(split_map[s]) > 0 for s in (Split.TRAIN, Split.VALIDATION, Split.TEST))


def test_pipeline_records_stay_schema_conformant_no_raw_content() -> None:
    for ev in _multi_guild_events():
        row = conform(ev.to_row())
        joined = " ".join(str(k) for k in row.keys()).lower()
        assert "content" not in joined
        assert "user_id" not in joined
        # actor 는 가명(순수 숫자 아님).
        assert not row["actor_pseudonym"].isdigit()
