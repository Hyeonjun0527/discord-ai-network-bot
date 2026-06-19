/// <reference types="astro/client" />

interface ImportMetaEnv {
  readonly PUBLIC_BUGSINK_DSN?: string;
  readonly PUBLIC_SENTRY_DSN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
