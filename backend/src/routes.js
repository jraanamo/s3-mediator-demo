import { issueToken, verifyToken } from './jwt.js';
import { CONFIG_KEY, CATALOG_KEY } from './catalogPublisher.js';
import { validateEvent, MAX_EVENT_BODY_BYTES } from './monitorEvents.js';
import { config } from './config.js';

const PRESIGN_EXPIRY_SECONDS = 600;
// deviceId/receiptId are client-supplied and get interpolated into S3 keys
// (directly here, and via receiptId/clientId in InboxProcessor); restrict
// them to safe characters so they can't escape the inbox/archive prefixes
// (path traversal / arbitrary key write).
const SAFE_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
// SSE comment lines, sent periodically so any idle-connection timeout in
// front of the Backend (proxies commonly close silent connections after
// ~60s) never sees this stream as idle and cuts it.
const SSE_HEARTBEAT_INTERVAL_MS = 20_000;

function bearerToken(request) {
  const header = request.headers.authorization ?? '';
  const [scheme, token] = header.split(' ');
  return scheme === 'Bearer' ? token : undefined;
}

export function registerRoutes(app, store, monitorHub, pageHtml) {
  app.get('/', async (request, reply) => {
    reply.type('text/html').send(pageHtml);
  });

  app.get('/monitor/events', (request, reply) => {
    reply.hijack();
    reply.raw.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      connection: 'keep-alive',
    });
    reply.raw.write('\n');
    monitorHub.addStream(reply.raw);

    const heartbeat = setInterval(() => {
      try {
        reply.raw.write(': keep-alive\n\n');
      } catch {
        clearInterval(heartbeat);
      }
    }, SSE_HEARTBEAT_INTERVAL_MS);

    request.raw.on('close', () => {
      clearInterval(heartbeat);
      monitorHub.removeStream(reply.raw);
    });
  });

  app.post('/monitor/events', { bodyLimit: MAX_EVENT_BODY_BYTES }, async (request, reply) => {
    const token = bearerToken(request);
    try {
      verifyToken(token);
    } catch {
      return reply.code(401).send({ error: 'invalid or expired token' });
    }

    const error = validateEvent(request.body);
    if (error) {
      return reply.code(400).send({ error });
    }

    monitorHub.broadcast({ ...request.body, source: 'client' });
    return reply.code(204).send();
  });

  app.post('/login', async (request, reply) => {
    const startedAt = process.hrtime.bigint();
    const { deviceId } = request.body ?? {};
    if (!SAFE_ID_RE.test(deviceId ?? '')) {
      monitorHub.broadcast({ type: 'login', source: 'backend', outcome: 'error', detail: 'invalid deviceId' });
      return reply.code(400).send({ error: 'invalid deviceId' });
    }

    const uploadPrefix = `inbox/${deviceId}/`;
    const [{ token, expiresAt }, configUrl, catalogUrl, upload] = await Promise.all([
      issueToken(deviceId),
      store.presignGet(CONFIG_KEY, PRESIGN_EXPIRY_SECONDS),
      store.presignGet(CATALOG_KEY, PRESIGN_EXPIRY_SECONDS),
      store.assumeUploadRole(uploadPrefix, config.jwtExpirySeconds, deviceId),
    ]);

    const durationMs = Number(process.hrtime.bigint() - startedAt) / 1_000_000;
    monitorHub.broadcast({ type: 'login', source: 'backend', clientId: deviceId, outcome: 'ok', durationMs });

    return {
      token,
      expiresAt,
      resources: { config: configUrl, catalog: catalogUrl },
      upload: {
        endpoint: config.s3.endpoint,
        region: config.s3.region,
        bucket: config.s3.bucket,
        keyPrefix: uploadPrefix,
        accessKeyId: upload.accessKeyId,
        secretAccessKey: upload.secretAccessKey,
        sessionToken: upload.sessionToken,
        expiration: upload.expiration,
      },
    };
  });
}
