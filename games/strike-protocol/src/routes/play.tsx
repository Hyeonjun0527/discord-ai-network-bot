import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";

import { bootstrapDiscord } from "../discord/bootstrap";

export const Route = createFileRoute("/play")({
  component: Play,
  validateSearch: (s: Record<string, unknown>): { room?: string } =>
    typeof s.room === "string" && s.room ? { room: s.room } : {},
});

function randomRoom() {
  const A = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  let r = "";
  for (let i = 0; i < 5; i++) r += A[Math.floor(Math.random() * A.length)];
  return r;
}

interface Settings {
  sens: number; fov: number; volume: number; xhairColor: string; xhairSize: number;
  gameMode: "elim" | "defuse"; botDiff: "easy" | "normal" | "hard"; roundTarget: number; fragLimit: number;
  map: "bazaar" | "compound";
}
const DEFAULTS: Settings = {
  sens: 1, fov: 78, volume: 0.7, xhairColor: "#9fe870", xhairSize: 1,
  gameMode: "defuse", botDiff: "normal", roundTarget: 8, fragLimit: 25,
  map: "bazaar",
};
interface Profile { kills: number; deaths: number; shots: number; hits: number; head: number; matches: number; wins: number; bestStreak: number }

/* shared visual atoms for the tactical menu */
const CLIP = "polygon(12px 0, 100% 0, 100% calc(100% - 12px), calc(100% - 12px) 100%, 0 100%, 0 12px)";

function Corner({ pos }: { pos: "tl" | "tr" | "bl" | "br" }) {
  const m: Record<string, string> = {
    tl: "left-0 top-0 border-l-2 border-t-2",
    tr: "right-0 top-0 border-r-2 border-t-2",
    bl: "bottom-0 left-0 border-b-2 border-l-2",
    br: "bottom-0 right-0 border-b-2 border-r-2",
  };
  return <span className={"pointer-events-none absolute h-4 w-4 border-[#9fe870]/60 " + m[pos]} />;
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="mb-2 flex items-center gap-2 text-[10px] font-bold tracking-[3px] text-[#9aa3ad]">
      <span className="inline-block h-[2px] w-4 bg-[#9fe870]" />
      {children}
    </div>
  );
}

