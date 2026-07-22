import type { ChannelSummary } from "./api-contract.js";

const EXECUTION_CHANNEL_STORAGE_PREFIX = "nexa-console-execution-channel-id";

type SelectionStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

function channelStorageKey(guildId: string): string {
  return `${EXECUTION_CHANNEL_STORAGE_PREFIX}:${guildId}`;
}

export function restoreExecutionChannelId(
  guildId: string,
  channels: ChannelSummary[],
  storage: SelectionStorage,
): string {
  if (!guildId) return "";
  const storedChannelId = storage.getItem(channelStorageKey(guildId)) ?? "";
  return channels.some((channel) => channel.id === storedChannelId) ? storedChannelId : "";
}

export function rememberExecutionChannelId(
  guildId: string,
  channelId: string,
  storage: SelectionStorage,
): void {
  if (!guildId) return;
  const key = channelStorageKey(guildId);
  if (channelId) storage.setItem(key, channelId);
  else storage.removeItem(key);
}
