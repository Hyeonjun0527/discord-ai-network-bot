"use client";

import { useEffect, useState } from "react";
import StatsCard from "@/components/stats-card";
import { apiFetch } from "@/lib/api";
import { Activity, Terminal, Clock, AlertTriangle } from "lucide-react";

interface Stats {
  total: number;
  by_command: { command: string; count: number }[];
  avg_latency_ms: number;
  error_rate: number;
  daily: { day: string; count: number }[];
}

export default function DashboardOverviewPage() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const guildId =
      localStorage.getItem("selectedGuildId") ?? "";
    if (!guildId) {
      setLoading(false);
      return;
    }

    apiFetch<Stats>(`/api/guilds/${guildId}/stats`)
      .then((data) => {
        setStats(data);
        setLoading(false);
      })
      .catch((err: Error) => {
        setError(err.message);
        setLoading(false);
      });
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-48 text-gray-500">
        Loading statistics&hellip;
      </div>
    );
  }

  if (error) {
    return (
      <div className="bg-discord-red/10 border border-discord-red/30 rounded-xl p-4 text-discord-red text-sm">
        Failed to load stats: {error}
      </div>
    );
  }

  if (!stats) {
    return (
      <div className="text-gray-500 text-center py-16">
        Select a server from the sidebar to get started.
      </div>
    );
  }

  const topCommands = stats.by_command.slice(0, 5);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-white">Overview</h1>

      {/* Summary cards */}
      <div className="grid grid-cols-2 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatsCard
          title="Total Commands"
          value={stats.total.toLocaleString()}
          icon={<Activity className="w-5 h-5" />}
          color="blurple"
        />
        <StatsCard
          title="Avg Latency"
          value={`${stats.avg_latency_ms} ms`}
          icon={<Clock className="w-5 h-5" />}
          color="green"
        />
        <StatsCard
          title="Error Rate"
          value={`${stats.error_rate}%`}
          icon={<AlertTriangle className="w-5 h-5" />}
          color={stats.error_rate > 5 ? "red" : "green"}
        />
        <StatsCard
          title="Commands Used"
          value={topCommands[0]?.command ?? "—"}
          icon={<Terminal className="w-5 h-5" />}
          color="yellow"
          subtitle="most popular"
        />
      </div>

      {/* Top commands table */}
      <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5">
        <h2 className="text-white font-semibold mb-4">Commands Breakdown</h2>
        {topCommands.length === 0 ? (
          <p className="text-gray-500 text-sm">No usage data yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-gray-400 border-b border-white/10">
                  <th className="text-left py-2 pr-4 font-medium">Command</th>
                  <th className="text-right py-2 font-medium">Uses</th>
                  <th className="text-right py-2 font-medium">Share</th>
                </tr>
              </thead>
              <tbody>
                {topCommands.map(({ command, count }) => (
                  <tr
                    key={command}
                    className="border-b border-white/5 hover:bg-white/5 transition-colors"
                  >
                    <td className="py-2 pr-4 text-white font-mono">
                      /{command}
                    </td>
                    <td className="py-2 text-right text-gray-300">
                      {count.toLocaleString()}
                    </td>
                    <td className="py-2 text-right text-gray-400">
                      {stats.total > 0
                        ? `${((count / stats.total) * 100).toFixed(1)}%`
                        : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
