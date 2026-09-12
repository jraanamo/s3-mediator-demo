const EVENT_TYPES = new Set([
  'login',
  'config-check',
  'catalog-check',
  'receipt-uploaded',
  'inbox-poll',
  'receipt-processed',
  'receipt-archived',
  'catalog-publish',
]);
const MAX_FIELD_LENGTH = 200;
const MAX_DURATION_MS = 10 * 60 * 1000; // 10 minutes — generous upper bound, just to catch garbage input
export const MAX_EVENT_BODY_BYTES = 2000;

// Fields allowed on an event body, beyond the required `type`.
const OPTIONAL_STRING_FIELDS = ['clientId', 'receiptId', 'outcome', 'detail'];

export function createMonitorHub() {
  const streams = new Set();

  return {
    addStream(res) {
      streams.add(res);
    },

    removeStream(res) {
      streams.delete(res);
    },

    streamCount() {
      return streams.size;
    },

    broadcast(event) {
      const payload = JSON.stringify({ ...event, timestamp: event.timestamp ?? new Date().toISOString() });
      const frame = `data: ${payload}\n\n`;
      for (const res of streams) {
        try {
          res.write(frame);
        } catch {
          streams.delete(res);
        }
      }
    },
  };
}

// Returns an error message string if invalid, or null if the event is well-formed.
export function validateEvent(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return 'invalid body';
  }
  if (!EVENT_TYPES.has(body.type)) {
    return 'invalid type';
  }
  for (const field of OPTIONAL_STRING_FIELDS) {
    const value = body[field];
    if (value !== undefined && (typeof value !== 'string' || value.length > MAX_FIELD_LENGTH)) {
      return `invalid ${field}`;
    }
  }
  if (body.durationMs !== undefined) {
    if (typeof body.durationMs !== 'number' || !Number.isFinite(body.durationMs) || body.durationMs < 0 || body.durationMs > MAX_DURATION_MS) {
      return 'invalid durationMs';
    }
  }
  return null;
}
