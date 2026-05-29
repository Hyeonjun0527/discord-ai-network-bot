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
  updated_at: string | null;
}

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
        setLoading(false);
      })
      .catch((err: Error) => {
        setError(err.message);
        setLoading(false);
      });
  }, []);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    const id = guildId();
    try {
      const updated = await apiFetch<GuildConfig>(`/api/guilds/${id}/config`, {
        method: "PUT",
        body: JSON.stringify({
          model,
          summary_limit: summaryLimit,
          language,
          provider,
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

        {/* Model name */}
        <div>
          <label className="block text-sm font-medium text-gray-300 mb-1">
            Model Name
          </label>
          <input
            type="text"
            value={model}
            onChange={(e) => setModel(e.target.value)}
            placeholder="e.g. llama3.1:8b, gpt-4o-mini"
            className="w-full bg-discord-dark border border-white/10 text-white rounded-lg px-3 py-2 text-sm placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-discord-blurple"
          />
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
