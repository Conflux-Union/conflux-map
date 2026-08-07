const status = document.querySelector('#status');
const dimension = document.querySelector('#dimension');
const mode = document.querySelector('#mode');
const playerList = document.querySelector('#player-list');
const playerCount = document.querySelector('#player-count');
const scaleLabel = document.querySelector('#scale-label');
const messages = {
  connecting: 'Connecting…',
  loadError: 'Map loading failed',
  connected: 'Connected',
  zoomIn: 'Zoom in',
  zoomOut: 'Zoom out',
  scale: 'Scale: {scale}'
};
window.addEventListener('unhandledrejection', () => showLoadError());
window.addEventListener('error', () => showLoadError());
const manifest = await fetch('/api/v1/manifest', {cache: 'no-store'}).then(response => {
  if (!response.ok) throw new Error(`manifest ${response.status}`);
  return response.json();
});

for (const item of manifest.dimensions) {
  const option = document.createElement('option');
  option.value = item.index;
  option.textContent = item.id;
  dimension.append(option);
}
syncModeAvailability();

const ZOOM_STEP = Math.log2(1.26);
const map = L.map('map', {
  crs: L.CRS.Simple,
  minZoom: -4,
  maxZoom: 2,
  zoomSnap: ZOOM_STEP,
  zoomDelta: Math.log2(1.26),
  attributionControl: true
}).setView([0, 0], -1);
map.attributionControl.addAttribution('Conflux Map · Leaflet');
localizeZoomControl();
updateScaleLabel();

const playerMarkers = new Map();
const waypointMarkers = new Map();
let currentMapState = {players: [], waypoints: []};
const PLAYER_MOVE_DURATION_MS = 1800;
const mapSocket = connectMapSocket();
let predictionGeneration = 1;
const predictor = manifest.predictionAvailable ? createPredictor() : null;
let sequence = 1;
let lastRequestAt = 0;
let requestGate = Promise.resolve();
let subscriptionTimer;
let visibleTileSyncTimer;
const pendingRequests = new Map();
const revisions = new Map();
const validated = new Set();
const patchCache = new Map();
const compressedCache = new Map();
const queuedTileRequests = new Map();
const inFlightTiles = new Map();
let tileBatchTimer;
const database = openDatabase();
const palette = [
  '#000000','#7fb238','#f7e9a3','#c7c7c7','#ff0000','#a0a0ff','#a7a7a7','#007c00',
  '#ffffff','#a4a8b8','#976d4d','#707070','#4040ff','#8f7748','#fffcf5','#d87f33',
  '#b24cd8','#6699d8','#e5e533','#7fcc19','#f27fa5','#4c4c4c','#999999','#4c7f99',
  '#7f3fb2','#334cb2','#664c33','#667f33','#993333','#191919','#faee4d','#5cdbd5',
  '#4a80ff','#00d93a','#815631','#700200','#d1b1a1','#9f5224','#95576c','#706c8a',
  '#ba8524','#677535','#a04d4e','#392923','#876b62','#575c5c','#7a4958','#4c3e5c',
  '#4c3223','#4c522a','#8e3c2e','#251610','#bd3031','#943f61','#5c191d','#167e86',
  '#3a8e8c','#562c3e','#14b485','#646464','#d8af93','#7fa796'
];
const NETHER_ROOF_MAP_COLOR_ID = 11;
const predictionBiomes = new Map(
  (manifest.predictionBiomes ?? []).map(entry => [entry.id, entry])
);
const fallbackPredictionBiome = predictionBiomes.get(-1) ?? {
  kind: 'LAND', waterBiome: false, surfaceColor: 0x49763b,
  canopyColor: 0x2f6d1b, waterTint: 0x3f76e4
};

class RegionLayer extends L.GridLayer {
  createTile(coords, done) {
    const canvas = L.DomUtil.create('canvas', 'leaflet-tile map-tile');
    canvas.width = canvas.height = 256;
    const lod = Math.max(0, Math.min(4, -coords.z));
    const generation = predictionGeneration;
    canvas.mapTile = {dim: Number(dimension.value), lod, x: coords.x, z: coords.y, generation};
    canvas.localReady = renderTile(canvas, coords.x, coords.y, lod, generation);
    canvas.localReady.then(() => done(null, canvas), error => done(error, canvas));
    scheduleVisibleTileSync();
    return canvas;
  }
}

let layer = new RegionLayer({
  tileSize: 256,
  minZoom: -4,
  maxZoom: 2,
  minNativeZoom: -4,
  maxNativeZoom: 0,
  noWrap: true,
  updateWhenIdle: true
}).addTo(map);
dimension.addEventListener('change', () => {
  syncModeAvailability();
  refresh();
});
mode.addEventListener('change', refresh);
map.on('moveend zoomend', () => {
  scheduleSubscription();
  scheduleVisibleTileSync();
});
map.on('zoom', updateScaleLabel);
mapSocket.ready.then(() => {
  status.classList.remove('error');
  status.textContent = messages.connected;
  scheduleSubscription();
  scheduleVisibleTileSync();
});
layer.on('tileunload', event => event.tile.cancelPrediction?.());

function refresh() {
  beginPredictionGeneration();
  clearMapMarkers();
  layer.redraw();
  renderMapState(currentMapState);
  scheduleSubscription();
}

async function renderTile(canvas, tileX, tileZ, lod, generation) {
  const renderDimension = Number(dimension.value);
  const renderMode = mode.value;
  const cacheKey = tileKey(renderDimension, lod, tileX, tileZ);
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  const cached = restoreRegion(cacheKey);
  if (renderMode === 'all') {
    try {
      await drawPrediction(canvas, ctx, tileX, tileZ, lod, renderDimension, generation);
    } catch (error) {
      console.error('Map prediction failed', error);
      showLoadError();
    }
  }
  await cached;
  if (!tileStillCurrent(canvas, renderDimension, lod, tileX, tileZ, generation)) return;
  await drawCachedTile(ctx, cacheKey, lod, renderDimension);
}

