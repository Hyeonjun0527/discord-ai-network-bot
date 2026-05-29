"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  LayoutDashboard,
  Settings,
  BarChart3,
  Key,
  Bot,
  LogOut,
  Menu,
  X,
  ChevronDown,
} from "lucide-react";
import { clearToken } from "@/lib/auth";

interface Guild {
  id: string;
  name: string;
  icon: string | null;
}

interface SidebarProps {
  guilds: Guild[];
  selectedGuildId: string;
  onGuildChange: (id: string) => void;
}

const NAV_ITEMS = [
  { href: "/dashboard", label: "Overview", icon: LayoutDashboard },
  { href: "/dashboard/settings", label: "Settings", icon: Settings },
  { href: "/dashboard/api-keys", label: "API Keys", icon: Key },
  { href: "/dashboard/stats", label: "Statistics", icon: BarChart3 },
  { href: "/dashboard/models", label: "Models", icon: Bot },
];

function GuildIcon({ guild }: { guild: Guild }) {
  if (guild.icon) {
    return (
      <img
        src={`https://cdn.discordapp.com/icons/${guild.id}/${guild.icon}.webp?size=32`}
        alt={guild.name}
        className="w-7 h-7 rounded-full object-cover flex-shrink-0"
      />
    );
  }
  return (
    <div className="w-7 h-7 rounded-full bg-discord-blurple flex items-center justify-center text-white text-xs font-bold flex-shrink-0">
      {guild.name.charAt(0).toUpperCase()}
    </div>
  );
}

export default function Sidebar({ guilds, selectedGuildId, onGuildChange }: SidebarProps) {
  const pathname = usePathname();
  const router = useRouter();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [guildDropdownOpen, setGuildDropdownOpen] = useState(false);

  const selectedGuild = guilds.find((g) => g.id === selectedGuildId);

  const handleLogout = () => {
    clearToken();
    router.push("/");
  };

  const SidebarContent = () => (
    <div className="flex flex-col h-full">
      {/* Logo */}
      <div className="px-4 py-5 border-b border-white/5">
        <Link
          href="/dashboard"
          className="flex items-center gap-2.5 text-white font-bold text-base"
          onClick={() => setMobileOpen(false)}
        >
          <div className="w-8 h-8 bg-discord-blurple rounded-lg flex items-center justify-center">
            <Bot className="w-5 h-5" />
          </div>
          AI Assistant
        </Link>
      </div>

      {/* Guild selector */}
      {guilds.length > 0 && (
        <div className="px-3 py-3 border-b border-white/5">
          <button
            onClick={() => setGuildDropdownOpen(!guildDropdownOpen)}
            className="w-full flex items-center gap-2 text-sm text-gray-300 hover:text-white hover:bg-white/5 px-2 py-2 rounded-lg transition-colors"
          >
            {selectedGuild && <GuildIcon guild={selectedGuild} />}
            <span className="flex-1 text-left truncate text-sm">
              {selectedGuild?.name ?? "Select server"}
            </span>
            <ChevronDown
              className={`w-4 h-4 flex-shrink-0 transition-transform ${guildDropdownOpen ? "rotate-180" : ""}`}
            />
          </button>

          {guildDropdownOpen && (
            <div className="mt-1 bg-discord-dark rounded-lg border border-white/10 overflow-hidden">
              {guilds.map((guild) => (
                <button
                  key={guild.id}
                  onClick={() => {
                    onGuildChange(guild.id);
                    setGuildDropdownOpen(false);
                  }}
                  className={`w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-white/5 transition-colors ${
                    guild.id === selectedGuildId
                      ? "text-discord-blurple"
                      : "text-gray-300"
                  }`}
                >
                  <GuildIcon guild={guild} />
                  <span className="truncate">{guild.name}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Navigation */}
      <nav className="flex-1 px-3 py-3 space-y-0.5">
        {NAV_ITEMS.map(({ href, label, icon: Icon }) => {
          const isActive =
            href === "/dashboard"
              ? pathname === "/dashboard"
              : pathname.startsWith(href);
          return (
            <Link
              key={href}
              href={href}
              onClick={() => setMobileOpen(false)}
              className={`flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                isActive
                  ? "bg-discord-blurple/20 text-discord-blurple"
                  : "text-gray-400 hover:text-white hover:bg-white/5"
              }`}
            >
              <Icon className="w-4 h-4 flex-shrink-0" />
              {label}
            </Link>
          );
        })}
      </nav>

      {/* Logout */}
      <div className="px-3 py-4 border-t border-white/5">
        <button
          onClick={handleLogout}
          className="w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm text-gray-400 hover:text-discord-red hover:bg-discord-red/10 transition-colors"
        >
          <LogOut className="w-4 h-4" />
          Log out
        </button>
      </div>
    </div>
  );

  return (
    <>
      {/* Mobile top bar */}
      <div className="md:hidden fixed top-0 left-0 right-0 z-50 bg-discord-darker border-b border-white/5 px-4 py-3 flex items-center justify-between">
        <Link href="/dashboard" className="flex items-center gap-2 text-white font-bold text-sm">
          <Bot className="w-5 h-5 text-discord-blurple" />
          AI Assistant
        </Link>
        <button
          onClick={() => setMobileOpen(!mobileOpen)}
          className="text-gray-400 hover:text-white"
          aria-label="Toggle menu"
        >
          {mobileOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
        </button>
      </div>

      {/* Mobile sidebar overlay */}
      {mobileOpen && (
        <div
          className="md:hidden fixed inset-0 z-40 bg-black/60"
          onClick={() => setMobileOpen(false)}
        />
      )}

      {/* Mobile sidebar drawer */}
      <div
        className={`md:hidden fixed top-0 left-0 z-50 h-full w-64 bg-discord-darker transform transition-transform ${
          mobileOpen ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        <div className="pt-14 h-full">
          <SidebarContent />
        </div>
      </div>

      {/* Desktop sidebar */}
      <aside className="hidden md:flex flex-col fixed left-0 top-0 h-full w-64 bg-discord-darker border-r border-white/5 z-30">
        <SidebarContent />
      </aside>
    </>
  );
}
