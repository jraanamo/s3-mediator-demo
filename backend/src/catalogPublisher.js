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

// Writes config+catalog if missing (so the client always has something to
// fetch), regardless of the interval below.
export async function ensureInitialPublish(store, uploadReceiptsUrl) {
  if (!(await store.exists(CONFIG_KEY))) {
    await store.putJson(CONFIG_KEY, synthesizeConfig(uploadReceiptsUrl));
  }
  if (!(await store.exists(CATALOG_KEY))) {
    await store.putJson(CATALOG_KEY, synthesizeCatalog());
  }
}

// Unconditionally regenerates and overwrites both objects, changing their
// ETag so clients polling with conditional GET see the update.
export async function publish(store, uploadReceiptsUrl) {
  await store.putJson(CONFIG_KEY, synthesizeConfig(uploadReceiptsUrl));
  await store.putJson(CATALOG_KEY, synthesizeCatalog());
}
