"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Bot, BarChart3, Settings, Key, Shield } from "lucide-react";
import { redirectToDiscordLogin, getToken } from "@/lib/auth";

export default function LandingPage() {
  const router = useRouter();

  // If a token is already stored, go straight to the dashboard
  useEffect(() => {
    if (getToken()) {
      router.replace("/dashboard");
    }
  }, [router]);

  return (
    <main className="min-h-screen bg-discord-darkest flex flex-col">
      {/* Hero section */}
      <div className="flex flex-col items-center justify-center flex-1 px-4 py-16 sm:py-24">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-14 h-14 bg-discord-blurple rounded-2xl flex items-center justify-center shadow-lg">
            <Bot className="w-8 h-8 text-white" />
          </div>
          <h1 className="text-3xl sm:text-4xl font-bold text-white">
            AI Assistant Dashboard
          </h1>
        </div>

        <p className="text-gray-400 text-lg sm:text-xl text-center max-w-xl mb-10">
          Manage your Discord AI assistant — configure models, view usage stats,
          and control API keys from one place.
        </p>

        {/* Feature grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-12 w-full max-w-3xl">
          {[
            { icon: Settings, label: "Server Settings", desc: "Language, model, summary limit" },
            { icon: Key, label: "API Keys", desc: "Securely store provider keys" },
            { icon: BarChart3, label: "Usage Stats", desc: "Daily charts and command counts" },
            { icon: Shield, label: "Ollama Models", desc: "Browse and install models" },
          ].map(({ icon: Icon, label, desc }) => (
            <div
              key={label}
              className="bg-discord-darker rounded-xl p-4 flex flex-col items-start gap-2 border border-white/5"
            >
              <Icon className="w-6 h-6 text-discord-blurple" />
              <span className="font-semibold text-white text-sm">{label}</span>
              <span className="text-gray-400 text-xs">{desc}</span>
            </div>
          ))}
        </div>

        {/* Login button */}
        <button
          onClick={redirectToDiscordLogin}
          className="flex items-center gap-3 bg-discord-blurple hover:bg-[#4752c4] active:bg-[#3c45a5] text-white font-semibold px-8 py-4 rounded-xl text-lg transition-colors shadow-lg shadow-discord-blurple/20"
        >
          {/* Discord logo SVG */}
          <svg
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="currentColor"
            aria-hidden="true"
          >
            <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z" />
          </svg>
          Login with Discord
        </button>

        <p className="text-gray-500 text-sm mt-4">
          You must be a member of the server you want to manage.
        </p>
      </div>

      <footer className="text-center text-gray-600 text-xs py-4">
        Discord AI Assistant &mdash; open source &bull;{" "}
        <a
          href="https://github.com"
          className="hover:text-gray-400 underline"
          target="_blank"
          rel="noreferrer"
        >
          GitHub
        </a>
      </footer>
    </main>
  );
}
