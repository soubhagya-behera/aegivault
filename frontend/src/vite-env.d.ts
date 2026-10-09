/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL the client uses for API calls. Defaults to "/api" when unset. */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
