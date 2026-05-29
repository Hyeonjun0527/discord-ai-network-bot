"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { Loader2, HardDrive, RefreshCw, Download } from "lucide-react";

interface OllamaModel {
  name: string;
  size: number;
  modified_at: string;
}

interface ModelsResponse {
  models: OllamaModel[];
  error?: string;
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return "—";
  const gb = bytes / 1024 ** 3;
  if (gb >= 1) return `${gb.toFixed(1)} GB`;
  const mb = bytes / 1024 ** 2;
  return `${mb.toFixed(0)} MB`;
}

const POPULAR_MODELS = [
  { name: "llama3.1:8b", desc: "Meta's Llama 3.1 — 8B params, good balance", size: "~4.9 GB" },
  { name: "qwen2.5:7b", desc: "Alibaba Qwen 2.5 — fast and multilingual", size: "~4.4 GB" },
  { name: "gemma2:9b", desc: "Google Gemma 2 — strong reasoning", size: "~5.4 GB" },
  { name: "mistral:7b", desc: "Mistral 7B — efficient and capable", size: "~4.1 GB" },
];

export default function ModelsPage() {
  const [data, setData] = useState<ModelsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const loadModels = async (showRefreshing = false) => {
    if (showRefreshing) setRefreshing(true);
    try {
      const result = await apiFetch<ModelsResponse>("/api/models");
      setData(result);
    } catch (err: unknown) {
      setData({ models: [], error: (err as Error).message });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    loadModels();
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-48 text-gray-500">
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
        Fetching Ollama models&hellip;
      </div>
    );
  }

  const installedNames = new Set((data?.models ?? []).map((m) => m.name));

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-white">Ollama Models</h1>
        <button
          onClick={() => loadModels(true)}
          disabled={refreshing}
          className="flex items-center gap-1.5 text-sm text-gray-400 hover:text-white transition-colors disabled:opacity-50"
        >
          <RefreshCw className={`w-4 h-4 ${refreshing ? "animate-spin" : ""}`} />
          Refresh
        </button>
      </div>

      {data?.error && (
        <div className="bg-discord-red/10 border border-discord-red/30 rounded-xl p-4 text-discord-red text-sm">
          Cannot reach Ollama: {data.error}
        </div>
      )}

      {/* Installed models */}
      <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5">
        <h2 className="text-white font-semibold mb-4 text-sm flex items-center gap-2">
          <HardDrive className="w-4 h-4 text-discord-blurple" />
          Installed Models ({data?.models.length ?? 0})
        </h2>
        {(!data?.models || data.models.length === 0) ? (
          <p className="text-gray-500 text-sm">
            No models installed yet, or Ollama is not running.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-gray-400 border-b border-white/10">
                  <th className="text-left py-2 pr-4 font-medium">Name</th>
                  <th className="text-right py-2 pr-4 font-medium">Size</th>
                  <th className="text-right py-2 font-medium">Modified</th>
                </tr>
              </thead>
              <tbody>
                {data.models.map((model) => (
                  <tr
                    key={model.name}
                    className="border-b border-white/5 hover:bg-white/5 transition-colors"
                  >
                    <td className="py-2.5 pr-4 text-white font-mono text-xs sm:text-sm">
                      {model.name}
                    </td>
                    <td className="py-2.5 pr-4 text-right text-gray-300 text-xs sm:text-sm">
                      {formatBytes(model.size)}
                    </td>
                    <td className="py-2.5 text-right text-gray-400 text-xs">
                      {model.modified_at
                        ? new Date(model.modified_at).toLocaleDateString()
                        : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Popular models to install */}
      <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5">
        <h2 className="text-white font-semibold mb-1 text-sm flex items-center gap-2">
          <Download className="w-4 h-4 text-discord-blurple" />
          Popular Models
        </h2>
        <p className="text-xs text-gray-500 mb-4">
          Run{" "}
          <code className="text-discord-blurple">ollama pull &lt;name&gt;</code>{" "}
          on your server to install a model.
        </p>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          {POPULAR_MODELS.map((m) => (
            <div
              key={m.name}
              className="flex items-start justify-between gap-3 bg-discord-dark rounded-lg p-3 border border-white/5"
            >
              <div className="flex-1 min-w-0">
                <p className="text-white font-mono text-xs font-semibold truncate">
                  {m.name}
                </p>
                <p className="text-gray-400 text-xs mt-0.5">{m.desc}</p>
                <p className="text-gray-500 text-xs mt-0.5">{m.size}</p>
              </div>
              {installedNames.has(m.name) ? (
                <span className="flex-shrink-0 text-xs bg-discord-green/20 text-discord-green px-2 py-0.5 rounded-full font-medium">
                  Installed
                </span>
              ) : (
                <code className="flex-shrink-0 text-xs bg-discord-dark border border-white/10 text-gray-300 px-2 py-0.5 rounded font-mono whitespace-nowrap">
                  pull
                </code>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
