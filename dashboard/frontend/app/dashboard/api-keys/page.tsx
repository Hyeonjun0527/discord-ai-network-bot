"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { Key, Trash2, Loader2, CheckCircle, AlertTriangle } from "lucide-react";

interface ApiKeyStatus {
  has_key: boolean;
}

export default function ApiKeysPage() {
  const [status, setStatus] = useState<ApiKeyStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [clearing, setClearing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const guildId = () => localStorage.getItem("selectedGuildId") ?? "";

  const fetchStatus = async () => {
    const id = guildId();
    if (!id) {
      setLoading(false);
      return;
    }
    try {
      const data = await apiFetch<ApiKeyStatus>(`/api/guilds/${id}/api-key`);
      setStatus(data);
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchStatus();
  }, []);

  const handleClear = async () => {
    setClearing(true);
    setError(null);
    setSuccess(null);
    const id = guildId();
    try {
      await apiFetch(`/api/guilds/${id}/api-key`, { method: "DELETE" });
      setStatus({ has_key: false });
      setSuccess("API key cleared successfully.");
      setTimeout(() => setSuccess(null), 3000);
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setClearing(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-48 text-gray-500">
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
        Loading&hellip;
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <h1 className="text-2xl font-bold text-white">API Key Management</h1>

      <div className="bg-discord-darker border border-white/5 rounded-xl p-4 sm:p-6 space-y-4">
        <div className="flex items-center gap-3">
          <div
            className={`w-10 h-10 rounded-full flex items-center justify-center ${
              status?.has_key
                ? "bg-discord-green/20 text-discord-green"
                : "bg-gray-700 text-gray-400"
            }`}
          >
            <Key className="w-5 h-5" />
          </div>
          <div>
            <p className="text-white font-medium text-sm">Stored API Key</p>
            <p className="text-xs text-gray-400 mt-0.5">
              {status?.has_key
                ? "sk-•••••••••••••••••••••••••••••••••••••"
                : "No key set"}
            </p>
          </div>
          <div className="ml-auto">
            <span
              className={`text-xs font-medium px-2 py-1 rounded-full ${
                status?.has_key
                  ? "bg-discord-green/20 text-discord-green"
                  : "bg-gray-700 text-gray-400"
              }`}
            >
              {status?.has_key ? "Active" : "Not set"}
            </span>
          </div>
        </div>

        <p className="text-xs text-gray-500 border-t border-white/5 pt-4">
          API keys are encrypted with AES-256-GCM before being stored in the
          database. The raw key is never logged or exposed via the dashboard.
          To set or update a key, use the{" "}
          <code className="text-discord-blurple">/settings</code> command in
          Discord.
        </p>
      </div>

      {error && (
        <div className="flex items-center gap-2 bg-discord-red/10 border border-discord-red/30 rounded-xl p-4 text-discord-red text-sm">
          <AlertTriangle className="w-4 h-4 flex-shrink-0" />
          {error}
        </div>
      )}
      {success && (
        <div className="flex items-center gap-2 bg-discord-green/10 border border-discord-green/30 rounded-xl p-4 text-discord-green text-sm">
          <CheckCircle className="w-4 h-4 flex-shrink-0" />
          {success}
        </div>
      )}

      {status?.has_key && (
        <div className="bg-discord-darker border border-discord-red/20 rounded-xl p-4 sm:p-6">
          <h2 className="text-white font-semibold mb-1 text-sm">
            Clear API Key
          </h2>
          <p className="text-gray-400 text-xs mb-4">
            Permanently remove the stored key. The bot will fall back to Ollama
            (local) mode after clearing.
          </p>
          <button
            onClick={handleClear}
            disabled={clearing}
            className="flex items-center gap-2 bg-discord-red/20 hover:bg-discord-red/30 border border-discord-red/30 disabled:opacity-50 disabled:cursor-not-allowed text-discord-red font-medium px-4 py-2 rounded-lg text-sm transition-colors"
          >
            {clearing ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Trash2 className="w-4 h-4" />
            )}
            {clearing ? "Clearing…" : "Clear API Key"}
          </button>
        </div>
      )}

      {/* Instructions panel */}
      <div className="bg-discord-darker border border-white/5 rounded-xl p-4 sm:p-6">
        <h2 className="text-white font-semibold mb-3 text-sm">
          How to set an API key
        </h2>
        <ol className="text-gray-400 text-sm space-y-2 list-decimal list-inside">
          <li>
            Open Discord and run{" "}
            <code className="text-discord-blurple">/settings</code> in your
            server.
          </li>
          <li>Select &ldquo;Model Management&rdquo; from the panel.</li>
          <li>Choose your provider (OpenAI or Anthropic).</li>
          <li>Click &ldquo;Set API Key&rdquo; and enter your key.</li>
        </ol>
      </div>
    </div>
  );
}
