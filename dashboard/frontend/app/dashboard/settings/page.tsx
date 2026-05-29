"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { Save, Loader2, CheckCircle } from "lucide-react";

interface GuildConfig {
  guild_id: number;
  model: string;
  summary_limit: number;
  language: string;
  provider: string;
  // #80: 자동 요약 주기(분). null 이면 자동 요약 비활성화.
  auto_summary_interval: number | null;
  updated_at: string | null;
}

// #81: /api/models 응답에서 설치된 모델명을 끌어오기 위한 타입
interface ModelsResponse {
  models: { name: string }[];
  error?: string;
}

// 모델 select 에서 "직접 입력" 을 선택하면 텍스트 입력으로 전환하기 위한 센티넬 값
const CUSTOM_MODEL = "__custom__";

const LANGUAGES = [
  { value: "ko", label: "Korean (한국어)" },
  { value: "en", label: "English" },
  { value: "ja", label: "Japanese (日本語)" },
  { value: "zh", label: "Chinese (中文)" },
  { value: "es", label: "Spanish (Español)" },
  { value: "fr", label: "French (Français)" },
];

const PROVIDERS = [
  { value: "ollama", label: "Ollama (local)" },
  { value: "openai", label: "OpenAI (GPT)" },
  { value: "anthropic", label: "Anthropic (Claude)" },
];