async function syncTile(canvas, tileX, tileZ, lod, generation, force = false) {
  const dimIndex = Number(dimension.value);
  const cacheKey = tileKey(dimIndex, lod, tileX, tileZ);
  if (!force && validated.has(cacheKey)) {
    return;
  }
  const patchPromise = requestTile(tileX, tileZ, lod, dimIndex);
  await canvas.localReady;
  const patch = await patchPromise;
  if (!tileStillCurrent(canvas, dimIndex, lod, tileX, tileZ, generation)) return;
  if (patch.mode === 3) {
    await discardRegion(cacheKey);
    validated.add(cacheKey);
    return;
  }
  const compressed = patch.mode === 0 ? compressedCache.get(cacheKey) : patch.body;
  const decoded = compressed ? await decodeTilePatch(compressed) : null;
  if (decoded) {
    patchCache.set(cacheKey, decoded);
    drawPatch(
      canvas.getContext('2d'), decoded, 0, 0, lod, isNetherDimension(dimIndex)
    );
  }
  revisions.set(cacheKey, patch.revision);
  if (patch.mode !== 0 && patch.body.length) {
    compressedCache.set(cacheKey, patch.body);
    persistRegion(cacheKey, patch.revision, patch.body);
  }
  validated.add(cacheKey);
}

function scheduleVisibleTileSync() {
  clearTimeout(visibleTileSyncTimer);
  visibleTileSyncTimer = setTimeout(() => {
    const activeLod = Math.max(0, Math.min(4, -Math.round(map.getZoom())));
    for (const canvas of layer.getContainer().querySelectorAll('canvas.map-tile')) {
      const tile = canvas.mapTile;
      if (!tile || tile.lod !== activeLod || !tileStillCurrent(
        canvas, tile.dim, tile.lod, tile.x, tile.z, tile.generation
      )) continue;
      syncTile(canvas, tile.x, tile.z, tile.lod, tile.generation).catch(error => {
        console.warn('Map correction sync failed', error);
        scheduleVisibleTileSync();
      });
    }
  }, 500);
}

async function drawCachedTile(ctx, cacheKey, lod, dimIndex) {
  let decoded = patchCache.get(cacheKey);
  if (!decoded) {
    const compressed = compressedCache.get(cacheKey);
    if (compressed) {
      decoded = await decodeTilePatch(compressed);
      patchCache.set(cacheKey, decoded);
    }
  }
  if (!decoded) return false;
  drawPatch(ctx, decoded, 0, 0, lod, isNetherDimension(dimIndex));
  return true;
}

function requestTile(tileX, tileZ, lod, dimIndex) {
  const cacheKey = tileKey(dimIndex, lod, tileX, tileZ);
  const existing = inFlightTiles.get(cacheKey);
  if (existing) return existing;
  const request = new Promise((resolve, reject) => {
    queuedTileRequests.set(cacheKey, {tileX, tileZ, lod, dimIndex, resolve, reject});
    clearTimeout(tileBatchTimer);
    tileBatchTimer = setTimeout(flushTileRequests, 20);
  });
  inFlightTiles.set(cacheKey, request);
  request.then(
    () => inFlightTiles.delete(cacheKey),
    () => inFlightTiles.delete(cacheKey)
  );
  return request;
}

