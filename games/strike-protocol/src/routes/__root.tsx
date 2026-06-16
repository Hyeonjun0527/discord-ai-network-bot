import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  Outlet,
  Link,
  createRootRouteWithContext,
  useRouter,
  HeadContent,
  Scripts,
} from "@tanstack/react-router";
import { useEffect, type ReactNode } from "react";

import appCss from "../styles.css?url";
import { reportHiggsfieldError } from "../lib/higgsfield-error-reporting";

function NotFoundComponent() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <h1 className="text-7xl font-bold text-foreground">404</h1>
        <h2 className="mt-4 text-xl font-semibold text-foreground">Page not found</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          The page you're looking for doesn't exist or has been moved.
        </p>
        <div className="mt-6">
          <Link
            to="/"
            className="inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Go home
          </Link>
        </div>
      </div>
    </div>
  );
}

function ErrorComponent({ error, reset }: { error: Error; reset: () => void }) {
  console.error(error);
  const router = useRouter();
  useEffect(() => {
    reportHiggsfieldError(error, { boundary: "tanstack_root_error_component" });
  }, [error]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <h1 className="text-xl font-semibold tracking-tight text-foreground">
          This page didn't load
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Something went wrong on our end. You can try refreshing or head back home.
        </p>
        <div className="mt-6 flex flex-wrap justify-center gap-2">
          <button
            onClick={() => {
              router.invalidate();
              reset();
            }}
            className="inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Try again
          </button>
          <a
            href="/"
            className="inline-flex items-center justify-center rounded-md border border-input bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
          >
            Go home
          </a>
        </div>
      </div>
    </div>
  );
}

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  head: () => ({
    meta: [
      { charSet: "utf-8" },
      { name: "viewport", content: "width=device-width, initial-scale=1" },
      { title: "STRIKE PROTOCOL — Tactical Browser FPS" },
      {
        name: "description",
        content:
          "Round-based tactical FPS in your browser. Bomb defuse vs AI squads or online deathmatch with friends via one invite link. Buy phase, economy, recoil, headshots, radar. No downloads.",
      },
      { name: "author", content: "STRIKE PROTOCOL" },
      { name: "theme-color", content: "#0a0c0e" },
      { property: "og:title", content: "STRIKE PROTOCOL — Tactical Browser FPS" },
      {
        property: "og:description",
        content:
          "Bomb defuse vs tactical bots or online deathmatch with friends — one invite link, zero downloads. Economy, recoil, headshots, radar. Runs entirely in your browser.",
      },
      { property: "og:type", content: "website" },
      { property: "og:url", content: "https://dev--yellow-dune-350.higgsfield.app/" },
      { property: "og:image", content: "https://dev--yellow-dune-350.higgsfield.app/art/og.jpg" },
      { property: "og:image:width", content: "1376" },
      { property: "og:image:height", content: "768" },
      { property: "og:image:alt", content: "STRIKE PROTOCOL — desert arena key art" },
      { property: "og:site_name", content: "STRIKE PROTOCOL" },
      { name: "twitter:card", content: "summary_large_image" },
      { name: "twitter:title", content: "STRIKE PROTOCOL — Tactical Browser FPS" },
      {
        name: "twitter:description",
        content:
          "Bomb defuse vs tactical bots or online deathmatch with friends — one invite link, zero downloads.",
      },
      { name: "twitter:image", content: "https://dev--yellow-dune-350.higgsfield.app/art/og.jpg" },
    ],
    links: [
      {
        rel: "stylesheet",
        href: appCss,
      },
      { rel: "icon", type: "image/jpeg", href: "/art/og.jpg" },
      { rel: "apple-touch-icon", href: "/art/og.jpg" },
    ],
  }),
  shellComponent: RootShell,
  component: RootComponent,
  notFoundComponent: NotFoundComponent,
  errorComponent: ErrorComponent,
});

function RootShell({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <head>
        <HeadContent />
      </head>
      <body>
        {children}
        <Scripts />
      </body>
    </html>
  );
}

function RootComponent() {
  const { queryClient } = Route.useRouteContext();

  return (
    <QueryClientProvider client={queryClient}>
      {/* Required: nested routes render here. Removing <Outlet /> breaks all child routes. */}
      <Outlet />
    </QueryClientProvider>
  );
}
