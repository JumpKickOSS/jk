import { defineConfig } from "vite";

// One origin for the browser: Vite serves the page on 5173 and forwards /api to the JVM on 8080,
// so there is no CORS and no second port to remember. `jk dev` starts both and prints the 5173 URL.
export default defineConfig({
  server: {
    port: 5173,
    strictPort: true,
    proxy: { "/api": "http://localhost:8080" },
  },
});
