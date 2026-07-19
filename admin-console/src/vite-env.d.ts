/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_APP_BASE?: string;
  readonly VITE_APP_ENV?: string;
  readonly VITE_BUGSINK_DSN?: string;
  readonly VITE_SENTRY_DSN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
