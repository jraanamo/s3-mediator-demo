import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFakeStore } from './fakeStore.js';
import { ensureInitialPublish, publish, CONFIG_KEY, CATALOG_KEY } from '../src/catalogPublisher.js';

test('ensureInitialPublish writes config+catalog when missing', async () => {
  const store = createFakeStore();
  await ensureInitialPublish(store, 'https://backend.test/upload-receipts');
  assert.ok(await store.exists(CONFIG_KEY));
  assert.ok(await store.exists(CATALOG_KEY));
});

test('ensureInitialPublish leaves existing objects untouched', async () => {
  const store = createFakeStore();
  await store.putJson(CONFIG_KEY, { storeName: 'Untouched' });
  await store.putJson(CATALOG_KEY, [{ id: 'x' }]);

  await ensureInitialPublish(store, 'https://backend.test/upload-receipts');

  assert.equal((await store.getJson(CONFIG_KEY)).storeName, 'Untouched');
  assert.deepEqual(await store.getJson(CATALOG_KEY), [{ id: 'x' }]);
});

test('ensureInitialPublish emits catalog-publish only when it actually wrote something', async () => {
  const emittedWhenMissing = [];
  const store = createFakeStore();
  await ensureInitialPublish(store, 'https://backend.test/upload-receipts', (e) => emittedWhenMissing.push(e));
  assert.equal(emittedWhenMissing.length, 1);
  assert.equal(emittedWhenMissing[0].type, 'catalog-publish');
  assert.equal(emittedWhenMissing[0].outcome, 'ok');

  const emittedWhenPresent = [];
  await ensureInitialPublish(store, 'https://backend.test/upload-receipts', (e) => emittedWhenPresent.push(e));
  assert.equal(emittedWhenPresent.length, 0, 'no event should fire when nothing needed writing');
});

test('publish always emits a catalog-publish event', async () => {
  const store = createFakeStore();
  const emitted = [];
  await publish(store, 'https://backend.test/upload-receipts', (e) => emitted.push(e));
  assert.equal(emitted.length, 1);
  assert.equal(emitted[0].type, 'catalog-publish');
  assert.equal(emitted[0].outcome, 'ok');
});