async function flushTileRequests() {
  const queued = [...queuedTileRequests.values()];
  queuedTileRequests.clear();
  const groups = new Map();
  for (const item of queued) {
    const key = `${item.dimIndex}:${item.lod}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(item);
  }
  for (const group of groups.values()) {
    for (let offset = 0; offset < group.length; offset += 8) {
      const batch = group.slice(offset, offset + 8);
      requestTileBatch(batch).then(patches => {
        for (const item of batch) {
          const patch = patches.find(candidate =>
            candidate.tileX === item.tileX && candidate.tileZ === item.tileZ
          );
          if (patch) item.resolve(patch);
          else item.reject(new Error('tile response missing'));
        }
      }, error => {
        for (const item of batch) item.reject(error);
      });
    }
  }
}

async function requestTileBatch(tiles) {
  await mapSocket.ready;
  await enterRequestGate();
  const requestId = sequence++;
  const bytes = new Uint8Array(8 + tiles.length * 16);
  const view = new DataView(bytes.buffer);
  let p = 0;
  view.setUint8(p++, 0x03);
  view.setInt32(p, requestId); p += 4;
  view.setUint8(p++, tiles[0].dimIndex);
  view.setUint8(p++, tiles[0].lod);
  view.setUint8(p++, tiles.length);
  for (const tile of tiles) {
    view.setInt32(p, tile.tileX); p += 4;
    view.setInt32(p, tile.tileZ); p += 4;
    view.setBigInt64(
      p,
      revisions.get(tileKey(tile.dimIndex, tile.lod, tile.tileX, tile.tileZ))
        ?? -9223372036854775808n
    ); p += 8;
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pendingRequests.delete(requestId);
      reject(new Error('tile response timed out'));
    }, 30000);
    pendingRequests.set(requestId, {expected: tiles.length, patches: [], resolve, reject, timer});
    mapSocket.socket.send(bytes);
  });
}

function connectMapSocket() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(`${protocol}//${location.host}/api/v1/map`);
  socket.binaryType = 'arraybuffer';
  let open;
  const ready = new Promise((resolve, reject) => { open = {resolve, reject}; });
  socket.addEventListener('open', () => {
    open.resolve();
    setInterval(() => {
      if (socket.readyState === WebSocket.OPEN) socket.send('ping');
    }, 25000);
  }, {once: true});
  socket.addEventListener('message', event => {
    if (typeof event.data === 'string') {
      if (event.data !== 'pong') updateMapState(JSON.parse(event.data));
      return;
    }
    handleMapFrame(new Uint8Array(event.data));
  });
  socket.addEventListener('close', () => {
    const error = new Error('map websocket closed');
    open.reject(error);
    for (const pending of pendingRequests.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    pendingRequests.clear();
    showLoadError();
  });
  socket.addEventListener('error', () => showLoadError());
  return {socket, ready};
}

function handleMapFrame(bytes) {
  const frame = new Reader(bytes);
  const type = frame.u8();
  if (type === 0x04) {
    const patch = decodeTileFrame(frame);
    const pending = pendingRequests.get(patch.reqId);
    if (!pending) return;
    pending.patches.push(patch);
    if (pending.patches.length === pending.expected) {
      clearTimeout(pending.timer);
      pendingRequests.delete(patch.reqId);
      pending.resolve(pending.patches);
    }
  } else if (type === 0x0f) {
    invalidateRegions(frame);
  } else if (type === 0x06) {
    const error = new Error('map request was rejected');
    for (const pending of pendingRequests.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    pendingRequests.clear();
    showLoadError();
  }
}

function decodeTileFrame(frame) {
  const reqId = frame.i32();
  const dim = frame.u8(), lod = frame.u8();
  const tileX = frame.i32(), tileZ = frame.i32();
  const mode = frame.u8();
  const revision = frame.i64();
  frame.bytes(32);
  const structures = frame.u8();
  frame.bytes(structures * 10);
  const body = frame.bytes(frame.u32());
  return {reqId, dim, lod, tileX, tileZ, mode, revision, body};
}

function invalidateRegions(frame) {
  const dim = frame.u8(), lod = frame.u8(), count = frame.u16();
  const invalidTiles = new Set();
  const regionsPerTile = 1 << lod;
  for (let i = 0; i < count; i++) {
    const tileX = Math.floor(frame.i32() / regionsPerTile);
    const tileZ = Math.floor(frame.i32() / regionsPerTile);
    const cacheKey = tileKey(dim, lod, tileX, tileZ);
    invalidTiles.add(cacheKey);
    validated.delete(cacheKey);
    revisions.delete(cacheKey);
    patchCache.delete(cacheKey);
    compressedCache.delete(cacheKey);
    deletePersistedRegion(cacheKey);
  }
  if (dim !== Number(dimension.value)) return;
  for (const canvas of layer.getContainer().querySelectorAll('canvas.map-tile')) {
    const tile = canvas.mapTile;
    if (!tile || !invalidTiles.has(tileKey(tile.dim, tile.lod, tile.x, tile.z))) continue;
    syncTile(canvas, tile.x, tile.z, tile.lod, tile.generation, true).catch(error => {
      console.warn('Map correction refresh failed', error);
      scheduleVisibleTileSync();
    });
  }
}

function scheduleSubscription() {
  clearTimeout(subscriptionTimer);
  subscriptionTimer = setTimeout(sendSubscription, 500);
}

async function sendSubscription() {
  await mapSocket.ready;
  const bounds = map.getBounds();
  const minChunkX = Math.floor(bounds.getWest() / 16);
  const maxChunkX = Math.floor(bounds.getEast() / 16);
  const minChunkZ = Math.floor(-bounds.getNorth() / 16);
  const maxChunkZ = Math.floor(-bounds.getSouth() / 16);
  if (maxChunkX - minChunkX >= 4096 || maxChunkZ - minChunkZ >= 4096) return;
  const lod = Math.max(0, Math.min(4, -Math.round(map.getZoom())));
  const bytes = new Uint8Array(20);
  const view = new DataView(bytes.buffer);
  let p = 0;
  view.setUint8(p++, 0x0e);
  view.setUint8(p++, Number(dimension.value));
  view.setUint8(p++, lod);
  view.setUint8(p++, 1);
  view.setInt32(p, minChunkX); p += 4;
  view.setInt32(p, maxChunkX); p += 4;
  view.setInt32(p, minChunkZ); p += 4;
  view.setInt32(p, maxChunkZ);
  await enterRequestGate();
  mapSocket.socket.send(bytes);
}

function enterRequestGate() {
  requestGate = requestGate.then(async () => {
    const delay = 125 - (performance.now() - lastRequestAt);
    if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay));
    lastRequestAt = performance.now();
  });
  return requestGate;
}

function updateMapState(message) {
  if (message.type !== 'map-state' && message.type !== 'players') return;
  currentMapState = message.snapshot;
  updatePlayerList(currentMapState.players ?? []);
  updatePlayers(currentMapState.players ?? []);
  updateWaypoints(currentMapState.waypoints ?? []);
}

function updatePlayers(players) {
  const visible = new Set();
  for (const player of players) {
    if (player.dimension !== Number(dimension.value)) continue;
    visible.add(player.id);
    let marker = playerMarkers.get(player.id);
    if (!marker) {
      marker = L.marker([-player.z, player.x], {icon: playerIcon(player)})
        .bindTooltip(player.name).addTo(map);
      playerMarkers.set(player.id, marker);
    } else {
      animatePlayerMarker(marker, [-player.z, player.x]);
      marker.getElement()?.querySelector('.web-player')
        ?.classList.toggle('translucent', player.translucent);
    }
  }
  for (const [id, marker] of playerMarkers) if (!visible.has(id)) {
    removePlayerMarker(marker); playerMarkers.delete(id);
  }
}

function updatePlayerList(players) {
  playerCount.textContent = String(players.length);
  playerList.replaceChildren();
  if (!players.length) {
    const empty = document.createElement('p');
    empty.className = 'empty-state';
    empty.textContent = 'No online players';
    playerList.append(empty);
    return;
  }
  for (const player of [...players].sort((a, b) => a.name.localeCompare(b.name))) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'player-row';
    button.title = `Go to ${player.name}`;
    button.addEventListener('click', () => focusLocation(player));
    const avatar = document.createElement('span');
    avatar.className = 'player-list-avatar';
    const avatarImage = document.createElement('img');
    avatarImage.alt = '';
    avatarImage.src = `/api/v1/avatars/${player.id}.png`;
    avatarImage.addEventListener('load', () => avatar.classList.add('image-loaded'));
    avatarImage.addEventListener('error', () => {
      if (avatarImage.src.includes('/api/v1/avatars/')) {
        avatarImage.src = Math.random() < 0.5 ? '/default-steve.svg' : '/default-alex.svg';
      } else {
        avatar.classList.remove('image-loaded');
      }
    });
    const avatarFallback = document.createElement('span');
    avatarFallback.textContent = player.name.slice(0, 1).toUpperCase();
    avatar.append(avatarImage, avatarFallback);
    const details = document.createElement('span');
    details.className = 'player-details';
    const name = document.createElement('strong');
    name.textContent = player.name;
    const location = document.createElement('small');
    location.textContent = `${dimensionName(player.dimension)} · ${formatCoordinate(player.x)}, ${formatCoordinate(player.z)}`;
    details.append(name, location);
    button.append(avatar, details);
    playerList.append(button);
  }
}

