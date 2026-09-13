// In-memory stand-in for the S3-backed store, used to exercise
// CatalogPublisher/InboxProcessor logic without touching real S3.
export function createFakeStore() {
  const objects = new Map();

  return {
    objects,
    async exists(key) {
      return objects.has(key);
    },
    async putJson(key, value) {
      objects.set(key, value);
    },
    async getJson(key) {
      if (!objects.has(key)) throw new Error(`no such key: ${key}`);
      return objects.get(key);
    },
    async copy(sourceKey, destKey) {
      objects.set(destKey, objects.get(sourceKey));
    },
    async delete(key) {
      objects.delete(key);
    },
    async deleteMany(keys) {
      for (const key of keys) objects.delete(key);
      return [];
    },
    async listPage(prefix, continuationToken, maxKeys) {
      // Mirrors real S3: the token is the last key already seen ("StartAfter"),
      // not a position, so it stays valid even if earlier keys are deleted
      // between pages.
      const allKeys = [...objects.keys()].filter((k) => k.startsWith(prefix)).sort();
      const startIndex = continuationToken ? allKeys.findIndex((k) => k > continuationToken) : 0;
      const remaining = startIndex === -1 ? [] : allKeys.slice(startIndex);
      const page = remaining.slice(0, maxKeys);
      return {
        keys: page,
        nextToken: page.length < remaining.length ? page[page.length - 1] : undefined,
      };
    },
  };
}
