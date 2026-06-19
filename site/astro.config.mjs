import sitemap from "@astrojs/sitemap";
import { defineConfig } from "astro/config";

export default defineConfig({
  site: "https://discord-ai.yeon.world",
  integrations: [sitemap()],
  vite: {
    server: {
      fs: {
        allow: [".."],
      },
    },
  },
});
