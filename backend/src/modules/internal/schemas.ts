import { z } from 'zod';

export const proxyAuthenticateSchema = z.object({
  username: z.string().min(1).max(256),
  password: z.string().min(1).max(512),
});
export type ProxyAuthenticateInput = z.infer<typeof proxyAuthenticateSchema>;
