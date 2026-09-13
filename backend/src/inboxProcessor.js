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

// Runs `worker` over `items` with at most `concurrency` in flight at once.
// GET/COPY have no S3 bulk equivalent (one call per object), so this is what
// actually parallelizes a page instead of processing it one object at a time.
async function runWithConcurrency(items, concurrency, worker) {
  let nextIndex = 0;
  async function run() {
    while (nextIndex < items.length) {
      const index = nextIndex++;
      await worker(items[index]);
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, run));
}

// GET + parse + validate + COPY to the archive path. Does not delete the
// inbox object — deletes are batched separately across the whole page.
async function fetchAndArchive(store, key, log, emit) {
  const getStartedAt = process.hrtime.bigint();
  const receipt = await store.getJson(key);
  const getDurationMs = elapsedMs(getStartedAt);
  log(`processing receipt ${receipt.receiptId} from ${receipt.clientId}, total=${receipt.totalAmount}`);
  emit({ type: 'receipt-processed', source: 'backend', clientId: receipt.clientId, receiptId: receipt.receiptId, outcome: 'ok', durationMs: getDurationMs });

  const destKey = archiveKey(receipt);
  await store.copy(key, destKey);
  return { key, clientId: receipt.clientId, receiptId: receipt.receiptId };
}

// Processes one listPage() page: GET+COPY objects with bounded concurrency
// (S3 has no bulk GET/COPY, so this is the only way to parallelize that
// part), then deletes every successfully archived object in one batched
// DeleteObjects call instead of one DeleteObject per receipt.
async function processPage(store, keys, concurrency, log, emit) {
  const archived = [];
  await runWithConcurrency(keys, concurrency, async (key) => {
    try {
      archived.push(await fetchAndArchive(store, key, log, emit));
    } catch (err) {
      log(`error: failed to process ${key}: ${err.message}`);
      emit({ type: 'receipt-processed', source: 'backend', outcome: 'error', detail: err.message });
    }
  });

  if (archived.length === 0) {
    return;
  }

  const deleteStartedAt = process.hrtime.bigint();
  const errors = await store.deleteMany(archived.map((a) => a.key));
  const deleteDurationMs = elapsedMs(deleteStartedAt);
  const errorByKey = new Map(errors.map((e) => [e.key, e.message]));

  for (const a of archived) {
    const failureMessage = errorByKey.get(a.key);
    if (failureMessage === undefined) {
      emit({ type: 'receipt-archived', source: 'backend', clientId: a.clientId, receiptId: a.receiptId, outcome: 'ok', durationMs: deleteDurationMs });
    } else {
      // Copy already succeeded and is idempotent (same destination key), so a
      // failed delete just means this object gets reprocessed next interval.
      log(`warning: failed to delete ${a.key} after archiving, will reprocess: ${failureMessage}`);
      emit({ type: 'receipt-archived', source: 'backend', clientId: a.clientId, receiptId: a.receiptId, outcome: 'error', detail: 'delete failed, will retry', durationMs: deleteDurationMs });
    }
  }
}

// Drains the whole inbox, one page at a time, tolerating per-object failures
// (a bad object is logged and left for the next interval rather than
// aborting the rest of the page).
export async function processInbox(store, pageSize, log = console.log, emit = noopEmit, concurrency = 20) {
  let continuationToken;
  let totalFound = 0;
  let listDurationMs = 0;
  do {
    const listStartedAt = process.hrtime.bigint();
    const { keys, nextToken } = await store.listPage(INBOX_PREFIX, continuationToken, pageSize);
    listDurationMs += elapsedMs(listStartedAt);
    totalFound += keys.length;
    await processPage(store, keys, concurrency, log, emit);
    continuationToken = nextToken;
  } while (continuationToken);
  emit({ type: 'inbox-poll', source: 'backend', outcome: 'ok', detail: `found ${totalFound} object(s)`, durationMs: listDurationMs });
}
