"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Sidebar from "@/components/sidebar";
import { clearLoginHint, markLoggedIn } from "@/lib/auth";
import { apiFetch } from "@/lib/api";
import { Loader2 } from "lucide-react";

interface Guild {
  id: string;
  name: string;
  icon: string | null;
}

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const [loading, setLoading] = useState(true);
  const [guilds, setGuilds] = useState<Guild[]>([]);
  const [selectedGuildId, setSelectedGuildId] = useState<string>("");

  useEffect(() => {
    // #34: 토큰은 httpOnly 쿠키라 JS 로 못 읽으므로, /auth/me 호출 자체가 인증
    // 판정이 된다. 쿠키로 인증되면 200 + guilds, 무효면 401 → catch 에서 로그인으로.
    apiFetch<{ sub: string; guilds: Guild[] }>("/auth/me")
      .then((data) => {
        // 인증 확인됨 → 라우팅 힌트를 켜둔다(새로고침/직접 진입 대비).
        markLoggedIn();
        setGuilds(data.guilds ?? []);
        if (data.guilds && data.guilds.length > 0) {
          const stored = localStorage.getItem("selectedGuildId");
          const valid = data.guilds.find((g) => g.id === stored);
          setSelectedGuildId(valid ? stored! : data.guilds[0].id);
        }
        setLoading(false);
      })
      .catch(() => {
        // 쿠키가 만료/무효 — 로컬 힌트 정리 후 로그인 페이지로.
        clearLoginHint();
        router.replace("/");
      });
  }, [router]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-discord-darkest">
        <Loader2 className="w-8 h-8 animate-spin text-discord-blurple" />
      </div>
    );
  }

  const handleGuildChange = (id: string) => {
    setSelectedGuildId(id);
    localStorage.setItem("selectedGuildId", id);
  };

  return (
    <div className="flex min-h-screen bg-discord-darkest">
      <Sidebar
        guilds={guilds}
        selectedGuildId={selectedGuildId}
        onGuildChange={handleGuildChange}
      />

      {/* Main content area */}
      <main className="flex-1 ml-0 md:ml-64 min-h-screen">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 py-6 sm:py-8">
          {guilds.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-64 text-gray-500">
              <p className="text-lg">No servers found.</p>
              <p className="text-sm mt-1">
                Make sure the bot is added to your server.
              </p>
            </div>
          ) : (
            // Pass selectedGuildId via a data attribute on the wrapper so child
            // pages can read it via context if needed. For simplicity, child pages
            // read localStorage directly.
            <div data-guild-id={selectedGuildId}>{children}</div>
          )}
        </div>
      </main>
    </div>
  );
}
