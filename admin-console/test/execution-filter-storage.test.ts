import assert from "node:assert/strict";
import test from "node:test";

import {
  rememberExecutionChannelId,
  restoreExecutionChannelId,
} from "../src/execution-filter-storage.js";

class MemoryStorage {
  private readonly values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }
}

const channels = [
  { id: "1509347932665675867", name: "general" },
  { id: "1509347932665675868", name: "nia" },
];

test("선택한 실행 기록 채널을 서버별로 복원한다", () => {
  const storage = new MemoryStorage();
  rememberExecutionChannelId("guild-a", channels[1].id, storage);
  rememberExecutionChannelId("guild-b", channels[0].id, storage);

  assert.equal(restoreExecutionChannelId("guild-a", channels, storage), channels[1].id);
  assert.equal(restoreExecutionChannelId("guild-b", channels, storage), channels[0].id);
});

test("저장된 채널이 현재 서버에 없으면 선택하지 않는다", () => {
  const storage = new MemoryStorage();
  rememberExecutionChannelId("guild-a", "deleted-channel", storage);

  assert.equal(restoreExecutionChannelId("guild-a", channels, storage), "");
});

test("채널 선택을 해제하면 저장값도 지운다", () => {
  const storage = new MemoryStorage();
  rememberExecutionChannelId("guild-a", channels[0].id, storage);
  rememberExecutionChannelId("guild-a", "", storage);

  assert.equal(restoreExecutionChannelId("guild-a", channels, storage), "");
});
