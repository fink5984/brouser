import 'dotenv/config';
import { z } from 'zod';

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(4000),
  HOST: z.string().default('0.0.0.0'),

  // Single shared secret the app presents as `Authorization: Bearer <token>`
  // when fetching /device/config. There is only one device, so there is
  // only one token -- generate with: openssl rand -base64 32
  DEVICE_TOKEN: z.string().min(24, 'DEVICE_TOKEN must be at least 24 characters'),

  // Shared secret between this backend and the Squid auth helper. Never
  // exposed publicly -- only the proxy container calls this endpoint.
  INTERNAL_API_SECRET: z.string().min(24, 'INTERNAL_API_SECRET must be at least 24 characters'),

  // Static proxy credentials handed to Squid's auth helper for validation.
  PROXY_USERNAME: z.string().min(1, 'PROXY_USERNAME is required'),
  PROXY_PASSWORD: z.string().min(12, 'PROXY_PASSWORD must be at least 12 characters'),

  // Public connection info returned to the app. "https" means the app opens
  // a TLS connection to the proxy itself before speaking the proxy protocol
  // inside it (androidx.webkit ProxyConfig "https" scheme) -- this is what
  // Squid's https_port (3129) expects. Use "http" only for the plain 3128
  // port during local development.
  PROXY_HOST: z.string().min(1, 'PROXY_HOST is required'),
  PROXY_PORT: z.coerce.number().int().positive().default(3129),
  PROXY_SCHEME: z.enum(['http', 'https']).default('https'),

  // Browser defaults, editable here without rebuilding the APK.
  HOMEPAGE: z.string().url().default('https://www.google.com'),
  SEARCH_ENGINE: z.enum(['google', 'bing', 'duckduckgo']).default('google'),
  MAX_TABS: z.coerce.number().int().min(1).max(50).default(10),
  DOWNLOADS_ENABLED: z
    .enum(['true', 'false'])
    .default('true')
    .transform((v) => v === 'true'),
  LATEST_VERSION: z.string().default('1.0.0'),
  MINIMUM_VERSION: z.string().default('1.0.0'),

  CORS_ORIGIN: z.string().default('*'),
  RATE_LIMIT_MAX: z.coerce.number().int().positive().default(60),
  RATE_LIMIT_WINDOW: z.string().default('1 minute'),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace']).default('info'),
});

export type Env = z.infer<typeof envSchema>;

function loadEnv(): Env {
  const parsed = envSchema.safeParse(process.env);
  if (!parsed.success) {
    // eslint-disable-next-line no-console
    console.error('Invalid environment configuration:', parsed.error.flatten().fieldErrors);
    throw new Error('Invalid environment configuration');
  }
  return parsed.data;
}

export const env = loadEnv();
