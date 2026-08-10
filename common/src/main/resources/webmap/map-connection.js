/** Owns one browser WebSocket and restores it after transient disconnects. */
export function createReconnectingWebSocket({
  url,
  WebSocketClass = WebSocket,
  setTimer = setTimeout,
  clearTimer = clearTimeout,
  onOpen = () => {},
  onMessage = () => {},
  onDisconnect = () => {},
  onError = () => {},
  minDelayMs = 500,
  maxDelayMs = 10000
}) {
  let socket;
  let reconnectTimer;
  let stopped = false;
  let retry = 0;
  let opened = false;
  let readyState = deferred();

  function connect() {
    if (stopped) return;
    reconnectTimer = undefined;
    const active = new WebSocketClass(url);
    socket = active;
    active.binaryType = 'arraybuffer';
    active.addEventListener('open', () => {
      if (stopped || socket !== active) return;
      opened = true;
      retry = 0;
      readyState.resolve();
      onOpen();
    });
    active.addEventListener('message', event => {
      if (!stopped && socket === active) onMessage(event);
    });
    active.addEventListener('error', error => {
      if (!stopped && socket === active) onError(error);
    });
    active.addEventListener('close', () => {
      if (stopped || socket !== active) return;
      socket = undefined;
      if (opened) readyState = deferred();
      opened = false;
      onDisconnect();
      const delay = Math.min(maxDelayMs, minDelayMs * Math.pow(2, retry++));
      reconnectTimer = setTimer(connect, delay);
    });
  }

  connect();
  return {
    ready() {
      return readyState.promise;
    },
    send(payload) {
      if (!socket || socket.readyState !== 1) {
        throw new Error('map websocket is not open');
      }
      socket.send(payload);
    },
    close() {
      stopped = true;
      if (reconnectTimer !== undefined) clearTimer(reconnectTimer);
      reconnectTimer = undefined;
      socket?.close();
      socket = undefined;
    }
  };
}

function deferred() {
  let resolve;
  const promise = new Promise(next => { resolve = next; });
  return {promise, resolve};
}
