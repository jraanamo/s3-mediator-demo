export const INBOX_PREFIX = 'inbox/';

// The receipt body is untrusted (written by whichever client held a valid
// presigned PUT URL); clientId/receiptId get interpolated into an S3 key
// below, so they're restricted to safe characters against path traversal /
// arbitrary key write.
const SAFE_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

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

async function processObject(store, key, log) {
  const receipt = await store.getJson(key);
  log(`processing receipt ${receipt.receiptId} from ${receipt.clientId}, total=${receipt.totalAmount}`);
  const destKey = archiveKey(receipt);
  await store.copy(key, destKey);
  try {
    await store.delete(key);
  } catch (err) {
    // Copy already succeeded and is idempotent (same destination key), so a
    // failed delete just means this object gets reprocessed next interval.
    log(`warning: failed to delete ${key} after archiving, will reprocess: ${err.message}`);
  }
}

// Drains the whole inbox, one page at a time, tolerating per-object failures
// (a bad object is logged and left for the next interval rather than
// aborting the rest of the page).
export async function processInbox(store, pageSize, log = console.log) {
  let continuationToken;
  do {
    const { keys, nextToken } = await store.listPage(INBOX_PREFIX, continuationToken, pageSize);
    for (const key of keys) {
      try {
        await processObject(store, key, log);
      } catch (err) {
        log(`error: failed to process ${key}: ${err.message}`);
      }
    }
    continuationToken = nextToken;
  } while (continuationToken);
}
