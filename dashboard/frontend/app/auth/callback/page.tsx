"use client";

/**
 * /auth/callback
 *
 * The Next.js app rewrites /auth/* → the FastAPI backend, so this page is
 * normally never reached directly.  It exists as a fallback that handles the
 * case where the backend redirects back to /auth/callback with the JWT in the
 * query string (e.g. `?token=<jwt>`) instead of returning JSON.
 *
 * If you configure DISCORD_REDIRECT_URI to point straight at the Next.js app
 * (i.e., http://localhost:3000/auth/callback), you must update the FastAPI
 * callback endpoint to redirect here with `?token=<jwt>` instead of returning
 * JSON.
 */

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Loader2 } from "lucide-react";
import { setToken } from "@/lib/auth";

// useSearchParams() 를 쓰는 컴포넌트는 정적 빌드 시 Suspense 경계로 감싸야 한다.
// (#86: next build 가 prerender 단계에서 실패하던 문제 해결)
function CallbackInner() {
  const router = useRouter();
  const params = useSearchParams();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const token = params.get("token");
    const err = params.get("error");

    if (err) {
      setError(`Discord returned an error: ${err}`);
      return;
    }

    if (token) {
      setToken(token);
      router.replace("/dashboard");
      return;
    }

    // No token in params — this page was hit without expected data
    setError("No token received. Please try logging in again.");
  }, [params, router]);

  if (error) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-discord-darkest">
        <div className="bg-discord-darker rounded-xl p-8 max-w-md w-full mx-4 border border-discord-red/30">
          <h2 className="text-discord-red font-semibold text-lg mb-2">Login Failed</h2>
          <p className="text-gray-400 text-sm">{error}</p>
          <button
            className="mt-4 text-discord-blurple hover:underline text-sm"
            onClick={() => router.push("/")}
          >
            Back to login
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-discord-darkest">
      <div className="flex flex-col items-center gap-4 text-gray-400">
        <Loader2 className="w-8 h-8 animate-spin text-discord-blurple" />
        <p>Completing login&hellip;</p>
      </div>
    </div>
  );
}

function CallbackFallback() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-discord-darkest">
      <div className="flex flex-col items-center gap-4 text-gray-400">
        <Loader2 className="w-8 h-8 animate-spin text-discord-blurple" />
        <p>Completing login&hellip;</p>
      </div>
    </div>
  );
}

export default function CallbackPage() {
  return (
    <Suspense fallback={<CallbackFallback />}>
      <CallbackInner />
    </Suspense>
  );
}
