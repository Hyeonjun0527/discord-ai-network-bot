/**
 * OAuth2 helpers and token storage for the Next.js dashboard.
 *
 * Token is stored in localStorage so it survives page refreshes.
 * In production you may prefer httpOnly cookies — update the relevant
 * backend endpoint to Set-Cookie and remove localStorage usage here.
 */

const TOKEN_KEY = "dashboard_token";

/** Redirect the user to the Discord OAuth2 login endpoint (proxied via Next.js). */
export function redirectToDiscordLogin(): void {
  window.location.href = "/auth/login";
}

/** Persist the JWT returned by the backend. */
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

/** Return the stored JWT, or null if not logged in. */
export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(TOKEN_KEY);
}

/** Remove the stored JWT (logout). */
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

/**
 * Decode the JWT payload (without verifying the signature — the backend
 * does the authoritative verification).  Returns null on parse failure.
 */
export function decodeToken(token: string): Record<string, unknown> | null {
  try {
    const [, payloadB64] = token.split(".");
    const padded = payloadB64.replace(/-/g, "+").replace(/_/g, "/");
    const json = atob(padded);
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** Check whether the stored token is still valid (not expired client-side). */
export function isTokenValid(): boolean {
  const token = getToken();
  if (!token) return false;
  const payload = decodeToken(token);
  if (!payload) return false;
  const exp = payload["exp"];
  if (typeof exp !== "number") return true; // no exp claim — treat as valid
  return Date.now() / 1000 < exp;
}
