import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMonitorHub, validateEvent } from '../src/monitorEvents.js';

function fakeStream(onWrite) {
  return { write: onWrite ?? (() => {}) };
}

test('validateEvent accepts a well-formed event', () => {
  const error = validateEvent({ type: 'login', source: 'backend', clientId: 'device-1', outcome: 'ok' });
  assert.equal(error, null);
});

test('validateEvent rejects an unknown type', () => {
  const error = validateEvent({ type: 'not-a-real-type' });
  assert.equal(error, 'invalid type');
});

test('validateEvent rejects an oversized field', () => {
  const error = validateEvent({ type: 'login', detail: 'x'.repeat(500) });
  assert.equal(error, 'invalid detail');
});

test('validateEvent rejects a non-object body', () => {
  assert.equal(validateEvent(null), 'invalid body');
  assert.equal(validateEvent('nope'), 'invalid body');
  assert.equal(validateEvent(['login']), 'invalid body');
});

test('broadcast writes to every connected stream', () => {
  const hub = createMonitorHub();
  const received = [];
  hub.addStream(fakeStream((frame) => received.push(frame)));
  hub.addStream(fakeStream((frame) => received.push(frame)));

  hub.broadcast({ type: 'login', source: 'backend', clientId: 'device-1', outcome: 'ok' });

  assert.equal(received.length, 2);
  assert.match(received[0], /^data: /);
  assert.match(received[0], /"type":"login"/);
});

test('a stream that throws on write is dropped without affecting other streams', () => {
  const hub = createMonitorHub();
  const received = [];
  const badStream = fakeStream(() => {
    throw new Error('client disconnected');
  });
  hub.addStream(badStream);
  hub.addStream(fakeStream((frame) => received.push(frame)));
  assert.equal(hub.streamCount(), 2);

  hub.broadcast({ type: 'login', source: 'backend', outcome: 'ok' });

  assert.equal(hub.streamCount(), 1, 'the throwing stream should have been removed');
  assert.equal(received.length, 1, 'the healthy stream should still have received the event');
});
