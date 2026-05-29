"use client";

import { useEffect, useState } from "react";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  Legend,
} from "recharts";
import { apiFetch } from "@/lib/api";
import { Loader2 } from "lucide-react";

interface Stats {
  total: number;
  by_command: { command: string; count: number }[];
  avg_latency_ms: number;
  error_rate: number;
  daily: { day: string; count: number }[];
}

const COLORS = ["#5865F2", "#57F287", "#FEE75C", "#ED4245", "#EB459E", "#3BA55D"];

function shortDate(isoDate: string): string {
  // "2025-06-01" → "Jun 1"
  const d = new Date(isoDate + "T00:00:00");
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export default function StatsPage() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const guildId = localStorage.getItem("selectedGuildId") ?? "";
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
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
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
        Select a server from the sidebar.
      </div>
    );
  }

  const dailyData = stats.daily.map((d) => ({
    ...d,
    label: shortDate(d.day),
  }));

  const pieData = stats.by_command.slice(0, 6).map((c) => ({
    name: `/${c.command}`,
    value: c.count,
  }));

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-bold text-white">Usage Statistics</h1>

      {/* Daily usage bar chart */}
      <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5">
        <h2 className="text-white font-semibold mb-4 text-sm">
          Daily Command Usage (last 30 days)
        </h2>
        {dailyData.length === 0 ? (
          <p className="text-gray-500 text-sm">No data for the past 30 days.</p>
        ) : (
          <div className="h-56 sm:h-64">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={dailyData}
                margin={{ top: 4, right: 8, left: -20, bottom: 0 }}
              >
                <CartesianGrid strokeDasharray="3 3" stroke="#2c2f33" />
                <XAxis
                  dataKey="label"
                  tick={{ fill: "#9ca3af", fontSize: 11 }}
                  axisLine={false}
                  tickLine={false}
                  interval="preserveStartEnd"
                />
                <YAxis
                  tick={{ fill: "#9ca3af", fontSize: 11 }}
                  axisLine={false}
                  tickLine={false}
                  allowDecimals={false}
                />
                <Tooltip
                  contentStyle={{
                    backgroundColor: "#23272a",
                    border: "1px solid #2c2f33",
                    borderRadius: "8px",
                    color: "#dcddde",
                    fontSize: 12,
                  }}
                  cursor={{ fill: "rgba(88, 101, 242, 0.1)" }}
                />
                <Bar dataKey="count" fill="#5865F2" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>

      {/* Command breakdown pie chart */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5">
          <h2 className="text-white font-semibold mb-4 text-sm">
            Command Distribution
          </h2>
          {pieData.length === 0 ? (
            <p className="text-gray-500 text-sm">No data yet.</p>
          ) : (
            <div className="h-48 sm:h-56">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={pieData}
                    cx="50%"
                    cy="50%"
                    innerRadius={50}
                    outerRadius={80}
                    paddingAngle={3}
                    dataKey="value"
                  >
                    {pieData.map((_entry, index) => (
                      <Cell
                        key={`cell-${index}`}
                        fill={COLORS[index % COLORS.length]}
                      />
                    ))}
                  </Pie>
                  <Tooltip
                    contentStyle={{
                      backgroundColor: "#23272a",
                      border: "1px solid #2c2f33",
                      borderRadius: "8px",
                      color: "#dcddde",
                      fontSize: 12,
                    }}
                  />
                  <Legend
                    iconType="circle"
                    iconSize={8}
                    wrapperStyle={{ fontSize: 11, color: "#9ca3af" }}
                  />
                </PieChart>
              </ResponsiveContainer>
            </div>
          )}
        </div>

        {/* Summary numbers */}
        <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5 flex flex-col gap-4">
          <h2 className="text-white font-semibold text-sm">Summary</h2>
          {[
            { label: "Total commands", value: stats.total.toLocaleString() },
            { label: "Avg response time", value: `${stats.avg_latency_ms} ms` },
            { label: "Error rate", value: `${stats.error_rate}%` },
            {
              label: "Unique commands",
              value: stats.by_command.length.toString(),
            },
          ].map(({ label, value }) => (
            <div
              key={label}
              className="flex justify-between items-center text-sm border-b border-white/5 pb-2 last:border-0 last:pb-0"
            >
              <span className="text-gray-400">{label}</span>
              <span className="text-white font-semibold">{value}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
