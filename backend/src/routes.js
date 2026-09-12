import { config } from './config.js';
import { issueToken, verifyToken } from './jwt.js';
import { CONFIG_KEY, CATALOG_KEY } from './catalogPublisher.js';

const PRESIGN_EXPIRY_SECONDS = 600;
// receiptId is client-supplied and gets interpolated into an S3 key below;
// restrict it to safe characters so it can't escape the inbox/ prefix
// (path traversal / arbitrary key write).
const SAFE_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

function bearerToken(request) {
  const header = request.headers.authorization ?? '';
  const [scheme, token] = header.split(' ');
  return scheme === 'Bearer' ? token : undefined;
}

export function registerRoutes(app, store) {
  app.post('/login', async (request, reply) => {
    const { deviceId } = request.body ?? {};
    if (!deviceId || !config.deviceIds.includes(deviceId)) {
      return reply.code(401).send({ error: 'unknown device' });
    }

    const { token, expiresAt } = issueToken(deviceId);
    const [configUrl, catalogUrl] = await Promise.all([
      store.presignGet(CONFIG_KEY, PRESIGN_EXPIRY_SECONDS),
      store.presignGet(CATALOG_KEY, PRESIGN_EXPIRY_SECONDS),
    ]);

    return {
      token,
      expiresAt,
      resources: { config: configUrl, catalog: catalogUrl },
    };
  });

  app.post('/upload-receipts', async (request, reply) => {
    const token = bearerToken(request);
    try {
      verifyToken(token);
    } catch {
      return reply.code(401).send({ error: 'invalid or expired token' });
    }

    const receipts = request.body ?? [];
    if (!receipts.every(({ receiptId }) => SAFE_ID_RE.test(receiptId ?? ''))) {
      return reply.code(400).send({ error: 'invalid receiptId' });
    }

    const results = await Promise.all(
      receipts.map(async ({ receiptId }) => ({
        receiptId,
        uploadUrl: await store.presignPut(`inbox/${receiptId}.json`, PRESIGN_EXPIRY_SECONDS),
      })),
    );
    return results;
  });
}
