import type { FastifyInstance } from 'fastify';

export default async function healthRoutes(fastify: FastifyInstance) {
  fastify.get('/health', async (_req, reply) => {
    reply.send({ status: 'ok', time: new Date().toISOString() });
  });
}
