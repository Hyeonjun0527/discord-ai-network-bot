import { createFileRoute, Link } from "@tanstack/react-router";

export const Route = createFileRoute("/")({
  component: Home,
});

const FEATURES = [
  { n: "01", t: "ONLINE MULTIPLAYER", d: "Real online deathmatch over WebRTC. Create a room, send the invite link — friends join the same arena from their own devices. Kills, tracers and the scoreboard sync live. No accounts, no game server." },
  { n: "02", t: "ROUND ECONOMY", d: "Start with $800. Earn $300 per kill, $1900 round win, $1100 loss bonus. Spend it in the buy phase on rifles, SMGs and kevlar." },
  { n: "03", t: "TACTICAL BOTS", d: "Enemy squad with waypoint pathfinding, line-of-sight checks, burst fire, combat strafing and aim error that tightens the longer they see you." },
  { n: "04", t: "GUNPLAY THAT KICKS", d: "ADS aiming, sniper scope, sprint and slide, recoil climb, movement spread, headshot multipliers, tracers, shell ejection and hit markers — all simulated." },
  { n: "05", t: "DUST-STYLE ARENA", d: "Two-lane desert map with mid doors, A and B sites, sandbag nests, oil barrels, palm trees, market awnings and a distant city skyline." },
  { n: "06", t: "FULL COMBAT HUD", d: "Rotating radar minimap with wall layout and enemy blips, damage direction indicator, low-HP pulse, kill feed, dynamic crosshair and scoreboard." },
];

const CONTROLS: [string, string][] = [
  ["W A S D", "Move"], ["MOUSE", "Aim"], ["LMB", "Fire / throw grenade"], ["RMB", "Aim down sights / scope"],
  ["SHIFT", "Sprint"], ["CTRL while sprinting", "Slide"], ["R", "Reload"], ["B", "Buy menu (buy phase)"],
  ["1 / 2 / 3", "Cycle primary / Pistol / Knife"], ["4 / 5", "Frag / Smoke grenade"], ["Q", "Last weapon"], ["G", "Drop weapon"],
  ["F", "Inspect weapon"], ["SPACE", "Jump"], ["CTRL / C", "Crouch (better accuracy)"], ["TAB", "Scoreboard"],
];

