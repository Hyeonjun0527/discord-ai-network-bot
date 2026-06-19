import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  base: process.env.VITE_APP_BASE || "/admin/dashboard/",
  plugins: [react()],
  server: {
    port: 5174,
  },
});
