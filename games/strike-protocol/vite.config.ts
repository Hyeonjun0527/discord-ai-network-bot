import { tanstackStart } from "@tanstack/react-start/plugin/vite";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";

export default defineConfig(({ command }) => ({
  server: {
    // Allow the Vite dev server to be reached through the Discord proxy and through
    // dev tunnels (cloudflared/ngrok) used to expose localhost as HTTPS for testing
    // a Discord Activity. Discord proxies every request via <CLIENT_ID>.discordsays.com.
    // Normal local dev on 127.0.0.1 is unaffected.
    allowedHosts: [".discordsays.com", ".trycloudflare.com", ".ngrok-free.app", ".ngrok.io"],
    // When serving the dev server THROUGH a tunnel/the Discord proxy (HTTPS on 443),
    // set DISCORD_DEV=1 so HMR uses the secure websocket port. Off for normal local
    // dev so HMR stays on 127.0.0.1:5173.
    ...(process.env.DISCORD_DEV === "1" ? { hmr: { clientPort: 443 } } : {}),
  },
  // The server bundle runs as a Cloudflare Worker — there is no node_modules
  // at runtime. Vite's default SSR build leaves npm deps as bare external
  // imports (h3, react, @tanstack/*, seroval, …), which resolve on a Node
  // server but throw "No such module" in a Worker. Bundle them all in.
  // (node: builtins stay external — nodejs_compat provides them.)
  //
  // Apply this only for `vite build` (the Worker bundle). During `vite dev`
  // the SSR module runner inlines deps as ESM; CJS packages like React's
  // index.js use `module.exports`, which throws "module is not defined" when
  // forcibly inlined — so in dev we let Node resolve them externally.
  ssr: {
    noExternal: command === "build" ? true : undefined,
  },
  plugins: [
    // TanStack Start plugin must run before React's plugin.
    //
    // SSR build: `vite build` emits a Workers-shaped server bundle
    // (dist/server/server.js — `export default { fetch }`) plus dist/client
    // (hashed static assets). The platform publishes that as a per-tenant
    // Worker on Workers for Platforms, served at <sub>.higgsfield.app/ (host
    // root, so Vite's default base "/" — no base-path juggling).
    //
    // Rendering happens on the server per request, so site code must be
    // SSR-safe: never touch browser-only globals (window, document,
    // localStorage, navigator) during render or at module top level — only
    // inside effects/handlers, or guarded with `typeof window !== "undefined"`.
    tanstackStart({
      server: { entry: "server" },
    }),
    react(),
    tailwindcss(),
    tsconfigPaths(),
  ],
}));
