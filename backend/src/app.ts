import Fastify, { type FastifyError } from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import { randomUUID } from 'node:crypto';
import { env } from './config/env.js';
import { logger } from './lib/logger.js';
import { AppError } from './lib/errors.js';
import authPlugin from './plugins/auth.js';
import healthRoutes from './modules/health/routes.js';
import deviceRoutes from './modules/device/routes.js';
import internalRoutes from './modules/internal/routes.js';

export function buildApp() {
  const app = Fastify({
    loggerInstance: logger,
    genReqId: (req) => (req.headers['x-request-id'] as string | undefined) ?? randomUUID(),
    requestIdHeader: 'x-request-id',
    trustProxy: true,
  });

  app.register(helmet, { contentSecurityPolicy: false });
  app.register(cors, { origin: env.CORS_ORIGIN });
  app.register(rateLimit, {
    max: env.RATE_LIMIT_MAX,
    timeWindow: env.RATE_LIMIT_WINDOW,
    allowList: [],
  });
  app.register(authPlugin);

  app.register(healthRoutes, { prefix: '/api/v1' });
  app.register(deviceRoutes, { prefix: '/api/v1' });
  // Internal-only: never exposed through the public reverse proxy.
  app.register(internalRoutes, { prefix: '/internal/v1' });

  app.setNotFoundHandler((_req, reply) => {
    reply.code(404).send({ error: 'NOT_FOUND', message: 'Route not found' });
  });

  app.setErrorHandler((error: FastifyError | AppError, req, reply) => {
    if (error instanceof AppError) {
      reply.code(error.statusCode).send({ error: error.code, message: error.message });
      return;
    }
    if (error.validation) {
      reply.code(400).send({ error: 'VALIDATION_ERROR', message: error.message });
      return;
    }
    if (error.statusCode === 429) {
      reply.code(429).send({ error: 'RATE_LIMITED', message: 'Too many requests' });
      return;
    }
    req.log.error({ err: error }, 'Unhandled error');
    reply.code(500).send({ error: 'INTERNAL_ERROR', message: 'Internal server error' });
  });

  return app;
}
