import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFakeStore } from './fakeStore.js';
import { ensureInitialPublish, CONFIG_KEY, CATALOG_KEY } from '../src/catalogPublisher.js';

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
