import * as Sentry from "@sentry/react";

const dsn = import.meta.env.VITE_BUGSINK_DSN || import.meta.env.VITE_SENTRY_DSN;

if (import.meta.env.PROD && dsn) {
  Sentry.init({
    dsn,
    environment: import.meta.env.VITE_APP_ENV || "production",
    tracesSampleRate: 0,
    sendDefaultPii: false,
  });
}

export function captureConsoleError(error: unknown) {
  Sentry.captureException(error);
}