function updateWaypoints(waypoints) {
  const visible = new Set();
  for (const waypoint of waypoints) {
    if (waypoint.dimension !== Number(dimension.value)) continue;
    visible.add(waypoint.id);
    const target = [-waypoint.z, waypoint.x];
    let marker = waypointMarkers.get(waypoint.id);
    if (!marker) {
      marker = L.marker(target, {icon: waypointIcon(waypoint)})
        .bindTooltip(waypointLabel(waypoint)).addTo(map);
      waypointMarkers.set(waypoint.id, marker);
    } else {
      marker.setLatLng(target);
      marker.setTooltipContent(waypointLabel(waypoint));
      marker.setIcon(waypointIcon(waypoint));
    }
  }
  for (const [id, marker] of waypointMarkers) if (!visible.has(id)) {
    marker.remove(); waypointMarkers.delete(id);
  }
}

function focusLocation(location) {
  if (location.dimension !== Number(dimension.value)) {
    dimension.value = String(location.dimension);
    syncModeAvailability();
    refresh();
  }
  map.setView([-location.z, location.x], map.getZoom());
}

function dimensionName(index) {
  return manifest.dimensions.find(item => item.index === index)?.type
    ?? manifest.dimensions.find(item => item.index === index)?.id
    ?? `Dimension ${index}`;
}

function formatCoordinate(value) {
  return Math.round(value).toString();
}

function waypointLabel(waypoint) {
  const label = document.createElement('span');
  label.textContent = `${waypoint.name} · ${formatCoordinate(waypoint.x)}, ${formatCoordinate(waypoint.y)}, ${formatCoordinate(waypoint.z)}`;
  return label;
}

function waypointIcon(waypoint) {
  const root = document.createElement('div');
  root.className = `web-waypoint${waypoint.type === 'DEATH' ? ' death' : ''}`;
  const color = (waypoint.colorArgb >>> 0) & 0xffffff;
  root.style.backgroundColor = `#${color.toString(16).padStart(6, '0')}`;
  root.textContent = waypoint.type === 'DEATH' ? '✕' : '◆';
  return L.divIcon({className: '', html: root, iconSize: [24, 24], iconAnchor: [12, 12]});
}

function clearMapMarkers() {
  for (const marker of playerMarkers.values()) removePlayerMarker(marker);
  for (const marker of waypointMarkers.values()) marker.remove();
  playerMarkers.clear();
  waypointMarkers.clear();
}

function animatePlayerMarker(marker, target, duration = PLAYER_MOVE_DURATION_MS) {
  if (marker.playerMoveFrame != null) cancelAnimationFrame(marker.playerMoveFrame);
  const start = marker.getLatLng();
  const deltaLat = target[0] - start.lat;
  const deltaLng = target[1] - start.lng;
  if (duration <= 0 || (deltaLat === 0 && deltaLng === 0)) {
    marker.setLatLng(target);
    marker.playerMoveFrame = null;
    return;
  }
  const startedAt = performance.now();
  const frame = now => {
    const progress = Math.max(0, Math.min(1, (now - startedAt) / duration));
    marker.setLatLng([
      start.lat + deltaLat * progress,
      start.lng + deltaLng * progress
    ]);
    marker.playerMoveFrame = progress < 1 ? requestAnimationFrame(frame) : null;
  };
  marker.playerMoveFrame = requestAnimationFrame(frame);
}

function removePlayerMarker(marker) {
  if (marker.playerMoveFrame != null) cancelAnimationFrame(marker.playerMoveFrame);
  marker.remove();
}

function createPredictor() {
  const states = Array.from(
    {length: Math.max(1, Math.min(8, navigator.hardwareConcurrency ?? 2))},
    () => ({worker: null, active: null})
  );
  let id = 1;
  let generation = predictionGeneration;
  const pending = new Map();
  const queue = [];
  for (const state of states) startWorker(state);
  function startWorker(state) {
    const worker = new Worker('/predictor-worker.js');
    state.worker = worker;
    worker.addEventListener('message', event => {
      if (state.worker !== worker) return;
      const request = pending.get(event.data.id);
      state.active = null;
      if (request) {
        pending.delete(event.data.id);
        if (request.cancelled) request.resolve(null);
        else if (event.data.error) request.reject(new Error(event.data.error));
        else request.resolve({
          biomes: new Int32Array(event.data.biomes),
          heights: new Int32Array(event.data.heights),
          surfaces: new Int32Array(event.data.surfaces),
          canopies: new Int32Array(event.data.canopies),
          subBiomes: new Int32Array(event.data.subBiomes),
          subSurfaces: new Int32Array(event.data.subSurfaces),
          subCanopies: new Int32Array(event.data.subCanopies)
        });
      }
      dispatch();
    });
    worker.addEventListener('error', error => {
      if (state.worker !== worker) return;
      if (state.active) {
        pending.delete(state.active.id);
        state.active.reject(error);
        state.active = null;
      }
      dispatch();
    });
  }
  function cancel(request) {
    if (request.cancelled) return;
    request.cancelled = true;
    if (!request.started) {
      const index = queue.indexOf(request);
      if (index >= 0) queue.splice(index, 1);
      pending.delete(request.id);
      request.resolve(null);
      return;
    }
    const state = states.find(item => item.active === request);
    if (state) {
      state.worker.terminate();
      state.active = null;
      pending.delete(request.id);
      request.resolve(null);
      startWorker(state);
      dispatch();
    }
  }
  function dispatch() {
    for (const state of states) {
      if (state.active) continue;
      let request;
      while ((request = queue.shift())?.cancelled) {
        pending.delete(request.id);
        request.resolve(null);
      }
      if (!request) return;
      request.started = true;
      state.active = request;
      state.worker.postMessage({...request.data, id: request.id});
    }
  }
  return {
    beginGeneration(nextGeneration) {
      generation = nextGeneration;
      for (const request of pending.values()) {
        if (request.generation < generation) cancel(request);
      }
    },
    request(data, requestGeneration, canvas) {
      return new Promise((resolve, reject) => {
        if (requestGeneration !== generation) {
          resolve(null);
          return;
        }
        const requestId = id++;
        const request = {
          id: requestId, data, resolve, reject,
          generation: requestGeneration, started: false, cancelled: false
        };
        pending.set(requestId, request);
        queue.push(request);
        canvas.cancelPrediction = () => cancel(request);
        dispatch();
      });
    }
  };
}

