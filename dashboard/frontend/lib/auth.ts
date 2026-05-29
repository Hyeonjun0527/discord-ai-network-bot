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

/**
 * 토큰 만료가 임박했는지 판단한다 (#85).
 *
 * exp 까지 `thresholdSeconds`(기본 5분) 이하로 남았으면 true 를 돌려준다.
 * 이미 만료됐거나 토큰이 없으면 false(갱신 대상 아님 — 재로그인 영역)로 본다.
 */
export function isTokenExpiringSoon(thresholdSeconds = 300): boolean {
  const token = getToken();
  if (!token) return false;
  const payload = decodeToken(token);
  if (!payload) return false;
  const exp = payload["exp"];
  if (typeof exp !== "number") return false; // exp 없으면 갱신 불필요
  const secondsLeft = exp - Date.now() / 1000;
  return secondsLeft > 0 && secondsLeft <= thresholdSeconds;
}

/** 진행 중인 refresh 요청을 공유해 동시 다중 호출을 한 번으로 묶는다 (#85). */
let _refreshInFlight: Promise<boolean> | null = null;

/**
 * 현재 토큰으로 백엔드 /auth/refresh 를 호출해 새 토큰을 받아 저장한다 (#85).
 *
 * 성공 시 true 를 반환하고 새 JWT 를 localStorage 에 저장한다. 실패(401 등)하면
 * false 를 반환하며 호출 측이 재로그인 플로우로 처리하도록 한다.
 * 동시에 여러 요청이 갱신을 시도해도 단일 네트워크 호출만 수행한다.
 */
export function refreshToken(): Promise<boolean> {
  if (_refreshInFlight) return _refreshInFlight;

  const token = getToken();
  if (!token) return Promise.resolve(false);

  const base =
    typeof process !== "undefined" && process.env.NEXT_PUBLIC_API_URL
      ? process.env.NEXT_PUBLIC_API_URL
      : "";

  _refreshInFlight = fetch(`${base}/auth/refresh`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  })
    .then(async (res) => {
      if (!res.ok) return false;
      const data = (await res.json()) as { token?: string };
      if (data.token) {
        setToken(data.token);
        return true;
      }
      return false;
    })
    .catch(() => false)
    .finally(() => {
      _refreshInFlight = null;
    });

  return _refreshInFlight;
}
