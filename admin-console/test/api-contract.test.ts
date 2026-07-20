import assert from "node:assert/strict";
import test from "node:test";

import {
  ApiRequestError,
  ApiResponseParseError,
  type ChannelSummary,
  type GuildSummary,
  isAuthenticationRequired,
  parseApiErrorPayload,
  resolveRequestTarget,
  toPartialDashboardError,
} from "../src/api-contract.js";

test("Discord snowflake summaries keep exact string identifiers", () => {
  const guild: GuildSummary = { id: "1380395592336805928", name: "test guild" };
  const channel: ChannelSummary = { id: "1509347932665675867", name: "general" };

  assert.equal(guild.id, "1380395592336805928");
  assert.equal(channel.id, "1509347932665675867");
});

test("parseApiErrorPayload preserves typed server error fields", () => {
  const payload = parseApiErrorPayload(
    JSON.stringify({
      success: false,
      status: 403,
      requestId: "srv-1",
      error: {
        code: "DASHBOARD_ADMIN_REQUIRED",
        message: "관리자 권한이 필요합니다.",
        details: { requiredRole: "admin" },
        currentState: "anonymous",
        requiredState: "admin",
        failedCondition: "dashboard_admin_authenticated",
        blockedAction: "AI_NETWORK_ADMIN_ACCESS",
        actionGuide: "OAuth 또는 admin token으로 인증하세요.",
      },
    }),
  );

  assert.equal(payload?.success, false);
  assert.equal(payload?.status, 403);
  assert.equal(payload?.requestId, "srv-1");
  assert.equal(payload?.error?.code, "DASHBOARD_ADMIN_REQUIRED");
  assert.equal(payload?.error?.actionGuide, "OAuth 또는 admin token으로 인증하세요.");
  assert.deepEqual(payload?.error?.details, { requiredRole: "admin" });
});

test("parseApiErrorPayload rejects unstructured or wrong-typed error bodies", () => {
  assert.equal(parseApiErrorPayload("not-json"), null);
  assert.equal(parseApiErrorPayload(JSON.stringify({ status: 500, error: "boom" })), null);

  const payload = parseApiErrorPayload(
    JSON.stringify({
      success: "false",
      status: "403",
      requestId: 123,
      error: { code: 7, message: false, details: "raw" },
    }),
  );

  assert.equal(payload?.success, undefined);
  assert.equal(payload?.status, undefined);
  assert.equal(payload?.requestId, undefined);
  assert.equal(payload?.error?.code, undefined);
  assert.equal(payload?.error?.message, undefined);
  assert.equal(payload?.error?.details, undefined);
});

test("ApiRequestError exposes code, action guide, and server request id", () => {
  const response = new Response("{}", {
    status: 403,
    headers: { "X-Request-Id": "hdr-ignored" },
  });
  const error = new ApiRequestError("/api/dashboard/guilds", response, {
    status: 403,
    requestId: "srv-2",
    error: {
      code: "DASHBOARD_ADMIN_REQUIRED",
      message: "관리자 권한이 필요합니다.",
      actionGuide: "다시 로그인하세요.",
    },
  });

  assert.equal(error.status, 403);
  assert.equal(error.code, "DASHBOARD_ADMIN_REQUIRED");
  assert.equal(error.requestId, "srv-2");
  assert.match(error.message, /다시 로그인하세요/);
});

test("isAuthenticationRequired only accepts unauthorized API errors", () => {
  const unauthorized = new ApiRequestError("/api/dashboard/guilds", new Response("", { status: 401 }), null);
  const forbidden = new ApiRequestError("/api/dashboard/guilds", new Response("", { status: 403 }), null);

  assert.equal(isAuthenticationRequired(unauthorized), true);
  assert.equal(isAuthenticationRequired(forbidden), false);
  assert.equal(isAuthenticationRequired(new TypeError("Failed to fetch")), false);
});

test("resolveRequestTarget keeps fetch URL and reported request URL aligned", () => {
  assert.deepEqual(resolveRequestTarget("/api/dashboard/guilds", "https://console.example"), {
    fetchUrl: "/api/dashboard/guilds",
    requestUrl: "https://console.example/api/dashboard/guilds",
    serverBaseUrl: "https://console.example",
  });
});

test("successful response JSON parse failures become partial dashboard errors", () => {
  const response = new Response("not-json", {
    status: 200,
    headers: { "X-Request-Id": "srv-3" },
  });
  const error = new ApiResponseParseError("/api/dashboard/1/requests", response, "client-1", new SyntaxError("bad json"));
  const partial = toPartialDashboardError("requests", "/api/dashboard/1/requests", error);

  assert.equal(error.code, "INVALID_RESPONSE_JSON");
  assert.equal(partial.panel, "requests");
  assert.equal(partial.status, 200);
  assert.equal(partial.code, "INVALID_RESPONSE_JSON");
  assert.equal(partial.serverRequestId, "srv-3");
});
