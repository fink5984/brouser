import type { FastifyInstance } from 'fastify';
import { env } from '../../config/env.js';
import { timingSafeEqual } from '../../lib/crypto.js';
import { proxyAuthenticateSchema } from './schemas.js';
import { UnauthorizedError, ValidationError } from '../../lib/errors.js';

/**
 * Internal-only surface used by the Squid basic-auth helper to validate the
 * single static proxy credential. Must never be exposed through the public
 * reverse proxy -- only reachable on the Docker-internal network, and
 * additionally gated by a shared secret as defense in depth.
 */
export default async function internalRoutes(fastify: FastifyInstance) {
  fastify.post(
    '/proxy/authenticate',
    { config: { rateLimit: { max: 600, timeWindow: '1 minute' } } },
    async (req, reply) => {
      const secret = req.headers['x-internal-secret'];
      if (typeof secret !== 'string' || !timingSafeEqual(secret, env.INTERNAL_API_SECRET)) {
        throw new UnauthorizedError('Invalid internal secret');
      }

      const parsed = proxyAuthenticateSchema.safeParse(req.body);
      if (!parsed.success) throw new ValidationError('Invalid credentials payload');
      const { username, password } = parsed.data;

      const ok = timingSafeEqual(username, env.PROXY_USERNAME) && timingSafeEqual(password, env.PROXY_PASSWORD);
      reply.send({ ok });
    },
  );
}
