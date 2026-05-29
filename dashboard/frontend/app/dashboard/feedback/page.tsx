"use client";

// #78: 피드백 열람 페이지.
// 백엔드 GET /api/guilds/{id}/feedback 의 집계(만족도/rating 분포/command별/최근 목록)를
// 차트와 표로 보여준다. stats 페이지와 동일한 디자인 토큰을 사용한다.

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
import { apiFetch, type GuildFeedback } from "@/lib/api";
import { Loader2, ThumbsUp, ThumbsDown } from "lucide-react";

const POSITIVE_COLOR = "#57F287"; // discord-green
const NEGATIVE_COLOR = "#ED4245"; // discord-red

function ratingLabel(rating: number): string {
  if (rating > 0) return "Positive";
  if (rating < 0) return "Negative";
  return "Neutral";
}

function ratingColor(rating: number): string {
  if (rating > 0) return POSITIVE_COLOR;
  if (rating < 0) return NEGATIVE_COLOR;
  return "#9ca3af";
}

export default function FeedbackPage() {
  const [data, setData] = useState<GuildFeedback | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const guildId = localStorage.getItem("selectedGuildId") ?? "";
    if (!guildId) {
      setLoading(false);
      return;
    }
    apiFetch<GuildFeedback>(`/api/guilds/${guildId}/feedback`)
      .then((d) => {
        setData(d);
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
        Loading feedback&hellip;
      </div>
    );
  }

  if (error) {
    return (
      <div className="bg-discord-red/10 border border-discord-red/30 rounded-xl p-4 text-discord-red text-sm">
        Failed to load feedback: {error}
      </div>
    );
  }

  if (!data) {
    return (
      <div className="text-gray-500 text-center py-16">
        Select a server from the sidebar.
      </div>
    );
  }

  if (data.total === 0) {
    return (
      <div className="space-y-6">
        <h1 className="text-2xl font-bold text-white">User Feedback</h1>
        <div className="bg-discord-darker rounded-xl p-8 border border-white/5 text-center text-gray-500 text-sm">
          No feedback collected yet. Reactions on the bot&apos;s replies will
          appear here.
        </div>
      </div>
    );
  }

  const pieData = [
    { name: "Positive", value: data.positive, color: POSITIVE_COLOR },
    { name: "Negative", value: data.negative, color: NEGATIVE_COLOR },
  ].filter((d) => d.value > 0);

  const commandData = data.by_command.map((c) => ({
    command: `/${c.command}`,
    positive: c.positive,
    negative: c.negative,
  }));

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-bold text-white">User Feedback</h1>

      {/* Summary numbers */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="bg-discord-darker rounded-xl p-4 border border-white/5">
          <p className="text-xs text-gray-400 uppercase tracking-wide">Total</p>
          <p className="text-2xl font-bold text-white mt-1">
            {data.total.toLocaleString()}
          </p>
        </div>
        <div className="bg-discord-green/10 rounded-xl p-4 border border-white/5">
          <p className="text-xs text-gray-400 uppercase tracking-wide flex items-center gap-1">
            <ThumbsUp className="w-3 h-3" /> Positive
          </p>
          <p className="text-2xl font-bold text-discord-green mt-1">
            {data.positive.toLocaleString()}
          </p>
        </div>
        <div className="bg-discord-red/10 rounded-xl p-4 border border-white/5">
          <p className="text-xs text-gray-400 uppercase tracking-wide flex items-center gap-1">
            <ThumbsDown className="w-3 h-3" /> Negative
          </p>
          <p className="text-2xl font-bold text-discord-red mt-1">
            {data.negative.toLocaleString()}
          </p>
        </div>
        <div className="bg-discord-blurple/10 rounded-xl p-4 border border-white/5">
          <p className="text-xs text-gray-400 uppercase tracking-wide">
            Satisfaction
          </p>
          <p className="text-2xl font-bold text-discord-blurple mt-1">
            {data.satisfaction === null ? "—" : `${data.satisfaction}%`}
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Rating distribution pie */}
        <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5">
          <h2 className="text-white font-semibold mb-4 text-sm">
            Rating Distribution
          </h2>
          {pieData.length === 0 ? (
            <p className="text-gray-500 text-sm">No rated feedback yet.</p>
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
                    {pieData.map((entry) => (
                      <Cell key={entry.name} fill={entry.color} />
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

        {/* Per-command stacked bar */}
        <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5">
          <h2 className="text-white font-semibold mb-4 text-sm">
            Feedback by Command
          </h2>
          {commandData.length === 0 ? (
            <p className="text-gray-500 text-sm">No data yet.</p>
          ) : (
            <div className="h-48 sm:h-56">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart
                  data={commandData}
                  margin={{ top: 4, right: 8, left: -20, bottom: 0 }}
                >
                  <CartesianGrid strokeDasharray="3 3" stroke="#2c2f33" />
                  <XAxis
                    dataKey="command"
                    tick={{ fill: "#9ca3af", fontSize: 11 }}
                    axisLine={false}
                    tickLine={false}
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
                  <Legend
                    iconType="circle"
                    iconSize={8}
                    wrapperStyle={{ fontSize: 11, color: "#9ca3af" }}
                  />
                  <Bar
                    dataKey="positive"
                    stackId="a"
                    fill={POSITIVE_COLOR}
                    radius={[0, 0, 0, 0]}
                    name="Positive"
                  />
                  <Bar
                    dataKey="negative"
                    stackId="a"
                    fill={NEGATIVE_COLOR}
                    radius={[4, 4, 0, 0]}
                    name="Negative"
                  />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </div>
      </div>

      {/* Recent feedback list */}
      <div className="bg-discord-darker rounded-xl p-4 sm:p-6 border border-white/5">
        <h2 className="text-white font-semibold mb-4 text-sm">
          Recent Feedback
        </h2>
        {data.recent.length === 0 ? (
          <p className="text-gray-500 text-sm">No recent feedback.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-gray-400 border-b border-white/10">
                  <th className="text-left py-2 pr-4 font-medium">Rating</th>
                  <th className="text-left py-2 pr-4 font-medium">Command</th>
                  <th className="text-left py-2 pr-4 font-medium">User</th>
                  <th className="text-right py-2 font-medium">When</th>
                </tr>
              </thead>
              <tbody>
                {data.recent.map((f) => (
                  <tr
                    key={`${f.message_id}-${f.user_id}`}
                    className="border-b border-white/5 hover:bg-white/5 transition-colors"
                  >
                    <td className="py-2.5 pr-4">
                      <span
                        className="text-xs font-medium px-2 py-0.5 rounded-full"
                        style={{
                          color: ratingColor(f.rating),
                          backgroundColor: `${ratingColor(f.rating)}22`,
                        }}
                      >
                        {ratingLabel(f.rating)}
                      </span>
                    </td>
                    <td className="py-2.5 pr-4 text-gray-300 font-mono text-xs">
                      {f.command ? `/${f.command}` : "—"}
                    </td>
                    <td className="py-2.5 pr-4 text-gray-400 font-mono text-xs">
                      {String(f.user_id)}
                    </td>
                    <td className="py-2.5 text-right text-gray-400 text-xs">
                      {new Date(f.created_at).toLocaleString(undefined, {
                        dateStyle: "medium",
                        timeStyle: "short",
                      })}
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
