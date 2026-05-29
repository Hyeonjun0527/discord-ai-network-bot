/**
 * OAuth2 helpers and auth-state for the Next.js dashboard.
 *
 * #34: JWT 는 더 이상 localStorage 에 저장하지 않는다. 백엔드가 httpOnly+SameSite=Lax
 * (+Secure) 쿠키로 발급하므로 JS 는 토큰을 읽을 수 없고, 브라우저가 모든 요청에
 * `credentials: 'include'` 로 자동 전송한다(XSS 토큰 탈취 방지).
 *
 * 라우팅 가드에 쓸 "로그인된 것 같다" 신호만 localStorage 에 둔다(민감정보 아님).
 * 실제 인증 판정은 백엔드 /auth/me 가 권위를 가진다.
 */

/** 비민감 로그인 힌트 플래그 키. 실제 토큰이 아니라 라우팅 힌트일 뿐이다. */
const LOGGED_IN_HINT_KEY = "dashboard_logged_in";

/** Redirect the user to the Discord OAuth2 login endpoint (proxied via Next.js). */
export function redirectToDiscordLogin(): void {
  window.location.href = "/auth/login";
}

/**
 * 로그인 힌트를 켠다 (#34). 콜백 처리 직후 호출해 라우팅 가드가 대시보드로
 * 보낼 수 있게 한다. 토큰 자체는 httpOnly 쿠키에 있으므로 여기 저장하지 않는다.
 */
export function markLoggedIn(): void {
  if (typeof window !== "undefined") {
    localStorage.setItem(LOGGED_IN_HINT_KEY, "1");
  }
}

/** 로그인 힌트가 켜져 있는지(=대시보드를 시도해볼 만한지) 반환한다 (#34). */
export function hasLoginHint(): boolean {
  if (typeof window === "undefined") return false;
  return localStorage.getItem(LOGGED_IN_HINT_KEY) === "1";
}

/** 로그인 힌트를 끈다(로컬 정리). 실제 토큰 무효화는 백엔드 /auth/logout 이 한다. */
export function clearLoginHint(): void {
  if (typeof window !== "undefined") {
    localStorage.removeItem(LOGGED_IN_HINT_KEY);
  }
}

/**
 * 로그아웃 (#34, #44).
 *
 * 백엔드 /auth/logout 을 호출해 토큰을 무효화(jti 블랙리스트)하고 httpOnly 쿠키를
 * 삭제한다. 네트워크 실패와 무관하게 로컬 힌트는 항상 정리한다.
 */
export async function logout(): Promise<void> {
  const base =
    typeof process !== "undefined" && process.env.NEXT_PUBLIC_API_URL
      ? process.env.NEXT_PUBLIC_API_URL
      : "";
  try {
    await fetch(`${base}/auth/logout`, {
      method: "POST",
      credentials: "include",
    });
  } catch {
    // 무시: 쿠키는 서버 응답으로만 지워지지만, 실패해도 힌트는 정리한다.
  } finally {
    clearLoginHint();
  }
}

/** 진행 중인 refresh 요청을 공유해 동시 다중 호출을 한 번으로 묶는다 (#85). */
let _refreshInFlight: Promise<boolean> | null = null;

/**
 * 백엔드 /auth/refresh 를 호출해 새 토큰을 받아 쿠키를 갱신한다 (#85, #34).
 *
 * 토큰은 쿠키로 자동 전송/수신되므로 JS 는 토큰 문자열을 다루지 않는다.
 * 성공 시 true, 실패(401 등) 시 false 를 반환한다. 동시 호출은 단일 네트워크
 * 요청으로 합친다.
 */
export function refreshToken(): Promise<boolean> {
  if (_refreshInFlight) return _refreshInFlight;

  const base =
    typeof process !== "undefined" && process.env.NEXT_PUBLIC_API_URL
      ? process.env.NEXT_PUBLIC_API_URL
      : "";

  _refreshInFlight = fetch(`${base}/auth/refresh`, {
    method: "POST",
    credentials: "include",
  })
    .then((res) => res.ok)
    .catch(() => false)
    .finally(() => {
      _refreshInFlight = null;
    });

  return _refreshInFlight;
}