function beginPredictionGeneration() {
  predictionGeneration++;
  predictor?.beginGeneration(predictionGeneration);
}

async function drawPrediction(canvas, ctx, tileX, tileZ, lod, dimIndex, generation) {
  const selected = manifest.dimensions.find(item => item.index === dimIndex);
  if (!predictor || !selected?.predictable) return false;
  const nativeDimension = selected.id === 'minecraft:the_nether' ? -1
    : selected.id === 'minecraft:the_end' ? 1 : 0;
  const span = 256 << lod;
  const request = {
    version: manifest.predictionVersion,
    seed: manifest.seed,
    dimension: nativeDimension,
    flags: selected.preset === 'LARGE_BIOMES' ? 1 : 0,
    blockX: tileX * span,
    blockZ: tileZ * span, lod
  };
  let predicted = await predictor.request({...request, exact: false}, generation, canvas);
  if (!predicted || !canvas.isConnected || generation !== predictionGeneration) return false;
  drawPredictedTile(ctx, predicted, lod, isNetherDimension(dimIndex));
  if ((nativeDimension === 0 && lod <= 1) || nativeDimension === 1) {
    predicted = await predictor.request({...request, exact: true}, generation, canvas);
    if (!predicted || !canvas.isConnected || generation !== predictionGeneration) return false;
    drawPredictedTile(ctx, predicted, lod, isNetherDimension(dimIndex));
  }
  return true;
}

function drawPredictedTile(ctx, predicted, lod, netherRoof) {
  const image = ctx.createImageData(256, 256);
  const size = 258;
  for (let z = 0; z < 256; z++) for (let x = 0; x < 256; x++) {
    const gridIndex = (z + 1) * size + x + 1;
    const color = predictedColor(predicted, gridIndex, lod, netherRoof);
    const pixel = (z * 256 + x) * 4;
    if (color < 0) {
      image.data[pixel + 3] = 0;
      continue;
    }
    image.data[pixel] = color >> 16;
    image.data[pixel + 1] = (color >> 8) & 255;
    image.data[pixel + 2] = color & 255;
    image.data[pixel + 3] = 255;
  }
  ctx.putImageData(image, 0, 0);
}

function predictedColor(predicted, index, lod, netherRoof) {
  if (netherRoof) return applyNetherLight(mapColor(NETHER_ROOF_MAP_COLOR_ID), 0);
  if ((predicted.surfaces[index] & 2) !== 0) return -1;
  const biome = predictionBiomes.get(predicted.biomes[index]) ?? fallbackPredictionBiome;
  const water = (predicted.surfaces[index] & 1) !== 0;
  const depth = predicted.surfaces[index] >>> 8;
  const surfaceHeight = water ? 62 : predicted.heights[index] + predicted.canopies[index];
  let color = predicted.subBiomes.length
    ? averagedSubColor(predicted, index)
    : water ? predictedWaterColor(biome, depth, predicted, index, lod)
      : predicted.canopies[index] > 0 ? biome.canopyColor : biome.surfaceColor;
  color = applyHeightShade(color, surfaceHeight);
  return applyBrightness(color, reliefMultiplier(predicted, index, lod, false));
}

function averagedSubColor(predicted, index) {
  let red = 0, green = 0, blue = 0;
  for (let sample = 0; sample < 4; sample++) {
    const subIndex = index * 4 + sample;
    const biome = predictionBiomes.get(predicted.subBiomes[subIndex])
      ?? fallbackPredictionBiome;
    const surface = predicted.subSurfaces[subIndex];
    const color = (surface & 1) !== 0
      ? predictedWaterBaseColor(biome, surface >>> 8, 1)
      : predicted.subCanopies[subIndex] > 0 ? biome.canopyColor : biome.surfaceColor;
    red += color >> 16;
    green += (color >> 8) & 255;
    blue += color & 255;
  }
  return Math.floor(red / 4) << 16 | Math.floor(green / 4) << 8
    | Math.floor(blue / 4);
}

function predictedWaterColor(biome, depth, predicted, index, lod) {
  const floorRelief = reliefMultiplier(predicted, index, lod, true);
  return predictedWaterBaseColor(biome, depth, floorRelief);
}

function predictedWaterBaseColor(biome, depth, floorRelief) {
  const tint = biome.waterBiome ? 0x3f76e4 : biome.waterTint;
  const water = multiplyColor(0xcfe0f2, tint);
  const floorBrightness = Math.max(0.25, 1 - depth / 48) * floorRelief;
  const floor = applyBrightness(0xc2a876, floorBrightness);
  return blendOver(floor, water, 0xcc);
}