function Play() {
  const { room: roomFromUrl } = Route.useSearch();
  const mountRef = useRef<HTMLDivElement>(null);
  const [started, setStarted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<"bots" | "mp">(roomFromUrl ? "mp" : "bots");
  const [name, setName] = useState("");
  const [room, setRoom] = useState(roomFromUrl ? roomFromUrl.toUpperCase() : "");
  const [copied, setCopied] = useState(false);
  const [tab, setTab] = useState<"deploy" | "settings" | "stats">("deploy");
  const [cfg, setCfg] = useState<Settings>(DEFAULTS);
  const [profile, setProfile] = useState<Profile | null>(null);

  useEffect(() => {
    try {
      const saved = localStorage.getItem("sp-name");
      setName(saved || "PLAYER-" + Math.floor(100 + Math.random() * 900));
      const rawCfg = localStorage.getItem("sp-settings");
      if (rawCfg) setCfg({ ...DEFAULTS, ...JSON.parse(rawCfg) });
      const rawProf = localStorage.getItem("sp-profile");
      if (rawProf) setProfile(JSON.parse(rawProf));
    } catch {
      setName("PLAYER-" + Math.floor(100 + Math.random() * 900));
    }
    if (!roomFromUrl) setRoom(randomRoom());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Discord Activity bootstrap. No-op outside Discord (no `frame_id`) — the standalone
  // browser build is untouched. Inside Discord it authenticates the embedded session
  // and prefills the callsign with the player's Discord display name.
  useEffect(() => {
    let cancelled = false;
    bootstrapDiscord()
      .then((session) => {
        if (cancelled || !session.inDiscord || !session.displayName) return;
        const callsign = session.displayName.toUpperCase().slice(0, 12);
        setName(callsign);
        try {
          localStorage.setItem("sp-name", callsign);
        } catch {
          /* private mode */
        }
      })
      .catch((err) => {
        // Surface but never block: outside Discord this never throws; inside Discord a
        // failed auth still lets the player use the menu (multiplayer just won't carry
        // their Discord identity).
        console.error("[strike-protocol] discord bootstrap failed", err);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const setCfgK = <K extends keyof Settings>(k: K, v: Settings[K]) => {
    setCfg((c) => {
      const next = { ...c, [k]: v };
      try { localStorage.setItem("sp-settings", JSON.stringify(next)); } catch { /* private */ }
      return next;
    });
  };

  const cleanRoom = room.replace(/[^a-zA-Z0-9]/g, "").toUpperCase().slice(0, 16);
  const shareUrl =
    typeof window !== "undefined" && cleanRoom
      ? window.location.origin + "/play?room=" + cleanRoom
      : "";

  useEffect(() => {
    if (!started || !mountRef.current) return;
    let cleanup: (() => void) | undefined;
    let cancelled = false;
    try { localStorage.setItem("sp-name", name); } catch { /* private mode */ }
    if (mode === "mp" && cleanRoom) {
      window.history.replaceState(null, "", "/play?room=" + cleanRoom);
    }
    import("../lib/game/engine")
      .then((mod) => {
        if (cancelled || !mountRef.current) return;
        cleanup = mod.initGame(mountRef.current, {
          sensitivity: cfg.sens, mode, name, room: cleanRoom,
          fov: cfg.fov, volume: cfg.volume, xhairColor: cfg.xhairColor, xhairSize: cfg.xhairSize,
          gameMode: cfg.gameMode, botDiff: cfg.botDiff,
          roundTarget: mode === "mp" ? cfg.fragLimit : cfg.roundTarget,
          map: cfg.map,
        });
      })
      .catch((e) => setError(String(e)));
    return () => {
      cancelled = true;
      if (cleanup) cleanup();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [started]);

  const copyLink = () => {
    if (!shareUrl) return;
    navigator.clipboard?.writeText(shareUrl).then(
      () => { setCopied(true); setTimeout(() => setCopied(false), 1500); },
      () => undefined,
    );
  };

  const modeBtn = (m: "bots" | "mp", title: string, sub: string) => (
    <button
      onClick={() => setMode(m)}
      className={
        "group relative flex-1 border p-4 text-left transition " +
        (mode === m
          ? "border-[#9fe870] bg-[#9fe870]/10 shadow-[0_0_24px_rgba(159,232,112,.15)]"
          : "border-white/15 bg-black/30 hover:border-white/40")
      }
      style={{ clipPath: CLIP }}
    >
      <div className={"text-base font-bold tracking-[2px] " + (mode === m ? "text-[#9fe870]" : "text-[#e8e3d6]")}>
        {mode === m ? "▸ " : ""}{title}
      </div>
      <div className="mt-1 text-[11px] leading-snug text-[#9aa3ad]">{sub}</div>
    </button>
  );

  const slider = (label: string, val: number, min: number, max: number, step: number, fmt: (v: number) => string, on: (v: number) => void) => (
    <div className="text-left text-sm text-[#9aa3ad]">
      <div className="mb-1 flex justify-between text-xs tracking-[1.5px]">
        <span>{label.toUpperCase()}</span>
        <span className="font-bold text-[#9fe870]">{fmt(val)}</span>
      </div>
      <input type="range" min={min} max={max} step={step} value={val}
        onChange={(e) => on(parseFloat(e.target.value))} className="w-full accent-[#9fe870]" />
    </div>
  );

  const acc = profile && profile.shots > 0 ? Math.round((profile.hits / profile.shots) * 100) : 0;
  const hsr = profile && profile.hits > 0 ? Math.round((profile.head / profile.hits) * 100) : 0;
  const kd = profile && profile.deaths > 0 ? (profile.kills / profile.deaths).toFixed(2) : profile ? String(profile.kills) : "0";

  const statCard = (label: string, val: string | number) => (
    <div className="relative border border-white/10 bg-black/40 p-3" style={{ clipPath: CLIP }}>
      <div className="text-[10px] tracking-[1.5px] text-[#9aa3ad]">{label}</div>
      <div className="text-2xl font-bold text-[#ffd76e]">{val}</div>
    </div>
  );

  const TABS = [
    ["deploy", "DEPLOY", "Mode, map, match setup"],
    ["settings", "SETTINGS", "Aim, video, audio"],
    ["stats", "CAREER", "Your service record"],
  ] as const;

  return (
    <div className="fixed inset-0 bg-[#0b0e11]" style={{ fontFamily: "'Rajdhani','Segoe UI',sans-serif" }}>
      <link href="https://fonts.googleapis.com/css2?family=Rajdhani:wght@500;600;700&&family=Press+Start+2P&&display=swap" rel="stylesheet" />
      <div ref={mountRef} className="absolute inset-0" />

      {/* MAIN MENU */}
      {!started && (
        <div className="absolute inset-0 z-10 overflow-y-auto text-[#e8e3d6]">
          <img src="/art/hero.jpg" alt="" className="fixed inset-0 h-full w-full object-cover" />
          <div className="fixed inset-0 bg-gradient-to-r from-[#0a0c0e]/95 via-[#0a0c0e]/80 to-[#0a0c0e]/40" />
          <div className="fixed inset-0 bg-gradient-to-t from-[#0a0c0e] via-transparent to-[#0a0c0e]/70" />
          <div
            className="pointer-events-none fixed inset-0 opacity-[0.07]"
            style={{ backgroundImage: "repeating-linear-gradient(0deg, transparent 0 2px, #9fe870 2px 3px)" }}
          />

          <div className="relative z-10 mx-auto flex min-h-full w-full max-w-6xl flex-col px-6 py-6 lg:px-10">
            {/* top bar */}
            <div className="flex items-center justify-between border-b border-white/10 pb-4">
              <div>
                <div className="text-2xl font-bold leading-none tracking-[4px]">
                  STRIKE<span className="text-[#ffd76e]">PROTOCOL</span>
                </div>
                <div className="mt-1 text-[10px] tracking-[4px] text-[#9fe870]">TACTICAL OPERATIONS TERMINAL</div>
              </div>
              <div className="hidden items-center gap-5 text-[10px] tracking-[2px] text-[#9aa3ad] sm:flex">
                <span className="flex items-center gap-1.5">
                  <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-[#9fe870]" /> SYSTEMS ONLINE
                </span>
                <Link to="/" className="border border-white/15 px-3 py-1.5 transition hover:border-white/50 hover:text-white">
                  ← BRIEFING
                </Link>
              </div>
            </div>

            {/* body: nav rail + panel */}
            <div className="mt-6 flex flex-1 flex-col gap-6 lg:flex-row">
              <div className="flex shrink-0 gap-2 lg:w-64 lg:flex-col">
                {TABS.map(([id, label, sub]) => (
                  <button
                    key={id}
                    onClick={() => setTab(id)}
                    className={
                      "relative flex-1 border-l-4 p-3 text-left transition lg:flex-none lg:p-4 " +
                      (tab === id
                        ? "border-l-[#9fe870] bg-[#9fe870]/10 text-[#9fe870]"
                        : "border-l-white/10 bg-black/30 text-[#9aa3ad] hover:border-l-white/40 hover:text-white")
                    }
                  >
                    <div className="text-sm font-bold tracking-[3px] lg:text-lg">{label}</div>
                    <div className="mt-0.5 hidden text-[10px] tracking-[1px] text-[#6b7280] lg:block">{sub}</div>
                  </button>
                ))}
                <div className="hidden border border-white/10 bg-black/30 p-4 lg:mt-auto lg:block" style={{ clipPath: CLIP }}>
                  <div className="text-[10px] tracking-[2px] text-[#9aa3ad]">OPERATOR</div>
                  <div className="mt-1 truncate text-lg font-bold tracking-[2px] text-[#9fe870]">{name || "—"}</div>
                  <div className="mt-1 text-[10px] tracking-[1px] text-[#6b7280]">
                    {profile ? profile.kills + " KILLS · " + profile.matches + " MATCHES" : "NO SERVICE RECORD"}
                  </div>
                </div>
              </div>

              {/* main panel */}
              <div className="relative flex-1 border border-white/10 bg-[#0c0f12]/80 p-6 shadow-[0_30px_80px_rgba(0,0,0,.7)] backdrop-blur-md lg:p-8">
                <Corner pos="tl" /><Corner pos="tr" /><Corner pos="bl" /><Corner pos="br" />

                {tab === "deploy" && (
                  <div className="space-y-5">
                    <div>
                      <SectionLabel>SELECT OPERATION</SectionLabel>
                      <div className="flex flex-col gap-3 sm:flex-row">
                        {modeBtn("bots", "VS BOTS", "4v4 team rounds — you + 3 AI vs 4 AI. Economy, bomb defuse.")}
                        {modeBtn("mp", "ONLINE", "Deathmatch with friends + AI bots. Warmup lobby, ready check.")}
                      </div>
                    </div>

                    {mode === "bots" && (
                      <div className="grid gap-5 sm:grid-cols-2">
                        <div>
                          <SectionLabel>MAP</SectionLabel>
                          <div className="flex gap-2">
                            {([["bazaar", "BAZAAR", "Open desert market — long sightlines, side yards."], ["compound", "COMPOUND", "Tight CQB — central building, twin corridors, north sites."]] as const).map(([id, label, sub]) => (
                              <button key={id} onClick={() => setCfgK("map", id)} title={sub}
                                className={"flex-1 border px-2 py-2.5 text-xs font-bold tracking-[1px] transition " + (cfg.map === id ? "border-[#9fe870] bg-[#9fe870]/10 text-[#9fe870]" : "border-white/15 bg-black/30 text-[#9aa3ad] hover:border-white/35")}
                                style={{ clipPath: CLIP }}>
                                {label}
                              </button>
                            ))}
                          </div>
                        </div>
                        <div>
                          <SectionLabel>GAME MODE</SectionLabel>
                          <div className="flex gap-2">
                            {([["defuse", "BOMB DEFUSE", "Plant or defend the C4 at sites A/B. Side switch at half."], ["elim", "ELIMINATION", "Classic round wins — last side standing."]] as const).map(([id, label, sub]) => (
                              <button key={id} onClick={() => setCfgK("gameMode", id)} title={sub}
                                className={"flex-1 border px-2 py-2.5 text-xs font-bold tracking-[1px] transition " + (cfg.gameMode === id ? "border-[#ffd76e] bg-[#ffd76e]/10 text-[#ffd76e]" : "border-white/15 bg-black/30 text-[#9aa3ad] hover:border-white/35")}
                                style={{ clipPath: CLIP }}>
                                {label}
                              </button>
                            ))}
                          </div>
                        </div>
                        <div>
                          <SectionLabel>BOT DIFFICULTY</SectionLabel>
                          <div className="flex gap-2">
                            {(["easy", "normal", "hard"] as const).map((d) => (
                              <button key={d} onClick={() => setCfgK("botDiff", d)}
                                className={"flex-1 border px-2 py-2.5 text-xs font-bold tracking-[1px] transition " + (cfg.botDiff === d ? "border-[#ff6b5a] bg-[#ff6b5a]/10 text-[#ff6b5a]" : "border-white/15 bg-black/30 text-[#9aa3ad] hover:border-white/35")}
                                style={{ clipPath: CLIP }}>
                                {d.toUpperCase()}
                              </button>
                            ))}
                          </div>
                        </div>
                        <div>
                          <SectionLabel>ROUNDS TO WIN — <span className="text-[#9fe870]">{cfg.roundTarget}</span></SectionLabel>
                          <input type="range" min={4} max={12} step={1} value={cfg.roundTarget}
                            onChange={(e) => setCfgK("roundTarget", parseInt(e.target.value))} className="mt-2 w-full accent-[#9fe870]" />
                        </div>
                      </div>
                    )}

                    {mode === "mp" && (
                      <div className="grid gap-5 sm:grid-cols-2">
                        <div>
                          <SectionLabel>CALLSIGN</SectionLabel>
                          <input value={name} maxLength={12}
                            onChange={(e) => setName(e.target.value.toUpperCase())}
                            className="w-full border border-white/15 bg-black/40 px-3 py-2.5 text-sm font-bold tracking-[2px] text-[#9fe870] outline-none focus:border-[#9fe870]" />
                        </div>
                        <div>
                          <div className="mb-2 flex items-center justify-between">
                            <SectionLabel>ROOM CODE</SectionLabel>
                            <button onClick={() => setRoom(randomRoom())} className="text-[10px] tracking-[1px] text-[#7fb3ff] hover:underline">NEW CODE</button>
                          </div>
                          <input value={room} maxLength={16}
                            onChange={(e) => setRoom(e.target.value.toUpperCase())}
                            className="w-full border border-white/15 bg-black/40 px-3 py-2 text-center text-lg font-bold tracking-[6px] text-[#ffd76e] outline-none focus:border-[#ffd76e]" />
                        </div>
                        <div>
                          <SectionLabel>FRAG LIMIT (HOST) — <span className="text-[#9fe870]">{cfg.fragLimit}</span></SectionLabel>
                          <input type="range" min={10} max={50} step={5} value={cfg.fragLimit}
                            onChange={(e) => setCfgK("fragLimit", parseInt(e.target.value))} className="mt-2 w-full accent-[#9fe870]" />
                        </div>
                        {shareUrl && (
                          <div>
                            <SectionLabel>INVITE LINK — SEND TO FRIENDS</SectionLabel>
                            <div className="flex gap-2">
                              <div className="min-w-0 flex-1 truncate border border-[#7fb3ff]/30 bg-[#7fb3ff]/5 px-3 py-2 text-xs leading-6 text-[#7fb3ff]">{shareUrl}</div>
                              <button onClick={copyLink}
                                className="shrink-0 border border-[#7fb3ff]/50 px-3 py-2 text-xs font-bold tracking-[1px] text-[#7fb3ff] transition hover:bg-[#7fb3ff] hover:text-[#0b0e11]">
                                {copied ? "COPIED ✓" : "COPY"}
                              </button>
                            </div>
                          </div>
                        )}
                      </div>
                    )}

                    <button onClick={() => setStarted(true)} disabled={mode === "mp" && !cleanRoom}
                      className="relative w-full border-2 border-[#9fe870] bg-[#9fe870]/10 py-4 text-2xl font-bold tracking-[6px] text-[#9fe870] transition hover:bg-[#9fe870] hover:text-[#0b0e11] hover:shadow-[0_0_40px_rgba(159,232,112,.35)] disabled:opacity-40"
                      style={{ clipPath: CLIP }}>
                      ▶ {mode === "mp" ? "JOIN ROOM " + (cleanRoom || "?") : "START MATCH"}
                    </button>
                    <div className="text-center text-[11px] leading-relaxed tracking-[1px] text-[#6b7280]">
                      WASD MOVE · SHIFT SPRINT · CTRL SLIDE · Z/X LEAN · LMB FIRE · RMB AIM · R RELOAD ·
                      {mode === "bots" ? " B BUY · E PLANT/DEFUSE · " : " ENTER READY · "}1-5 WEAPONS · G DROP · TAB SCOREBOARD
                    </div>
                  </div>
                )}

                {tab === "settings" && (
                  <div className="space-y-5">
                    <SectionLabel>COMBAT CALIBRATION</SectionLabel>
                    <div className="grid gap-x-10 gap-y-5 sm:grid-cols-2">
                      {slider("Mouse sensitivity", cfg.sens, 0.3, 2.5, 0.1, (v) => v.toFixed(1), (v) => setCfgK("sens", v))}
                      {slider("Field of view", cfg.fov, 60, 110, 1, (v) => String(Math.round(v)), (v) => setCfgK("fov", v))}
                      {slider("Volume", cfg.volume, 0, 1, 0.05, (v) => Math.round(v * 100) + "%", (v) => setCfgK("volume", v))}
                      {slider("Crosshair size", cfg.xhairSize, 0.5, 2, 0.1, (v) => v.toFixed(1) + "x", (v) => setCfgK("xhairSize", v))}
                    </div>
                    <div className="text-left text-sm text-[#9aa3ad]">
                      <div className="mb-2 text-xs tracking-[1.5px]">CROSSHAIR COLOR</div>
                      <div className="flex gap-3">
                        {["#9fe870", "#7fb3ff", "#ffd76e", "#ff6b5a", "#ffffff", "#ff7ad9"].map((c) => (
                          <button key={c} onClick={() => setCfgK("xhairColor", c)}
                            className={"h-9 w-9 rounded-full border-2 transition " + (cfg.xhairColor === c ? "scale-110 border-white shadow-[0_0_12px_rgba(255,255,255,.4)]" : "border-transparent hover:scale-105")}
                            style={{ background: c }} />
                        ))}
                      </div>
                    </div>
                    <div className="border-t border-white/10 pt-3 text-[11px] tracking-[1px] text-[#6b7280]">SAVED AUTOMATICALLY · APPLIES ON NEXT DEPLOY</div>
                  </div>
                )}

                {tab === "stats" && (
                  <div>
                    <SectionLabel>SERVICE RECORD</SectionLabel>
                    {profile ? (
                      <>
                        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                          {statCard("MATCHES", profile.matches)}
                          {statCard("WINS", profile.wins)}
                          {statCard("WIN %", profile.matches > 0 ? Math.round((profile.wins / profile.matches) * 100) + "%" : "—")}
                          {statCard("KILLS", profile.kills)}
                          {statCard("K/D", kd)}
                          {statCard("BEST STREAK", profile.bestStreak)}
                          {statCard("ACCURACY", acc + "%")}
                          {statCard("HEADSHOT %", hsr + "%")}
                          {statCard("SHOTS", profile.shots)}
                        </div>
                        <button onClick={() => { try { localStorage.removeItem("sp-profile"); } catch { /* */ } setProfile(null); }}
                          className="mt-5 border border-[#ff6b5a]/40 px-4 py-2 text-xs font-bold tracking-[2px] text-[#ff6b5a]/80 transition hover:bg-[#ff6b5a]/10 hover:text-[#ff6b5a]">
                          RESET STATS
                        </button>
                      </>
                    ) : (
                      <div className="py-16 text-center text-sm tracking-[1px] text-[#6b7280]">
                        NO MATCHES RECORDED YET.<br />FINISH A MATCH AND YOUR CAREER STATS APPEAR HERE.
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>

            {/* bottom strip */}
            <div className="mt-6 flex items-center justify-between border-t border-white/10 pt-3 text-[10px] tracking-[2px] text-[#6b7280]">
              <span>DESKTOP + MOUSE REQUIRED · CLICK ARENA TO LOCK CURSOR</span>
              <Link to="/" className="text-[#7fb3ff] hover:underline sm:hidden">← BRIEFING</Link>
              <span className="hidden sm:inline">BUILD 1.2 · THREE.JS / WEBRTC</span>
            </div>
          </div>
        </div>
      )}

      {error && (
        <div className="absolute bottom-4 left-1/2 z-20 -translate-x-1/2 border border-red-500/40 bg-red-950/80 px-4 py-2 text-sm text-red-200">
          Engine failed to start: {error}
        </div>
      )}
      {started && (
        <div className="absolute left-4 top-4 z-10 flex gap-2" style={{ marginTop: 190 }}>
          <Link to="/" className="border border-white/15 bg-black/50 px-3 py-1.5 text-xs tracking-[2px] text-[#9aa3ad] hover:text-white" style={{ pointerEvents: "auto" }}>
            ← EXIT
          </Link>
          {mode === "mp" && (
            <button onClick={copyLink}
              className="border border-[#7fb3ff]/40 bg-black/50 px-3 py-1.5 text-xs tracking-[2px] text-[#7fb3ff] hover:bg-[#7fb3ff] hover:text-black"
              style={{ pointerEvents: "auto" }}>
              {copied ? "LINK COPIED ✓" : "ROOM " + cleanRoom + " · COPY INVITE"}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
