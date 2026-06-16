import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  gameServerUrl,
  isInDiscord,
  roomIdFromInstance,
  tokenEndpoint,
  PROXY_TOKEN_PATH,
  PROXY_COLYSEUS_PREFIX,
  LOCAL_TOKEN_PATH,
  type LocationLike,
} from './proxy';

test('isInDiscord: true only when frame_id is present', () => {
  assert.equal(isInDiscord('?frame_id=abc&foo=1'), true);
  assert.equal(isInDiscord('?foo=1'), false);
  assert.equal(isInDiscord(''), false);
});

test('tokenEndpoint: proxied path in Discord, same-origin otherwise', () => {
  assert.equal(tokenEndpoint(true), PROXY_TOKEN_PATH);
  assert.equal(tokenEndpoint(false), LOCAL_TOKEN_PATH);
});

test('gameServerUrl: direct URL outside Discord, proxied wss inside', () => {
  const direct = 'ws://localhost:2567';
  const loc: LocationLike = {
    protocol: 'https:',
    host: '1234567890.discordsays.com',
    search: '?frame_id=x',
  };
  assert.equal(gameServerUrl(false, loc, direct), direct);
  assert.equal(
    gameServerUrl(true, loc, direct),
    `wss://1234567890.discordsays.com${PROXY_COLYSEUS_PREFIX}`,
  );
});

test('gameServerUrl: falls back to ws when the activity origin is http', () => {
  const loc: LocationLike = { protocol: 'http:', host: 'h.example', search: '?frame_id=x' };
  assert.equal(gameServerUrl(true, loc, 'ws://localhost:2567'), `ws://h.example${PROXY_COLYSEUS_PREFIX}`);
});

test('roomIdFromInstance: prefers instanceId, falls back to channelId, else null', () => {
  assert.equal(roomIdFromInstance('inst-1', 'chan-1'), 'inst-1');
  assert.equal(roomIdFromInstance(null, 'chan-1'), 'chan-1');
  assert.equal(roomIdFromInstance('', '  '), null);
  assert.equal(roomIdFromInstance(undefined, undefined), null);
});
