import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { env } from '../src/config/env.js';

let app: ReturnType<typeof buildApp>;

beforeAll(async () => {
  app = buildApp();
  await app.ready();
});

afterAll(async () => {
  await app.close();
});

describe('GET /api/v1/health', () => {
  it('returns ok', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/health' });
    expect(res.statusCode).toBe(200);
    expect(res.json().status).toBe('ok');
  });
});

describe('GET /api/v1/device/config', () => {
  it('rejects requests with no token', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/device/config' });
    expect(res.statusCode).toBe(401);
  });

  it('rejects requests with the wrong token', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/device/config',
      headers: { authorization: 'Bearer wrong-token' },
    });
    expect(res.statusCode).toBe(401);
  });

  it('returns proxy + browser config for the correct token', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/device/config',
      headers: { authorization: `Bearer ${env.DEVICE_TOKEN}` },
    });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.proxy).toEqual({
      host: env.PROXY_HOST,
      port: env.PROXY_PORT,
      scheme: env.PROXY_SCHEME,
      username: env.PROXY_USERNAME,
      password: env.PROXY_PASSWORD,
    });
    expect(body.browser.homepage).toBe(env.HOMEPAGE);
    expect(body.browser.searchEngine).toBe(env.SEARCH_ENGINE);
    expect(body.browser.maxTabs).toBe(env.MAX_TABS);
    expect(body.browser.downloadsEnabled).toBe(env.DOWNLOADS_ENABLED);
  });
});

describe('GET /api/v1/app/version', () => {
  it('is public and returns version info', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/app/version' });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({
      latestVersion: env.LATEST_VERSION,
      minimumVersion: env.MINIMUM_VERSION,
    });
  });
});

describe('POST /internal/v1/proxy/authenticate', () => {
  it('rejects requests missing the internal secret', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/internal/v1/proxy/authenticate',
      payload: { username: env.PROXY_USERNAME, password: env.PROXY_PASSWORD },
    });
    expect(res.statusCode).toBe(401);
  });

  it('rejects requests with the wrong internal secret', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/internal/v1/proxy/authenticate',
      headers: { 'x-internal-secret': 'wrong-secret' },
      payload: { username: env.PROXY_USERNAME, password: env.PROXY_PASSWORD },
    });
    expect(res.statusCode).toBe(401);
  });

  it('returns ok:false for wrong proxy credentials with a valid internal secret', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/internal/v1/proxy/authenticate',
      headers: { 'x-internal-secret': env.INTERNAL_API_SECRET },
      payload: { username: env.PROXY_USERNAME, password: 'totally-wrong' },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ ok: false });
  });

  it('returns ok:true for the correct proxy credentials', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/internal/v1/proxy/authenticate',
      headers: { 'x-internal-secret': env.INTERNAL_API_SECRET },
      payload: { username: env.PROXY_USERNAME, password: env.PROXY_PASSWORD },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ ok: true });
  });

  it('rejects a malformed payload', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/internal/v1/proxy/authenticate',
      headers: { 'x-internal-secret': env.INTERNAL_API_SECRET },
      payload: { username: '' },
    });
    expect(res.statusCode).toBe(400);
  });
});

describe('unknown routes', () => {
  it('returns a 404 JSON body', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/does-not-exist' });
    expect(res.statusCode).toBe(404);
    expect(res.json().error).toBe('NOT_FOUND');
  });
});
