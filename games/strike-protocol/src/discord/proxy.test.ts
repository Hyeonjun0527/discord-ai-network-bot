import { test } from "node:test";
import assert from "node:assert/strict";

import { isInDiscord, tokenEndpoint, LOCAL_TOKEN_PATH, PROXY_TOKEN_PATH } from "./proxy";

test("isInDiscord: true only when frame_id is present", () => {
  assert.equal(isInDiscord("?frame_id=abc&foo=1"), true);
  assert.equal(isInDiscord("?foo=1"), false);
  assert.equal(isInDiscord(""), false);
});

test("tokenEndpoint: proxied path in Discord, same-origin otherwise", () => {
  assert.equal(tokenEndpoint(true), PROXY_TOKEN_PATH);
  assert.equal(tokenEndpoint(false), LOCAL_TOKEN_PATH);
});