function reliefMultiplier(predicted, index, lod, floorPlane) {
  if (lod === 0) {
    if (predictionVoid(predicted, index + 257) || predictionVoid(predicted, index)) return 1;
    const lit = predictionHeight(predicted, index + 257, floorPlane);
    const center = predictionHeight(predicted, index, floorPlane);
    const rise = (lit - center) / 2;
    return 1 + 0.3 * Math.max(-1, Math.min(1, rise));
  }
  const neighbors = [index - 1, index + 258, index + 257,
    index + 1, index - 258, index - 257];
  if (neighbors.some(neighbor => predictionVoid(predicted, neighbor))) return 1;
  const lit = (
    predictionHeight(predicted, index - 1, floorPlane)
    + predictionHeight(predicted, index + 258, floorPlane)
    + predictionHeight(predicted, index + 257, floorPlane)
  ) / 3;
  const dark = (
    predictionHeight(predicted, index + 1, floorPlane)
    + predictionHeight(predicted, index - 258, floorPlane)
    + predictionHeight(predicted, index - 257, floorPlane)
  ) / 3;
  const rise = (lit - dark) / (2 * (1 << lod));
  return 1 + 0.3 * Math.max(-1, Math.min(1, rise));
}

function predictionVoid(predicted, index) {
  return (predicted.surfaces[index] & 2) !== 0;
}

function predictionHeight(predicted, index, floorPlane) {
  const water = (predicted.surfaces[index] & 1) !== 0;
  if (water) return floorPlane ? predicted.heights[index] : 62;
  return predicted.heights[index] + predicted.canopies[index];
}

function applyHeightShade(color, height) {
  const difference = height - 80;
  const shade = Math.log10(Math.abs(difference) / 8 + 1) / 3 * 0.65;
  return applyShade(color, difference < 0 ? -shade : shade);
}

function applyShade(color, shade) {
  return shadeColor(
    color >> 16, shade
  ) << 16 | shadeColor((color >> 8) & 255, shade) << 8
    | shadeColor(color & 255, shade);
}

function shadeColor(channel, shade) {
  return clampColor(Math.round(shade > 0
    ? channel + shade * (255 - channel) : channel * (1 + shade)));
}

function applyBrightness(color, factor) {
  return clampColor(Math.round((color >> 16) * factor)) << 16
    | clampColor(Math.round(((color >> 8) & 255) * factor)) << 8
    | clampColor(Math.round((color & 255) * factor));
}

function multiplyColor(base, tint) {
  return Math.floor((base >> 16) * (tint >> 16) / 255) << 16
    | Math.floor(((base >> 8) & 255) * ((tint >> 8) & 255) / 255) << 8
    | Math.floor((base & 255) * (tint & 255) / 255);
}

function blendOver(bottom, top, alpha) {
  const inverse = 255 - alpha;
  return Math.floor(((top >> 16) * alpha + (bottom >> 16) * inverse) / 255) << 16
    | Math.floor((((top >> 8) & 255) * alpha + ((bottom >> 8) & 255) * inverse) / 255) << 8
    | Math.floor(((top & 255) * alpha + (bottom & 255) * inverse) / 255);
}

function clampColor(channel) {
  return Math.max(0, Math.min(255, channel));
}

async function decodeTilePatch(compressed) {
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('deflate'));
  const raw = new Uint8Array(await new Response(stream).arrayBuffer());
  const r = new Reader(raw);
  const version = r.u8();
  if (version < 3 || version > 5) throw new Error(`unsupported tile codec ${version}`);
  const evaluated = readSparseTileMask(r);
  const difference = readSparseTileMask(r);
  const indexes = bits(difference);
  const count = indexes.length;
  const biomes = r.bytes(count);
  const heights = new Int32Array(count);
  let height = 0;
  for (let i = 0; i < count; i++) { height += r.zigzag(); heights[i] = height; }
  const kinds = r.bytes(count), colors = r.bytes(count), fluids = r.bytes(count);
  const floorColors = r.bytes(count);
  const materialIds = new Array(count).fill('');
  const floorMaterialIds = new Array(count).fill('');
  if (version >= 5) {
    const decoder = new TextDecoder();
    const materials = new Array(r.u16());
    for (let i = 0; i < materials.length; i++) {
      materials[i] = decoder.decode(r.bytes(r.u16()));
    }
    readMaterialPlane(r, materials, materialIds);
    readMaterialPlane(r, materials, floorMaterialIds);
  }
  const lights = new Uint8Array(evaluated.length * 8);
  if (version >= 4) {
    const evaluatedIndexes = bits(evaluated);
    for (let i = 0; i < evaluatedIndexes.length; i++) r.zigzagLong();
    for (const index of evaluatedIndexes) lights[index] = r.u8();
  }
  return {
    width: 256, height: 256, indexes, biomes, heights, kinds, colors, fluids,
    floorColors, materialIds, floorMaterialIds, lights
  };
}

function readMaterialPlane(r, materials, output) {
  for (let i = 0; i < output.length; i++) {
    const index = r.u16();
    if (index >= materials.length) throw new Error(`material index ${index} out of range`);
    output[i] = materials[index];
  }
}

function drawPatch(ctx, patch, offsetX, offsetZ, lod = 0, nether = false) {
  const image = ctx.getImageData(offsetX, offsetZ, patch.width, patch.height);
  const pixels = patch.width * patch.height;
  const biomes = new Uint8Array(pixels);
  const heights = new Int32Array(pixels);
  const kinds = new Uint8Array(pixels);
  const colors = new Uint8Array(pixels).fill(255);
  const fluids = new Uint8Array(pixels);
  const floorColors = new Uint8Array(pixels).fill(255);
  const present = new Uint8Array(pixels);
  for (let i = 0; i < patch.indexes.length; i++) {
    const pixel = patch.indexes[i];
    biomes[pixel] = patch.biomes[i];
    heights[pixel] = patch.heights[i];
    kinds[pixel] = patch.kinds[i];
    colors[pixel] = patch.colors[i];
    fluids[pixel] = patch.fluids[i];
    floorColors[pixel] = patch.floorColors?.[i] ?? 255;
    present[pixel] = 1;
  }
  const planes = {
    width: patch.width, height: patch.height, biomes, heights, kinds, colors,
    fluids, floorColors, present
  };
  for (const pixel of patch.indexes) {
    const kind = kinds[pixel];
    if (kind === 0 || kind === 9) continue;
    let color = authoritativeBaseColor(planes, pixel, lod);
    if (!nether) color = applyHeightShade(color, heights[pixel]);
    color = applyBrightness(color, authoritativeRelief(planes, pixel, lod, false));
    if (nether) color = applyNetherLight(color, patch.lights[pixel]);
    image.data[pixel * 4] = color >> 16;
    image.data[pixel * 4 + 1] = (color >> 8) & 255;
    image.data[pixel * 4 + 2] = color & 255;
    image.data[pixel * 4 + 3] = 255;
  }
  ctx.putImageData(image, offsetX, offsetZ);
}

