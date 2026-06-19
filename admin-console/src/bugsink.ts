import * as Sentry from "@sentry/react";

const dsn = import.meta.env.VITE_BUGSINK_DSN || import.meta.env.VITE_SENTRY_DSN;
const environment = import.meta.env.VITE_APP_ENV || "production";

if (import.meta.env.PROD && dsn) {
  Sentry.init({
    dsn,
    environment,
    tracesSampleRate: 0,
    sendDefaultPii: false,
  });
  Sentry.setTag("app", "admin-console");
  Sentry.setTag("environment", environment);
}

export type BugsinkApiContext = {
  requestId?: string;
  method?: string;
  apiEndpoint?: string;
  httpStatus?: number;
  serverBaseUrl?: string;
};

type ReportedError = {
  bugsinkReported?: boolean;
};

export function wasBugsinkReported(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as ReportedError).bugsinkReported === true;
}

export function captureConsoleError(error: unknown, context?: BugsinkApiContext) {
  if (typeof error === "object" && error !== null) {
    (error as ReportedError).bugsinkReported = true;
  }

  Sentry.withScope((scope) => {
    scope.setTag("app", "admin-console");
    scope.setTag("environment", environment);
    if (context?.requestId) scope.setTag("requestId", context.requestId);
    if (context?.apiEndpoint) scope.setTag("apiEndpoint", context.apiEndpoint);
    if (context?.httpStatus !== undefined) scope.setTag("httpStatus", String(context.httpStatus));
    if (context) scope.setContext("api", context);
    Sentry.captureException(error);
  });
}