export default function SettingsPage() {
  const [config, setConfig] = useState<GuildConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Form state (mirrors config)
  const [model, setModel] = useState("");
  const [summaryLimit, setSummaryLimit] = useState(50);
  const [language, setLanguage] = useState("ko");
  const [provider, setProvider] = useState("ollama");
  // #80: 자동 요약 주기(분). 토글 on 일 때만 값을 보낸다(off → null = 비활성화).
  const [autoSummaryEnabled, setAutoSummaryEnabled] = useState(false);
  const [autoSummaryInterval, setAutoSummaryInterval] = useState(60);

  // #81: /api/models 로 받은 설치된 모델 목록 + "직접 입력" 토글 상태
  const [availableModels, setAvailableModels] = useState<string[]>([]);
  const [customModel, setCustomModel] = useState(false);

  const guildId = () => localStorage.getItem("selectedGuildId") ?? "";

  useEffect(() => {
    const id = guildId();
    if (!id) {
      setLoading(false);
      return;
    }
    apiFetch<GuildConfig>(`/api/guilds/${id}/config`)
      .then((data) => {
        setConfig(data);
        setModel(data.model);
        setSummaryLimit(data.summary_limit);
        setLanguage(data.language);
        setProvider(data.provider);
        // #80: null 이면 비활성화, 값이 있으면 토글 on + 그 값을 표시한다.
        if (data.auto_summary_interval != null) {
          setAutoSummaryEnabled(true);
          setAutoSummaryInterval(data.auto_summary_interval);
        } else {
          setAutoSummaryEnabled(false);
        }
        setLoading(false);
      })
      .catch((err: Error) => {
        setError(err.message);
        setLoading(false);
      });
  }, []);

  // #81: 설치된 모델 목록을 불러와 드롭다운에 채운다. Ollama 가 꺼져 있거나
  // OpenAI/Anthropic 처럼 목록을 못 받는 경우엔 직접 입력으로 폴백한다.
  useEffect(() => {
    apiFetch<ModelsResponse>("/api/models")
      .then((data) => {
        const names = (data.models ?? []).map((m) => m.name).filter(Boolean);
        setAvailableModels(names);
      })
      .catch(() => {
        // 모델 목록 조회 실패는 치명적이지 않다 — 직접 입력으로 폴백.
        setAvailableModels([]);
      });
  }, []);

  // 현재 모델이 설치 목록에 없으면(예: 외부 provider) 직접 입력 모드로 시작한다.
  useEffect(() => {
    if (!model) return;
    if (availableModels.length > 0 && !availableModels.includes(model)) {
      setCustomModel(true);
    }
  }, [model, availableModels]);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    const id = guildId();
    // #80: 토글 on 이면 주기(분, 최소 5)를, off 면 null(비활성화)을 보낸다.
    const interval = autoSummaryEnabled ? autoSummaryInterval : null;
    if (interval != null && interval < 5) {
      setError("Auto-summary interval must be at least 5 minutes.");
      setSaving(false);
      return;
    }
    try {
      const updated = await apiFetch<GuildConfig>(`/api/guilds/${id}/config`, {
        method: "PUT",
        body: JSON.stringify({
          model,
          summary_limit: summaryLimit,
          language,
          provider,
          auto_summary_interval: interval,
        }),
      });
      setConfig(updated);
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-48 text-gray-500">
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
        Loading settings&hellip;
      </div>
    );
  }

  if (!config && !error) {
    return (
      <div className="text-gray-500 text-center py-16">
        Select a server to manage settings.
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-white">Server Settings</h1>
        {config?.updated_at && (
          <span className="text-xs text-gray-500">
            Last saved:{" "}
            {new Date(config.updated_at).toLocaleString(undefined, {
              dateStyle: "medium",
              timeStyle: "short",
            })}
          </span>
        )}
      </div>

      {error && (
        <div className="bg-discord-red/10 border border-discord-red/30 rounded-xl p-4 text-discord-red text-sm">
          {error}
        </div>
      )}

      <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5 space-y-6">
        {/* LLM Provider */}
        <div>
          <label className="block text-sm font-medium text-gray-300 mb-1">
            LLM Provider
          </label>
          <select
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
            className="w-full bg-discord-dark border border-white/10 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-discord-blurple"
          >
            {PROVIDERS.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>
          <p className="text-xs text-gray-500 mt-1">
            Ollama runs locally; OpenAI/Anthropic require an API key.
          </p>
        </div>

        {/* Model name — #81: /api/models 결과 기반 드롭다운 + 직접 입력 폴백 */}
        <div>
          <label className="block text-sm font-medium text-gray-300 mb-1">
            Model Name
          </label>
          {availableModels.length > 0 && !customModel ? (
            <select
              value={availableModels.includes(model) ? model : CUSTOM_MODEL}
              onChange={(e) => {
                if (e.target.value === CUSTOM_MODEL) {
                  setCustomModel(true);
                } else {
                  setModel(e.target.value);
                }
              }}
              className="w-full bg-discord-dark border border-white/10 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-discord-blurple"
            >
              {availableModels.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
              <option value={CUSTOM_MODEL}>Custom (enter manually)…</option>
            </select>
          ) : (
            <div className="space-y-1.5">
              <input
                type="text"
                value={model}
                onChange={(e) => setModel(e.target.value)}
                placeholder="e.g. llama3.1:8b, gpt-4o-mini"
                className="w-full bg-discord-dark border border-white/10 text-white rounded-lg px-3 py-2 text-sm placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-discord-blurple"
              />
              {availableModels.length > 0 && (
                <button
                  type="button"
                  onClick={() => setCustomModel(false)}
                  className="text-xs text-discord-blurple hover:underline"
                >
                  Choose from installed models
                </button>
              )}
            </div>
          )}
          <p className="text-xs text-gray-500 mt-1">
            {availableModels.length > 0
              ? "Pick an installed Ollama model, or enter a custom model name (e.g. for OpenAI/Anthropic)."
              : "Enter the model name (e.g. llama3.1:8b, gpt-4o-mini)."}
          </p>
        </div>

        {/* Summary limit */}
        <div>
          <label className="block text-sm font-medium text-gray-300 mb-1">
            Summary Message Limit
            <span className="ml-2 text-discord-blurple font-semibold">
              {summaryLimit}
            </span>
          </label>
          <input
            type="range"
            min={1}
            max={200}
            value={summaryLimit}
            onChange={(e) => setSummaryLimit(Number(e.target.value))}
            className="w-full accent-discord-blurple"
          />
          <div className="flex justify-between text-xs text-gray-500 mt-1">
            <span>1</span>
            <span>200 messages</span>
          </div>
        </div>

        {/* Language */}
        <div>
          <label className="block text-sm font-medium text-gray-300 mb-1">
            Response Language
          </label>
          <select
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
            className="w-full bg-discord-dark border border-white/10 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-discord-blurple"
          >
            {LANGUAGES.map((l) => (
              <option key={l.value} value={l.value}>
                {l.label}
              </option>
            ))}
          </select>
        </div>

        {/* Auto-summary interval (#80) */}
        <div>
          <label className="flex items-center gap-2 text-sm font-medium text-gray-300 mb-2">
            <input
              type="checkbox"
              checked={autoSummaryEnabled}
              onChange={(e) => setAutoSummaryEnabled(e.target.checked)}
              className="accent-discord-blurple w-4 h-4"
            />
            Automatic summaries
          </label>
          {autoSummaryEnabled ? (
            <div className="flex items-center gap-2">
              <input
                type="number"
                min={5}
                value={autoSummaryInterval}
                onChange={(e) => setAutoSummaryInterval(Number(e.target.value))}
                className="w-28 bg-discord-dark border border-white/10 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-discord-blurple"
              />
              <span className="text-sm text-gray-400">minutes between auto-summaries</span>
            </div>
          ) : (
            <p className="text-xs text-gray-500">
              Enable to have the bot post a summary on a fixed interval (minimum 5 minutes).
            </p>
          )}
        </div>
      </div>

      <div className="flex items-center gap-3">
        <button
          onClick={handleSave}
          disabled={saving}
          className="flex items-center gap-2 bg-discord-blurple hover:bg-[#4752c4] disabled:opacity-50 disabled:cursor-not-allowed text-white font-medium px-5 py-2.5 rounded-lg text-sm transition-colors"
        >
          {saving ? (
            <Loader2 className="w-4 h-4 animate-spin" />
          ) : (
            <Save className="w-4 h-4" />
          )}
          {saving ? "Saving…" : "Save Settings"}
        </button>

        {saved && (
          <span className="flex items-center gap-1.5 text-discord-green text-sm">
            <CheckCircle className="w-4 h-4" />
            Saved!
          </span>
        )}
      </div>
    </div>
  );
}