function authoritativeBaseColor(planes, pixel, lod) {
  const biome = predictionBiomes.get(planes.biomes[pixel]) ?? fallbackPredictionBiome;
  if (planes.kinds[pixel] === 2) {
    const tint = biome.waterBiome ? 0x3f76e4 : biome.waterTint;
    const water = multiplyColor(0xcfe0f2, tint);
    const floorId = planes.floorColors[pixel];
    const floorBase = paintsLiteralMapColor(floorId)
      ? mapColor(floorId) : 0xc2a876;
    const depthBrightness = Math.max(0.25, 1 - planes.fluids[pixel] / 48);
    const floor = applyBrightness(
      scaleColor(floorBase, depthBrightness),
      authoritativeRelief(planes, pixel, lod, true)
    );
    return blendOver(floor, water, 0xcc);
  }
  const mapColorId = planes.colors[pixel];
  if (paintsLiteralMapColor(mapColorId)) return mapColor(mapColorId);
  return authoritativeKindColor(planes.kinds[pixel], biome);
}

function authoritativeKindColor(kind, biome) {
  if (kind === 8) return mapColor(NETHER_ROOF_MAP_COLOR_ID);
  if (kind === 4) return biome.canopyColor;
  if (kind === 5) return 0xf7faff;
  if (kind === 6) return 0xa4c6e8;
  if (kind === 7) return 0xddce9b;
  return biome.surfaceColor;
}

function authoritativeRelief(planes, pixel, lod, floorPlane) {
  const x = pixel % planes.width;
  const z = Math.floor(pixel / planes.width);
  if (lod === 0) {
    const lit = authoritativeHeight(planes, x - 1, z + 1, floorPlane);
    const center = authoritativeHeight(planes, x, z, floorPlane);
    if (lit === null || center === null) return 1;
    return 1 + 0.3 * Math.max(-1, Math.min(1, (lit - center) / 2));
  }
  const lit = [
    authoritativeHeight(planes, x - 1, z, floorPlane),
    authoritativeHeight(planes, x, z + 1, floorPlane),
    authoritativeHeight(planes, x - 1, z + 1, floorPlane)
  ];
  const dark = [
    authoritativeHeight(planes, x + 1, z, floorPlane),
    authoritativeHeight(planes, x, z - 1, floorPlane),
    authoritativeHeight(planes, x + 1, z - 1, floorPlane)
  ];
  if ([...lit, ...dark].some(height => height === null)) return 1;
  const litMean = (lit[0] + lit[1] + lit[2]) / 3;
  const darkMean = (dark[0] + dark[1] + dark[2]) / 3;
  const rise = (litMean - darkMean) / (2 * (1 << lod));
  return 1 + 0.3 * Math.max(-1, Math.min(1, rise));
}

function authoritativeHeight(planes, x, z, floorPlane) {
  if (x < 0 || x >= planes.width || z < 0 || z >= planes.height) return null;
  const pixel = z * planes.width + x;
  const kind = planes.kinds[pixel];
  if (!planes.present[pixel] || kind === 0 || kind === 9) return null;
  return planes.heights[pixel] - (floorPlane && kind === 2 ? planes.fluids[pixel] : 0);
}

function paintsLiteralMapColor(id) { return id !== 255 && id !== 1 && id !== 7; }
function mapColor(id) {
  const value = palette[id];
  return value ? parseInt(value.slice(1), 16) : 0x969696;
}

function scaleColor(color, factor) {
  return Math.floor((color >> 16) * factor) << 16
    | Math.floor(((color >> 8) & 255) * factor) << 8
    | Math.floor((color & 255) * factor);
}

function applyNetherLight(color, blockLevel) {
  const ambient = netherLightTint(0);
  const base = multiplyColor(color, ambient);
  if (!blockLevel) return base;
  const lit = netherLightTint(blockLevel);
  return Math.min(255, Math.round((base >> 16) * ((lit >> 16) / (ambient >> 16)))) << 16
    | Math.min(255, Math.round(((base >> 8) & 255) * (((lit >> 8) & 255) / ((ambient >> 8) & 255)))) << 8
    | Math.min(255, Math.round((base & 255) * ((lit & 255) / (ambient & 255))));
}

function netherLightTint(blockLevel) {
  const strength = (blockLevel / 15) / (4 - 3 * (blockLevel / 15)) * 1.5;
  let red = strength;
  let green = strength * ((strength * 0.6 + 0.4) * 0.6 + 0.4);
  let blue = strength * (strength * strength * 0.6 + 0.4);
  red = mix(red, 1, 0.1);
  green = mix(green, 1, 0.1);
  blue = mix(blue, 1, 0.1);
  red = mix(0.3 + red * 0.7, 0.75, 0.04);
  green = mix(0.3 + green * 0.7, 0.75, 0.04);
  blue = mix(0.3 + blue * 0.7, 0.75, 0.04);
  return clampColor(Math.round(Math.min(1, red) * 255)) << 16
    | clampColor(Math.round(Math.min(1, green) * 255)) << 8
    | clampColor(Math.round(Math.min(1, blue) * 255));
}

function mix(value, target, amount) { return value + (target - value) * amount; }

function isNetherDimension(dimIndex) {
  return manifest.dimensions.find(item => item.index === dimIndex)?.id
    === 'minecraft:the_nether';
}

