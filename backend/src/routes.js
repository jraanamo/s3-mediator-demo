import { issueToken, verifyToken } from './jwt.js';
import { CONFIG_KEY, CATALOG_KEY } from './catalogPublisher.js';

const PRESIGN_EXPIRY_SECONDS = 600;
// deviceId/receiptId are client-supplied and get interpolated into S3 keys
// (directly here, and via receiptId/clientId in InboxProcessor); restrict
// them to safe characters so they can't escape the inbox/archive prefixes
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
    if (!SAFE_ID_RE.test(deviceId ?? '')) {
      return reply.code(400).send({ error: 'invalid deviceId' });
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
