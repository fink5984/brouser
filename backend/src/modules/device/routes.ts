import type { FastifyInstance } from 'fastify';
import { env } from '../../config/env.js';

export default async function deviceRoutes(fastify: FastifyInstance) {
  fastify.get(
    '/device/config',
    { preHandler: fastify.authenticateDevice },
    async (_req, reply) => {
      reply.send({
        proxy: {
          host: env.PROXY_HOST,
          port: env.PROXY_PORT,
          scheme: env.PROXY_SCHEME,
          username: env.PROXY_USERNAME,
          password: env.PROXY_PASSWORD,
        },
        browser: {
          homepage: env.HOMEPAGE,
          searchEngine: env.SEARCH_ENGINE,
          maxTabs: env.MAX_TABS,
          downloadsEnabled: env.DOWNLOADS_ENABLED,
        },
      });
    },
  );

  // Public: lets the app decide whether to nag about an update without
  // needing a device token, and without any install/update mechanism that
  // would bypass Android's own security model.
  fastify.get('/app/version', async (_req, reply) => {
    reply.send({ latestVersion: env.LATEST_VERSION, minimumVersion: env.MINIMUM_VERSION });
  });
}
