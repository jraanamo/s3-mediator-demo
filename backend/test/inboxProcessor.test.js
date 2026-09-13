import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFakeStore } from './fakeStore.js';
import { processInbox, INBOX_PREFIX } from '../src/inboxProcessor.js';

function receipt(id) {
  return {
    receiptId: id,
    clientId: 'device-1',
    date: '2026-01-15T10:00:00.000Z',
    totalAmount: 12.5,
    products: [{ id: 'item-1', name: 'Burger', price: 12.5, quantity: 1 }],
  };
}

test('processInbox drains a multi-page inbox and archives each object', async () => {
  const store = createFakeStore();
  for (let i = 0; i < 5; i++) {
    await store.putJson(`${INBOX_PREFIX}r${i}.json`, receipt(`r${i}`));
  }

  await processInbox(store, /* pageSize */ 2, () => {});

  for (let i = 0; i < 5; i++) {
    assert.ok(!(await store.exists(`${INBOX_PREFIX}r${i}.json`)), `r${i} should be removed from inbox`);
    assert.ok(await store.exists(`archive/device-1/2026/01/15/r${i}.json`), `r${i} should be archived`);
  }
});

test('a receipt with a path-traversal clientId/receiptId is rejected, not archived outside its prefix', async () => {
  const store = createFakeStore();
  await store.putJson(`${INBOX_PREFIX}bad.json`, receipt('../../../etc/passwd'));

  const messages = [];
  await processInbox(store, 10, (msg) => messages.push(msg));

  assert.ok(await store.exists(`${INBOX_PREFIX}bad.json`), 'invalid object should be left in place, not silently dropped');
  assert.ok(messages.some((m) => m.startsWith('error:')), 'should log an error for the invalid receipt');
  const escapedKeys = [...store.objects.keys()].filter((k) => k.includes('..') || k.startsWith('etc/'));
  assert.deepEqual(escapedKeys, [], 'no object should be written outside inbox/archive prefixes');
});

test('processInbox emits receipt-processed and receipt-archived events, and one inbox-poll summary', async () => {
  const store = createFakeStore();
  await store.putJson(`${INBOX_PREFIX}r0.json`, receipt('r0'));

  const events = [];
  await processInbox(store, 10, () => {}, (event) => events.push(event));

  const processed = events.find((e) => e.type === 'receipt-processed');
  const archived = events.find((e) => e.type === 'receipt-archived');
  const poll = events.find((e) => e.type === 'inbox-poll');

  assert.ok(processed, 'a receipt-processed event should be emitted');
  assert.equal(processed.receiptId, 'r0');
  assert.equal(processed.outcome, 'ok');
  assert.equal(typeof processed.durationMs, 'number');

  assert.ok(archived, 'a receipt-archived event should be emitted');
  assert.equal(archived.receiptId, 'r0');
  assert.equal(archived.outcome, 'ok');
  assert.equal(typeof archived.durationMs, 'number');

  assert.ok(poll, 'exactly one inbox-poll summary event should be emitted');
  assert.equal(typeof poll.durationMs, 'number');
  assert.equal(events.filter((e) => e.type === 'inbox-poll').length, 1);
});

test('a failed delete leaves the object for reprocessing without duplicating archive state', async () => {
  const store = createFakeStore();
  await store.putJson(`${INBOX_PREFIX}r0.json`, receipt('r0'));

  const realDelete = store.delete;
  let deleteAttempts = 0;
  store.delete = async (key) => {
    deleteAttempts += 1;
    if (deleteAttempts === 1) throw new Error('simulated delete failure');
    return realDelete(key);
  };

  await processInbox(store, 10, () => {});
  assert.ok(await store.exists(`${INBOX_PREFIX}r0.json`), 'object should remain in inbox after failed delete');
  assert.ok(await store.exists('archive/device-1/2026/01/15/r0.json'), 'archive copy should exist');

  await processInbox(store, 10, () => {});
  assert.ok(!(await store.exists(`${INBOX_PREFIX}r0.json`)), 'object should be gone after reprocessing');
  assert.deepEqual(
    await store.getJson('archive/device-1/2026/01/15/r0.json'),
    receipt('r0'),
    'reprocessing should not corrupt the archived object',
  );
});
