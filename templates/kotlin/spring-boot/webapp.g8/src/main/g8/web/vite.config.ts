import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development /api is proxied to the JVM on :8080 (`jk dev` in app/ starts both). The production
// bundle is written into this module's jk resource root, so `jk build` packages it as the web jar and
// the app serves it from classpath:static — no copy step between the two builds.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": "http://localhost:8080" },
  },
  build: { outDir: "resources/static", emptyOutDir: true },
});
