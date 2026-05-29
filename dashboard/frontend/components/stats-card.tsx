import { ReactNode } from "react";

type CardColor = "blurple" | "green" | "red" | "yellow" | "gray";

interface StatsCardProps {
  title: string;
  value: string | number;
  icon: ReactNode;
  color?: CardColor;
  subtitle?: string;
}

const colorMap: Record<CardColor, { bg: string; text: string; iconBg: string }> = {
  blurple: {
    bg: "bg-discord-blurple/10",
    text: "text-discord-blurple",
    iconBg: "bg-discord-blurple/20",
  },
  green: {
    bg: "bg-discord-green/10",
    text: "text-discord-green",
    iconBg: "bg-discord-green/20",
  },
  red: {
    bg: "bg-discord-red/10",
    text: "text-discord-red",
    iconBg: "bg-discord-red/20",
  },
  yellow: {
    bg: "bg-discord-yellow/10",
    text: "text-discord-yellow",
    iconBg: "bg-discord-yellow/20",
  },
  gray: {
    bg: "bg-white/5",
    text: "text-gray-300",
    iconBg: "bg-white/10",
  },
};

export default function StatsCard({
  title,
  value,
  icon,
  color = "blurple",
  subtitle,
}: StatsCardProps) {
  const { bg, text, iconBg } = colorMap[color];

  return (
    <div
      className={`${bg} rounded-xl p-4 sm:p-5 border border-white/5 flex flex-col gap-3`}
    >
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-gray-400 uppercase tracking-wide">
          {title}
        </span>
        <div className={`${iconBg} ${text} w-8 h-8 rounded-lg flex items-center justify-center`}>
          {icon}
        </div>
      </div>

      <div>
        <p className={`text-2xl font-bold ${text}`}>{value}</p>
        {subtitle && (
          <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>
        )}
      </div>
    </div>
  );
}