function Home() {
  return (
    <div className="min-h-screen bg-[#0a0c0e] text-[#e8e3d6]" style={{ fontFamily: "'Rajdhani','Segoe UI',sans-serif" }}>
      <link href="https://fonts.googleapis.com/css2?family=Rajdhani:wght@500;600;700&family=Press+Start+2P&display=swap" rel="stylesheet" />

      {/* HERO */}
      <div className="relative min-h-[92vh] overflow-hidden">
        <img src="/art/hero.jpg" alt="" className="absolute inset-0 h-full w-full object-cover" />
        <div className="absolute inset-0 bg-gradient-to-t from-[#0a0c0e] via-[#0a0c0e]/35 to-[#0a0c0e]/55" />
        <div className="absolute inset-0 bg-gradient-to-r from-[#0a0c0e]/70 via-transparent to-[#0a0c0e]/40" />
        <div className="relative z-10 mx-auto flex min-h-[92vh] max-w-6xl flex-col justify-center px-6 py-20">
          <div className="flex items-center gap-3 text-xs tracking-[5px] text-[#9fe870]">
            <span className="inline-block h-px w-10 bg-[#9fe870]" />
            TACTICAL BROWSER FPS — THREE.JS
          </div>
          <h1 className="mt-5 text-7xl font-bold leading-[0.95] tracking-[3px] md:text-8xl">
            STRIKE<br /><span className="text-[#ffd76e]">PROTOCOL</span>
          </h1>
          <p className="mt-6 max-w-xl text-lg leading-relaxed text-[#cfc9bb] [text-shadow:0_1px_8px_rgba(0,0,0,.8)]">
            Round-based combat against tactical bots, or online deathmatch with friends
            via a single invite link. Buy phase, economy, recoil, headshots, radar —
            running entirely in your browser. No downloads.
          </p>
          <div className="mt-10 flex flex-wrap items-center gap-4">
            <Link
              to="/play"
              className="group relative inline-block border-2 border-[#9fe870] bg-[#0a0c0e]/60 px-14 py-4 text-2xl font-bold tracking-[4px] text-[#9fe870] backdrop-blur-sm transition hover:bg-[#9fe870] hover:text-[#0a0c0e]"
            >
              ▶ DEPLOY
            </Link>
            <Link
              to="/play"
              search={{ room: undefined }}
              className="inline-block border border-white/25 bg-black/40 px-8 py-4 text-sm font-bold tracking-[3px] text-[#cfc9bb] backdrop-blur-sm transition hover:border-white/60 hover:text-white"
            >
              PLAY WITH FRIENDS →
            </Link>
          </div>
          <div className="mt-5 text-xs tracking-[1px] text-[#8a8576]">Desktop + mouse required · click the arena to lock your cursor</div>
        </div>
        <div className="absolute bottom-0 left-0 right-0 z-10 h-px bg-gradient-to-r from-transparent via-[#9fe870]/50 to-transparent" />
      </div>

      {/* FEATURES */}
      <div className="mx-auto max-w-6xl px-6 py-20">
        <div className="mb-10 flex items-center gap-3 text-xs tracking-[5px] text-[#9aa3ad]">
          <span className="inline-block h-px w-10 bg-[#9aa3ad]" /> SYSTEMS
        </div>
        <div className="grid gap-px overflow-hidden border border-white/10 bg-white/10 md:grid-cols-3" style={{ gap: 1 }}>
          {FEATURES.map((f) => (
            <div key={f.t} className="group bg-[#0d1013] p-7 transition hover:bg-[#11161a]">
              <div className="text-xs font-bold tracking-[2px] text-[#9fe870]/60">{f.n}</div>
              <div className="mt-2 text-lg font-bold tracking-[2px] text-[#ffd76e]">{f.t}</div>
              <p className="mt-3 text-sm leading-relaxed text-[#9aa3ad]">{f.d}</p>
            </div>
          ))}
        </div>
      </div>

      {/* ARMORY STRIP */}
      <div className="relative overflow-hidden border-y border-white/10">
        <img src="/art/armory.jpg" alt="" className="absolute inset-0 h-full w-full object-cover" />
        <div className="absolute inset-0 bg-gradient-to-r from-[#0a0c0e]/30 via-transparent to-[#0a0c0e]/85" />
        <div className="relative z-10 mx-auto flex max-w-6xl items-center justify-end px-6 py-24">
          <div className="max-w-md text-right">
            <div className="text-xs tracking-[5px] text-[#9fe870]">THE ARMORY</div>
            <h2 className="mt-3 text-4xl font-bold tracking-[2px]">FIVE WEAPONS.<br />ONE ECONOMY.</h2>
            <p className="mt-4 text-sm leading-relaxed text-[#cfc9bb] [text-shadow:0_1px_6px_rgba(0,0,0,.9)]">
              AK-103 — $2700, full-auto, hits like a truck. SR-90 — $4000, one shot through the scope.
              MP-9 — $1500, run-and-gun spray. P-57 sidearm — free, surgical. Tac knife — silent
              and fast. Kevlar — $650, soaks half the damage. Every dollar earned on the field.
            </p>
            <Link to="/play" className="mt-6 inline-block border border-[#ffd76e]/60 px-8 py-3 text-sm font-bold tracking-[3px] text-[#ffd76e] transition hover:bg-[#ffd76e] hover:text-[#0a0c0e]">
              ENTER ARMORY →
            </Link>
          </div>
        </div>
      </div>

      {/* CONTROLS */}
      <div className="mx-auto max-w-6xl px-6 py-20">
        <div className="mb-10 flex items-center gap-3 text-xs tracking-[5px] text-[#9aa3ad]">
          <span className="inline-block h-px w-10 bg-[#9aa3ad]" /> FIELD MANUAL
        </div>
        <div className="grid gap-x-12 gap-y-1 sm:grid-cols-2">
          {CONTROLS.map(([k, v]) => (
            <div key={k} className="flex items-center justify-between border-b border-white/8 py-2.5 text-sm">
              <span className="border border-white/15 bg-white/5 px-2.5 py-0.5 font-bold tracking-[1px] text-[#e8e3d6]">{k}</span>
              <span className="text-[#9aa3ad]">{v}</span>
            </div>
          ))}
        </div>
        <div className="mt-14 border-t border-white/10 pt-8 text-center">
          <Link
            to="/play"
            className="inline-block border-2 border-[#9fe870] px-14 py-4 text-xl font-bold tracking-[4px] text-[#9fe870] transition hover:bg-[#9fe870] hover:text-[#0a0c0e]"
          >
            ▶ START MATCH
          </Link>
          <div className="mt-6 text-xs text-[#4b5563]">
            Eliminate the enemy squad or survive the clock. First to 8 rounds wins. In deathmatch — most frags on the board.
          </div>
        </div>
      </div>
    </div>
  );
}
