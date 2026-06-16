import { test } from 'node:test';
import assert from 'node:assert/strict';
import { exchangeCodeForToken } from './token';

const cfg = { clientId: 'cid', clientSecret: 'secret' };

function fakeFetch(status: number, json: unknown): typeof fetch {
  return (async () =>
    ({ ok: status >= 200 && status < 300, status, json: async () => json }) as Response) as typeof fetch;
}

test('rejects a missing/empty code with 400', async () => {
  assert.deepEqual(await exchangeCodeForToken(undefined, cfg), {
    ok: false,
    status: 400,
    error: 'missing code',
  });
  assert.equal((await exchangeCodeForToken('', cfg)).status, 400);
});

test('rejects when server is missing client credentials with 500', async () => {
  const r = await exchangeCodeForToken('code', { clientId: '', clientSecret: '' });
  assert.equal(r.ok, false);
  assert.equal(r.status, 500);
});

test('returns the access_token from a successful exchange', async () => {
  const r = await exchangeCodeForToken('good-code', {
    ...cfg,
    fetchImpl: fakeFetch(200, { access_token: 'tok-123' }),
  });
  assert.deepEqual(r, { ok: true, status: 200, accessToken: 'tok-123' });
});

test('maps a Discord error response to 502', async () => {
  const r = await exchangeCodeForToken('bad-code', {
    ...cfg,
    fetchImpl: fakeFetch(401, { error: 'invalid_grant' }),
  });
  assert.equal(r.ok, false);
  assert.equal(r.status, 502);
});

test('502 when Discord omits the access_token', async () => {
  const r = await exchangeCodeForToken('code', {
    ...cfg,
    fetchImpl: fakeFetch(200, {}),
  });
  assert.equal(r.ok, false);
  assert.equal(r.status, 502);
});

test('does not leak the client_secret in any result', async () => {
  const r = await exchangeCodeForToken('code', {
    ...cfg,
    fetchImpl: fakeFetch(200, { access_token: 'tok' }),
  });
  assert.equal(JSON.stringify(r).includes('secret'), false);
});
