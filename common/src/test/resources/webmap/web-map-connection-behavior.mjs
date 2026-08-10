import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';

const {createReconnectingWebSocket} = await import(pathToFileURL(process.argv[2]));
const sockets = [];
const timers = [];

class FakeSocket {
  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.listeners = new Map();
    this.sent = [];
    sockets.push(this);
  }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }
  dispatch(type, event = {}) {
    for (const listener of this.listeners.get(type) ?? []) listener(event);
  }
  open() {
    this.readyState = 1;
    this.dispatch('open');
  }
  disconnect() {
    this.readyState = 3;
    this.dispatch('close');
  }
  send(value) { this.sent.push(value); }
  close() { this.readyState = 3; }
}

const connection = createReconnectingWebSocket({
  url: 'ws://127.0.0.1/map',
  WebSocketClass: FakeSocket,
  setTimer: (callback, delay) => {
    const timer = {callback, delay};
    timers.push(timer);
    return timer;
  },
  clearTimer: timer => {
    const index = timers.indexOf(timer);
    if (index >= 0) timers.splice(index, 1);
  }
});

const firstReady = connection.ready();
assert.equal(sockets.length, 1);
sockets[0].open();
await firstReady;
connection.send('first');
assert.deepEqual(sockets[0].sent, ['first']);

sockets[0].disconnect();
assert.equal(timers.length, 1);
assert.equal(timers[0].delay, 500);
const secondReady = connection.ready();
timers.shift().callback();
assert.equal(sockets.length, 2);
sockets[1].open();
await secondReady;
connection.send('second');
assert.deepEqual(sockets[1].sent, ['second']);

connection.close();
assert.equal(timers.length, 0);
console.log('web map reconnect lifecycle: ok');
