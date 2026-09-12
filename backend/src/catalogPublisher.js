export const CONFIG_KEY = 'config/config.json';
export const CATALOG_KEY = 'catalog/catalog.json';

const CATALOG_ITEMS = [
  { id: 'item-1', name: 'Burger', price: 12.5 },
  { id: 'item-2', name: 'Fries', price: 4.5 },
  { id: 'item-3', name: 'Soda', price: 3.0 },
  { id: 'item-4', name: 'Salad', price: 9.0 },
  { id: 'item-5', name: 'Coffee', price: 3.5 },
];

export function synthesizeConfig(uploadReceiptsUrl) {
  return {
    storeName: 'Demo Bistro',
    currency: 'EUR',
    taxRate: 0.14,
    uploadReceiptsUrl,
    generatedAt: new Date().toISOString(),
  };
}

export function synthesizeCatalog() {
  return CATALOG_ITEMS;
}

const noopEmit = () => {};

function elapsedMs(startedAt) {
  return Number(process.hrtime.bigint() - startedAt) / 1_000_000;
}

// Writes config+catalog if missing (so the client always has something to
// fetch), regardless of the interval below.
export async function ensureInitialPublish(store, uploadReceiptsUrl, emit = noopEmit) {
  const startedAt = process.hrtime.bigint();
  let wroteAnything = false;
  if (!(await store.exists(CONFIG_KEY))) {
    await store.putJson(CONFIG_KEY, synthesizeConfig(uploadReceiptsUrl));
    wroteAnything = true;
  }
  if (!(await store.exists(CATALOG_KEY))) {
    await store.putJson(CATALOG_KEY, synthesizeCatalog());
    wroteAnything = true;
  }
  if (wroteAnything) {
    emit({ type: 'catalog-publish', source: 'backend', outcome: 'ok', detail: 'initial publish', durationMs: elapsedMs(startedAt) });
  }
}

// Unconditionally regenerates and overwrites both objects, changing their
// ETag so clients polling with conditional GET see the update.
export async function publish(store, uploadReceiptsUrl, emit = noopEmit) {
  const startedAt = process.hrtime.bigint();
  await store.putJson(CONFIG_KEY, synthesizeConfig(uploadReceiptsUrl));
  await store.putJson(CATALOG_KEY, synthesizeCatalog());
  emit({ type: 'catalog-publish', source: 'backend', outcome: 'ok', durationMs: elapsedMs(startedAt) });
}