function readSparseTileMask(r) {
  const coarse = r.bytes(32);
  const out = new Uint8Array(8192);
  for (let coarseIndex = 0; coarseIndex < 256; coarseIndex++) {
    if (!(coarse[coarseIndex >> 3] & (1 << (coarseIndex & 7)))) continue;
    const fine = r.bytes(32);
    for (let fineIndex = 0; fineIndex < 256; fineIndex++) {
      if (!(fine[fineIndex >> 3] & (1 << (fineIndex & 7)))) continue;
      const x = ((coarseIndex & 15) << 4) | (fineIndex & 15);
      const z = ((coarseIndex >> 4) << 4) | (fineIndex >> 4);
      const pixel = (z << 8) | x;
      out[pixel >> 3] |= 1 << (pixel & 7);
    }
  }
  return out;
}
function bits(mask) { const out=[]; for(let i=0;i<mask.length*8;i++) if(mask[i>>3]&(1<<(i&7))) out.push(i); return out; }
function tileKey(dim,lod,tileX,tileZ) { return `tile:${dim}:${lod}:${tileX}:${tileZ}`; }
function tileStillCurrent(canvas, dim, lod, tileX, tileZ, generation) {
  const tile = canvas.mapTile;
  return canvas.isConnected && generation === predictionGeneration
    && dim === Number(dimension.value) && tile?.dim === dim && tile.lod === lod
    && tile.x === tileX && tile.z === tileZ && tile.generation === generation;
}
function playerIcon(player) {
  const root = document.createElement('div');
  root.className = `web-player${player.translucent ? ' translucent' : ''}`;
  const image = document.createElement('img');
  image.alt = '';
  image.src = `/api/v1/avatars/${player.id}.png`;
  image.addEventListener('load', () => root.classList.add('skin-loaded'));
  image.addEventListener('error', () => {
    image.src = Math.random() < 0.5 ? '/default-steve.svg' : '/default-alex.svg';
  }, {once: true});
  const fallback = document.createElement('span');
  fallback.textContent = player.name.slice(0, 1).toUpperCase();
  root.append(image, fallback);
  return L.divIcon({className: '', html: root, iconSize: [28, 28], iconAnchor: [14, 14]});
}

function message(key, values = {}) {
  return Object.entries(values).reduce(
    (text, [name, value]) => text.replaceAll(`{${name}}`, String(value)),
    messages[key] ?? key
  );
}
function localizeZoomControl() {
  const zoomIn = map.zoomControl._zoomInButton;
  const zoomOut = map.zoomControl._zoomOutButton;
  zoomIn.title = message('zoomIn');
  zoomIn.setAttribute('aria-label', message('zoomIn'));
  zoomOut.title = message('zoomOut');
  zoomOut.setAttribute('aria-label', message('zoomOut'));
}
function formatZoomMultiplier(leafletZoom) {
  const steps = Math.round((leafletZoom + 1) / ZOOM_STEP);
  const multiplier = Math.max(0.0625, Math.min(4, 0.5 * Math.pow(1.26, steps)));
  const fixedPrecision = multiplier.toFixed(4);
  const minimumLength = fixedPrecision.indexOf('.') + 3;
  let length = fixedPrecision.length;
  while (length > minimumLength && fixedPrecision.charAt(length - 1) === '0') length--;
  return `${fixedPrecision.slice(0, length)}x`;
}
function updateScaleLabel() {
  scaleLabel.textContent = message('scale', {
    scale: formatZoomMultiplier(map.getZoom())
  });
}
function syncModeAvailability() {
  const selected = manifest.dimensions.find(item => item.index === Number(dimension.value));
  const all = mode.querySelector('[value="all"]');
  all.disabled = !manifest.predictionAvailable || !selected?.predictable;
  if (all.disabled) mode.value = 'generated';
}
function showLoadError() {
  status.textContent = message('loadError');
  status.classList.add('error');
}

function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(`conflux-map-${manifest.worldId}`, 1);
    request.onupgradeneeded = () => request.result.createObjectStore('regions');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
async function restoreRegion(cacheKey) {
  if (revisions.has(cacheKey)) return;
  try {
    const db = await database;
    const stored = await transaction(db, 'readonly', store => store.get(cacheKey));
    if (!stored) return;
    revisions.set(cacheKey, BigInt(stored.revision));
    compressedCache.set(cacheKey, new Uint8Array(stored.body));
  } catch {
    // IndexedDB can be unavailable in hardened/private browser contexts.
  }
}
async function persistRegion(cacheKey, revision, body) {
  try {
    const db = await database;
    await transaction(db, 'readwrite', store => store.put({
      revision: revision.toString(), body: body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength)
    }, cacheKey));
  } catch {
    // The in-memory cache remains valid for this page session.
  }
}
async function discardRegion(cacheKey) {
  revisions.delete(cacheKey);
  patchCache.delete(cacheKey);
  compressedCache.delete(cacheKey);
  await deletePersistedRegion(cacheKey);
}
async function deletePersistedRegion(cacheKey) {
  try {
    const db = await database;
    await transaction(db, 'readwrite', store => store.delete(cacheKey));
  } catch {
    // The in-memory entry was already discarded.
  }
}
function transaction(db, transactionMode, operation) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction('regions', transactionMode);
    const request = operation(tx.objectStore('regions'));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

class Reader {
  constructor(bytes){this.b=bytes;this.v=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);this.p=0}
  left(){return this.b.length-this.p} u8(){return this.v.getUint8(this.p++)}
  u16(){const n=this.v.getUint16(this.p);this.p+=2;return n}
  i32(){const n=this.v.getInt32(this.p);this.p+=4;return n} u32(){const n=this.v.getUint32(this.p);this.p+=4;return n}
  i64(){const n=this.v.getBigInt64(this.p);this.p+=8;return n}
  bytes(n){const x=this.b.slice(this.p,this.p+n);this.p+=n;return x}
  varint(){let n=0,s=0,b;do{b=this.u8();n|=(b&127)<<s;s+=7}while(b&128);return n}
  zigzag(){const n=this.varint();return (n>>>1)^-(n&1)}
  zigzagLong(){let n=0n,s=0n,b;do{b=BigInt(this.u8());n|=(b&127n)<<s;s+=7n}while(b&128n);return (n>>1n)^-(n&1n)}
}
