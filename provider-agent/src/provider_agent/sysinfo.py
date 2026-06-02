"""자원 상태 감지 (차수 3, 선택적 psutil). 배터리/부하를 provider_status 로 보고한다."""
from __future__ import annotations

try:  # psutil 은 선택 의존성(`.[monitor]`)
    import psutil  # type: ignore[import-untyped]

    _HAVE_PSUTIL = True
except ImportError:  # pragma: no cover
    _HAVE_PSUTIL = False

# 이 사용률(%) 이상이면 고부하로 본다.
HIGH_LOAD_PERCENT = 85.0


def load_level() -> str:
    """'idle' | 'high'. psutil 없으면 항상 'idle'."""
    if not _HAVE_PSUTIL:
        return "idle"
    try:
        return "high" if psutil.cpu_percent(interval=None) >= HIGH_LOAD_PERCENT else "idle"
    except Exception:  # pragma: no cover
        return "idle"


def battery_state() -> str:
    """'charging' | 'discharging' | '' (배터리 없음/psutil 없음)."""
    if not _HAVE_PSUTIL:
        return ""
    try:
        bat = psutil.sensors_battery()
    except Exception:  # pragma: no cover
        return ""
    if bat is None:
        return ""
    return "charging" if bat.power_plugged else "discharging"


def should_pause(pause_on_battery: bool = True, pause_on_high_load: bool = True) -> tuple[bool, str]:
    """자원 보호용 자동 pause 판단. (pause 여부, 사유) 를 반환한다.

    - CPU 고부하('high') → pause(다른 작업 보호)
    - 배터리 방전 중('discharging') → pause(노트북 배터리 보호)

    psutil 이 없으면 신호를 못 읽어 항상 (False, '') — 즉, 모니터 의존성 없이도 동작은 한다.
    """
    if pause_on_high_load and load_level() == "high":
        return True, "high_load"
    if pause_on_battery and battery_state() == "discharging":
        return True, "on_battery"
    return False, ""
