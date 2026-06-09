"""ComfyUI→디스코드 브리지의 순수 로직 — WS URL 도출 + executed 이벤트 출력 이미지 필터."""
from provider_agent.comfy_watch import _output_images, _ws_url


def test_ws_url_http_to_ws():
    assert _ws_url("http://127.0.0.1:8188") == "ws://127.0.0.1:8188/ws?clientId=nexa-bridge"
    assert _ws_url("https://example/comfy/").startswith("wss://example/comfy/ws?")


def test_output_images_only_executed_final_outputs():
    # executed + type=output(SaveImage) 만 전달 대상.
    ev = {"type": "executed", "data": {"output": {"images": [
        {"filename": "a.png", "subfolder": "", "type": "output"},
        {"filename": "prev.png", "subfolder": "", "type": "temp"},  # 미리보기 → 제외
    ]}}}
    out = _output_images(ev)
    assert [i["filename"] for i in out] == ["a.png"]


def test_output_images_ignores_non_executed_and_malformed():
    assert _output_images({"type": "execution_success", "data": {}}) == []  # id 만, 이미지 없음
    assert _output_images({"type": "progress"}) == []
    assert _output_images({"type": "executed", "data": {"output": {}}}) == []
    assert _output_images("not a dict") == []
    assert _output_images({"type": "executed", "data": {"output": {"images": ["bad"]}}}) == []


def test_output_images_defaults_missing_type_to_output():
    # type 누락 시 output 으로 간주(보수적으로 최종 출력 취급).
    ev = {"type": "executed", "data": {"output": {"images": [{"filename": "x.png"}]}}}
    assert len(_output_images(ev)) == 1
