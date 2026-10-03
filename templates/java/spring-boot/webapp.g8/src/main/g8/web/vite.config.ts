import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development /api is proxied to the JVM on :8080 (`jk dev` in app/ starts both). `jk build` runs
// the build script and packages dist/ as the web jar; the app serves it from classpath:static.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": "http://localhost:8080" },
  },
});
