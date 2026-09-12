import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import Fastify from 'fastify';
import { config } from './config.js';
import { createS3Store } from './s3Store.js';
import { registerRoutes } from './routes.js';
import { ensureInitialPublish, publish } from './catalogPublisher.js';
import { processInbox } from './inboxProcessor.js';
import { createMonitorHub } from './monitorEvents.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const pageHtml = readFileSync(join(__dirname, '..', 'public', 'index.html'), 'utf-8');

const app = Fastify({ logger: true });
const store = createS3Store();
const monitorHub = createMonitorHub();
const uploadReceiptsUrl = `${config.backendUrl}/upload-receipts`;

registerRoutes(app, store, monitorHub, pageHtml);

try {
  await ensureInitialPublish(store, uploadReceiptsUrl);
} catch (err) {
  app.log.error(err, 'initial catalog publish failed; clients will have nothing to fetch until this succeeds');
}

setInterval(() => {
  publish(store, uploadReceiptsUrl).catch((err) => app.log.error(err, 'catalog publish failed'));
}, config.publishIntervalSeconds * 1000);

setInterval(() => {
  processInbox(store, config.inboxPageSize, (msg) => app.log.info(msg), (event) => monitorHub.broadcast(event))
    .catch((err) => app.log.error(err, 'inbox processing failed'));
}, config.inboxPollIntervalSeconds * 1000);

await app.listen({ host: '0.0.0.0', port: config.port });
