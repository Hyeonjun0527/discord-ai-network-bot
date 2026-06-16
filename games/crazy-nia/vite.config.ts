import { defineConfig } from 'vite';

// Base is relative so the production bundle works when served from any subpath
// (e.g. the Discord Activity embed under https://<CLIENT_ID>.discordsays.com/ or any
// static host).
//
// Set DISCORD_DEV=1 when serving the dev server THROUGH a tunnel/the Discord proxy
// (HTTPS on 443) so HMR uses the secure websocket port. Left off for normal local
// dev so HMR works on 127.0.0.1:5173 (and the headless smoke tests stay error-free).
const discordDev = process.env.DISCORD_DEV === '1';

export default defineConfig({
  base: './',
  server: {
    port: 5173,
    host: '127.0.0.1',
    // Allow the Vite dev server to be reached through the Discord proxy and through
    // dev tunnels (cloudflared/ngrok) used to expose localhost as HTTPS for testing
    // an Activity. Discord proxies every request via <CLIENT_ID>.discordsays.com.
    allowedHosts: ['.discordsays.com', '.trycloudflare.com', '.ngrok-free.app', '.ngrok.io'],
    ...(discordDev ? { hmr: { clientPort: 443 } } : {}),
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
  },
});
