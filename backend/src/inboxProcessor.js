export const INBOX_PREFIX = 'inbox/';

// The receipt body is untrusted (written by whichever client held a valid
// presigned PUT URL); clientId/receiptId get interpolated into an S3 key
// below, so they're restricted to safe characters against path traversal /
// arbitrary key write.
const SAFE_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

const noopEmit = () => {};

function elapsedMs(startedAt) {
  return Number(process.hrtime.bigint() - startedAt) / 1_000_000;
}

function archiveKey(receipt) {
  if (!SAFE_ID_RE.test(receipt.clientId ?? '') || !SAFE_ID_RE.test(receipt.receiptId ?? '')) {
    throw new Error(`invalid clientId/receiptId in receipt: ${JSON.stringify(receipt)}`);
  }
  const date = new Date(receipt.date);
  if (Number.isNaN(date.getTime())) {
    throw new Error(`invalid date in receipt ${receipt.receiptId}`);
  }
  const yyyy = date.getUTCFullYear();
  const mm = String(date.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(date.getUTCDate()).padStart(2, '0');
  return `archive/${receipt.clientId}/${yyyy}/${mm}/${dd}/${receipt.receiptId}.json`;
}

async function processObject(store, key, log, emit) {
  const getStartedAt = process.hrtime.bigint();
  const receipt = await store.getJson(key);
  const getDurationMs = elapsedMs(getStartedAt);
  log(`processing receipt ${receipt.receiptId} from ${receipt.clientId}, total=${receipt.totalAmount}`);
  emit({ type: 'receipt-processed', source: 'backend', clientId: receipt.clientId, receiptId: receipt.receiptId, outcome: 'ok', durationMs: getDurationMs });

  const destKey = archiveKey(receipt);
  const archiveStartedAt = process.hrtime.bigint();
  await store.copy(key, destKey);
  try {
    await store.delete(key);
    emit({ type: 'receipt-archived', source: 'backend', clientId: receipt.clientId, receiptId: receipt.receiptId, outcome: 'ok', durationMs: elapsedMs(archiveStartedAt) });
  } catch (err) {
    // Copy already succeeded and is idempotent (same destination key), so a
    // failed delete just means this object gets reprocessed next interval.
    log(`warning: failed to delete ${key} after archiving, will reprocess: ${err.message}`);
    emit({ type: 'receipt-archived', source: 'backend', clientId: receipt.clientId, receiptId: receipt.receiptId, outcome: 'error', detail: 'delete failed, will retry', durationMs: elapsedMs(archiveStartedAt) });
  }
}

// Drains the whole inbox, one page at a time, tolerating per-object failures
// (a bad object is logged and left for the next interval rather than
// aborting the rest of the page).
export async function processInbox(store, pageSize, log = console.log, emit = noopEmit) {
  let continuationToken;
  let totalFound = 0;
  let listDurationMs = 0;
  do {
    const listStartedAt = process.hrtime.bigint();
    const { keys, nextToken } = await store.listPage(INBOX_PREFIX, continuationToken, pageSize);
    listDurationMs += elapsedMs(listStartedAt);
    totalFound += keys.length;
    for (const key of keys) {
      try {
        await processObject(store, key, log, emit);
      } catch (err) {
        log(`error: failed to process ${key}: ${err.message}`);
        emit({ type: 'receipt-processed', source: 'backend', outcome: 'error', detail: err.message });
      }
    }
    continuationToken = nextToken;
  } while (continuationToken);
  emit({ type: 'inbox-poll', source: 'backend', outcome: 'ok', detail: `found ${totalFound} object(s)`, durationMs: listDurationMs });
}
