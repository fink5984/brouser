import fp from 'fastify-plugin';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { env } from '../config/env.js';
import { timingSafeEqual } from '../lib/crypto.js';
import { UnauthorizedError } from '../lib/errors.js';

function extractBearer(req: FastifyRequest): string | null {
  const header = req.headers.authorization;
  if (!header || !header.startsWith('Bearer ')) return null;
  return header.slice('Bearer '.length).trim();
}

async function authenticateDevice(req: FastifyRequest, _reply: FastifyReply): Promise<void> {
  const token = extractBearer(req);
  if (!token || !timingSafeEqual(token, env.DEVICE_TOKEN)) {
    throw new UnauthorizedError('Invalid or missing device token');
  }
}

export default fp(async function authPlugin(fastify: FastifyInstance) {
  fastify.decorate('authenticateDevice', authenticateDevice);
});

declare module 'fastify' {
  interface FastifyInstance {
    authenticateDevice: typeof authenticateDevice;
  }
}
