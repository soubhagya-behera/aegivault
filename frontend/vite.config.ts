import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

/**
 * Vite configuration for the Aegivault frontend (also drives Vitest).
 *
 * Development proxy: forwards /api and /actuator to the configured backend
 * target so the browser talks to a same-origin dev server. This is a local
 * development convenience only — it is NOT a production CORS or reverse-proxy
 * solution. Production deployment topology is addressed separately.
 *
 * The backend target is read from the environment (AEGIVAULT_BACKEND_TARGET)
 * and defaults to http://localhost:8080. It stays server-side and is never
 * bundled into client code.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const backendTarget = env.AEGIVAULT_BACKEND_TARGET || "http://localhost:8080";

  const proxyOptions = {
    target: backendTarget,
    changeOrigin: true,
    // Preserve the original request path (/api/..., /actuator/...). No rewrite.
  };

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      proxy: {
        "/api": proxyOptions,
        "/actuator": proxyOptions,
      },
    },
    test: {
      environment: "jsdom",
      globals: false,
      setupFiles: ["./src/test/setup.ts"],
    },
  };
});
