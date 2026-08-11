import {
  CACHE_SCHEMA_VERSION,
  PATCH_MODE_UNCHANGED,
  cacheTileKey,
  formatZoomMultiplier,
  lodForLeafletZoom,
  localeForPreferences,
  mapErrorAction,
  mergeMapState,
  requestLimits,
  tileZoomForLeafletZoom,
  patchAction
} from '/map-core.js';
import {createMapRenderer} from '/map-renderer.js';
import {createReconnectingWebSocket} from '/map-connection.js';

const status = document.querySelector('#status');
const dimension = document.querySelector('#dimension');
const mode = document.querySelector('#mode');
const language = document.querySelector('#language');
const playerList = document.querySelector('#player-list');
const playerCount = document.querySelector('#player-count');
const scaleLabel = document.querySelector('#scale-label');
let messages = {
  connecting: 'Connecting…',
  loadError: 'Map loading failed',
  connected: 'Connected',
  zoomIn: 'Zoom in',
  zoomOut: 'Zoom out',
  scale: 'Scale: {scale}'
};
window.addEventListener('unhandledrejection', () => showLoadError());
window.addEventListener('error', () => showLoadError());
const locale = localeForPreferences(
  localStorage.getItem('conflux-map-locale'),
  navigator.languages ?? [navigator.language]
);
messages = await fetch(`/locales/${locale}.json`).then(response => {
  if (!response.ok) throw new Error(`locale ${response.status}`);
  return response.json();
});
document.documentElement.lang = locale;
language.value = locale;
translatePage();
language.addEventListener('change', () => {
  localStorage.setItem('conflux-map-locale', language.value);
  location.reload();
});
const manifest = await fetch('/api/v1/manifest', {cache: 'no-store'}).then(response => {
  if (!response.ok) throw new Error(`manifest ${response.status}`);
  return response.json();
});
const limits = requestLimits(manifest);
const mapRenderer = createMapRenderer(manifest);

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

class RegionLayer extends L.GridLayer {
  _setView(center, zoom, noPrune, noUpdate) {
    let tileZoom = tileZoomForLeafletZoom(zoom);
    if ((this.options.maxZoom !== undefined && tileZoom > this.options.maxZoom)
      || (this.options.minZoom !== undefined && tileZoom < this.options.minZoom)) {
      tileZoom = undefined;
    } else {
      tileZoom = this._clampZoom(tileZoom);
    }
    const tileZoomChanged = this.options.updateWhenZooming && tileZoom !== this._tileZoom;
    if (!noUpdate || tileZoomChanged) {
      this._tileZoom = tileZoom;
      this._abortLoading?.();
      this._updateLevels();
      this._resetGrid();
      if (tileZoom !== undefined) this._update(center);
      if (!noPrune) this._pruneTiles();
      this._noPrune = Boolean(noPrune);
    }
    this._setZoomTransforms(center, zoom);
  }

  createTile(coords, done) {
    const canvas = L.DomUtil.create('canvas', 'leaflet-tile map-tile');
    canvas.width = canvas.height = 256;
    const lod = lodForLeafletZoom(coords.z);
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
  await drawCachedTile(canvas, cacheKey, lod, renderDimension);
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
  const action = patchAction(patch.mode, mode.value);
  if (action.retry) {
    setTimeout(() => {
      if (!tileStillCurrent(canvas, dimIndex, lod, tileX, tileZ, generation)) return;
      syncTile(canvas, tileX, tileZ, lod, generation, true).catch(error => {
        console.warn('Map correction retry failed', error);
        if (error.retry !== false) scheduleVisibleTileSync();
      });
    }, 500);
    return;
  }
  if (action.discardAuthority) {
    await discardRegion(cacheKey);
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (action.replacement === 'prediction') {
      await drawPrediction(canvas, ctx, tileX, tileZ, lod, dimIndex, generation);
    }
    if (action.validate) validated.add(cacheKey);
    return;
  }
  if (action.applyBody) {
    const compressed = patch.mode === PATCH_MODE_UNCHANGED
      ? compressedCache.get(cacheKey) : patch.body;
    const decoded = compressed ? await mapRenderer.decodeTilePatch(compressed) : null;
    if (decoded) {
      patchCache.set(cacheKey, decoded);
      const span = 256 << lod;
      mapRenderer.drawCorrectedTile(
        canvas.getContext('2d'), canvas.predicted ?? null, decoded, lod,
        isNetherDimension(dimIndex), tileX * span, tileZ * span
      );
    }
  }
  if (action.commitRevision) revisions.set(cacheKey, patch.revision);
  if (action.persistBody && patch.body.length) {
    compressedCache.set(cacheKey, patch.body);
    persistRegion(cacheKey, patch.revision, patch.body);
  }
  if (action.validate) validated.add(cacheKey);
}

function scheduleVisibleTileSync() {
  clearTimeout(visibleTileSyncTimer);
  visibleTileSyncTimer = setTimeout(() => {
    const activeLod = lodForLeafletZoom(map.getZoom());
    for (const canvas of layer.getContainer().querySelectorAll('canvas.map-tile')) {
      const tile = canvas.mapTile;
      if (!tile || tile.lod !== activeLod || !tileStillCurrent(
        canvas, tile.dim, tile.lod, tile.x, tile.z, tile.generation
      )) continue;
      syncTile(canvas, tile.x, tile.z, tile.lod, tile.generation).catch(error => {
        console.warn('Map correction sync failed', error);
        if (error.retry !== false) scheduleVisibleTileSync();
      });
    }
  }, 500);
}

async function drawCachedTile(canvas, cacheKey, lod, dimIndex) {
  let decoded = patchCache.get(cacheKey);
  if (!decoded) {
    const compressed = compressedCache.get(cacheKey);
    if (compressed) {
      decoded = await mapRenderer.decodeTilePatch(compressed);
      patchCache.set(cacheKey, decoded);
    }
  }
  if (!decoded) return false;
  const tile = canvas.mapTile;
  const span = 256 << lod;
  mapRenderer.drawCorrectedTile(
    canvas.getContext('2d'), canvas.predicted ?? null, decoded, lod,
    isNetherDimension(dimIndex), tile.x * span, tile.z * span
  );
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
    for (let offset = 0; offset < group.length; offset += limits.maxTilesPerRequest) {
      const batch = group.slice(offset, offset + limits.maxTilesPerRequest);
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
  await mapSocket.ready();
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
    mapSocket.send(bytes);
  });
}

