import 'dotenv/config';

export const config = {
  port: Number(process.env.PORT ?? 3000),
  backendUrl: process.env.BACKEND_URL ?? `http://localhost:${process.env.PORT ?? 3000}`,
  jwtSecret: process.env.JWT_SECRET ?? 'dev-secret',
  jwtExpirySeconds: Number(process.env.JWT_EXPIRY_SECONDS ?? 3600),
  deviceIds: (process.env.DEVICE_IDS ?? '').split(',').map((s) => s.trim()).filter(Boolean),
  s3: {
    endpoint: process.env.S3_ENDPOINT,
    region: process.env.S3_REGION ?? 'us-east-1',
    bucket: process.env.S3_BUCKET,
    accessKeyId: process.env.S3_ACCESS_KEY_ID,
    secretAccessKey: process.env.S3_SECRET_ACCESS_KEY,
  },
  publishIntervalSeconds: Number(process.env.PUBLISH_INTERVAL_SECONDS ?? 300),
  inboxPollIntervalSeconds: Number(process.env.INBOX_POLL_INTERVAL_SECONDS ?? 30),
  inboxPageSize: Number(process.env.INBOX_PAGE_SIZE ?? 50),
};
