import react from "@vitejs/plugin-react";
import { defineConfig, type ProxyOptions } from "vite";

const devApiPort = process.env.SERVER_PORT?.trim() || "8080";
const devApiTarget = process.env.VITE_DEV_API_TARGET || `http://127.0.0.1:${devApiPort}`;
const localAdminToken = process.env.CENTRAL_DASHBOARD_ADMIN_TOKEN?.trim();

const apiProxy: ProxyOptions = {
  target: devApiTarget,
  configure(proxy) {
    if (!localAdminToken) return;
    proxy.on("proxyReq", (request) => {
      request.setHeader("X-Dashboard-Admin-Token", localAdminToken);
    });
  },
};

export default defineConfig({
  base: process.env.VITE_APP_BASE || "/admin/console/",
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      "/api": apiProxy,
      "/login": devApiTarget,
      "/oauth2": devApiTarget,
    },
  },
});