function connectMapSocket() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  let heartbeat;
  return createReconnectingWebSocket({
    url: `${protocol}//${location.host}/api/v1/map`,
    onOpen() {
      clearInterval(heartbeat);
      heartbeat = setInterval(() => {
        try {
          mapSocket.send('ping');
        } catch {
          // The close event schedules reconnection and retries visible state.
        }
      }, 25000);
      status.classList.remove('error');
      status.textContent = messages.connected;
      scheduleSubscription();
      scheduleVisibleTileSync();
    },
    onMessage(event) {
      if (typeof event.data === 'string') {
        if (event.data !== 'pong') updateMapState(JSON.parse(event.data));
        return;
      }
      handleMapFrame(new Uint8Array(event.data));
    },
    onDisconnect() {
      clearInterval(heartbeat);
      const error = new Error('map websocket closed');
      for (const pending of pendingRequests.values()) {
        clearTimeout(pending.timer);
        pending.reject(error);
      }
      pendingRequests.clear();
      showLoadError();
    },
    onError: showLoadError
  });
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
    const code = frame.u8();
    const detail = new TextDecoder().decode(frame.bytes(frame.u16()));
    const action = mapErrorAction(code);
    const error = new Error(detail || 'map request was rejected');
    error.retry = action.retry;
    for (const pending of pendingRequests.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    pendingRequests.clear();
    if (action.showLoadError) showLoadError();
    if (action.retry) scheduleVisibleTileSync();
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
      if (error.retry !== false) scheduleVisibleTileSync();
    });
  }
}

function scheduleSubscription() {
  clearTimeout(subscriptionTimer);
  subscriptionTimer = setTimeout(sendSubscription, 500);
}

async function sendSubscription() {
  await mapSocket.ready();
  const bounds = map.getBounds();
  const minChunkX = Math.floor(bounds.getWest() / 16);
  const maxChunkX = Math.floor(bounds.getEast() / 16);
  const minChunkZ = Math.floor(-bounds.getNorth() / 16);
  const maxChunkZ = Math.floor(-bounds.getSouth() / 16);
  if (maxChunkX - minChunkX >= 4096 || maxChunkZ - minChunkZ >= 4096) return;
  const lod = lodForLeafletZoom(map.getZoom());
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
  mapSocket.send(bytes);
}

function enterRequestGate() {
  requestGate = requestGate.then(async () => {
    const delay = limits.requestIntervalMs - (performance.now() - lastRequestAt);
    if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay));
    lastRequestAt = performance.now();
  });
  return requestGate;
}

function updateMapState(message) {
  const next = mergeMapState(currentMapState, message);
  if (next === currentMapState) return;
  currentMapState = next;
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
    empty.textContent = message('noOnlinePlayers');
    playerList.append(empty);
    return;
  }
  for (const player of [...players].sort((a, b) => a.name.localeCompare(b.name))) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'player-row';
    button.title = message('goToPlayer', {player: player.name});
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
  canvas.predicted = predicted;
  mapRenderer.drawPredictedTile(
    ctx, predicted, lod, isNetherDimension(dimIndex), tileX * span, tileZ * span
  );
  if ((nativeDimension === 0 && lod <= 1) || nativeDimension === 1) {
    predicted = await predictor.request({...request, exact: true}, generation, canvas);
    if (!predicted || !canvas.isConnected || generation !== predictionGeneration) return false;
    canvas.predicted = predicted;
    mapRenderer.drawPredictedTile(
      ctx, predicted, lod, isNetherDimension(dimIndex), tileX * span, tileZ * span
    );
  }
  return true;
}

function isNetherDimension(dimIndex) {
  return manifest.dimensions.find(item => item.index === dimIndex)?.id
    === 'minecraft:the_nether';
}

function tileKey(dim,lod,tileX,tileZ) {
  return cacheTileKey(manifest, dim, lod, tileX, tileZ);
}
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
function translatePage() {
  for (const element of document.querySelectorAll('[data-i18n]')) {
    element.textContent = message(element.dataset.i18n);
  }
  for (const element of document.querySelectorAll('[data-i18n-aria-label]')) {
    element.setAttribute('aria-label', message(element.dataset.i18nAriaLabel));
  }
}
function localizeZoomControl() {
  const zoomIn = map.zoomControl._zoomInButton;
  const zoomOut = map.zoomControl._zoomOutButton;
  zoomIn.title = message('zoomIn');
  zoomIn.setAttribute('aria-label', message('zoomIn'));
  zoomOut.title = message('zoomOut');
  zoomOut.setAttribute('aria-label', message('zoomOut'));
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
    const request = indexedDB.open(
      `conflux-map-${manifest.worldId}-v${CACHE_SCHEMA_VERSION}`, 1
    );
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
