const status = document.querySelector('#status');
const dimension = document.querySelector('#dimension');
const mode = document.querySelector('#mode');
const language = document.querySelector('#language');
const supportedLocales = ['en', 'zh-CN'];
let messages = {
  connecting: 'Connecting…',
  loadError: 'Map loading failed'
};
window.addEventListener('unhandledrejection', () => showLoadError());
window.addEventListener('error', () => showLoadError());
const locale = selectLocale();
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
const manifest = await fetch('/api/v1/manifest', {cache: 'no-store'}).then(r => {
  if (!r.ok) throw new Error(`manifest ${r.status}`);
  return r.json();
});

for (const item of manifest.dimensions) {
  const option = document.createElement('option');
  option.value = item.index;
  option.textContent = item.id;
  dimension.append(option);
}
syncModeAvailability();

const map = L.map('map', {
  crs: L.CRS.Simple,
  minZoom: -4,
  maxZoom: 0,
  zoomSnap: 1,
  attributionControl: true
}).setView([0, 0], -2);
map.attributionControl.addAttribution('Conflux Map · Leaflet');
localizeZoomControl();

const playerMarkers = new Map();
const events = new EventSource('/api/v1/events');
events.addEventListener('players', event => {
  const snapshot = JSON.parse(event.data);
  const visible = new Set();
  for (const player of snapshot.players) {
    if (player.dimension !== Number(dimension.value)) continue;
    visible.add(player.id);
    let marker = playerMarkers.get(player.id);
    if (!marker) {
      marker = L.marker([-player.z, player.x], {icon: playerIcon(player)})
        .bindTooltip(player.name).addTo(map);
      playerMarkers.set(player.id, marker);
    } else {
      marker.setLatLng([-player.z, player.x]);
      marker.getElement()?.querySelector('.web-player')
        ?.classList.toggle('translucent', player.translucent);
    }
  }
  for (const [id, marker] of playerMarkers) if (!visible.has(id)) {
    marker.remove(); playerMarkers.delete(id);
  }
});

let sequence = 1;
let lastRequestAt = 0;
let requestGate = Promise.resolve();
const revisions = new Map();
const patchCache = new Map();
const compressedCache = new Map();
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

class RegionLayer extends L.GridLayer {
  createTile(coords, done) {
    const canvas = L.DomUtil.create('canvas', 'leaflet-tile');
    canvas.width = canvas.height = 256;
    const lod = Math.max(0, Math.min(4, -coords.z));
    renderTile(canvas, coords.x, coords.y, lod)
      .then(() => done(null, canvas), error => done(error, canvas));
    return canvas;
  }
}

let layer = new RegionLayer({
  tileSize: 256,
  minZoom: -4,
  maxZoom: 0,
  noWrap: true,
  updateWhenIdle: true
}).addTo(map);
dimension.addEventListener('change', () => {
  syncModeAvailability();
  refresh();
});
mode.addEventListener('change', refresh);
function refresh() {
  revisions.clear();
  for (const marker of playerMarkers.values()) marker.remove();
  playerMarkers.clear();
  layer.redraw();
}

async function renderTile(canvas, tileX, tileZ, lod) {
  const span = 1 << lod;
  const regions = [];
  for (let z = 0; z < span; z++) for (let x = 0; x < span; x++) {
    const rx = tileX * span + x;
    const rz = tileZ * span + z;
    regions.push({rx, rz, x, z});
  }
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  let loaded = 0;
  for (let offset = 0; offset < regions.length; offset += 8) {
    const batch = regions.slice(offset, offset + 8);
    const patches = await requestRegions(batch, lod);
    for (const patch of patches) {
      const region = batch.find(r => r.rx === patch.rx && r.rz === patch.rz);
      if (!region || patch.mode === 3) continue;
      const cacheKey = key(region, lod);
      const compressed = patch.mode === 0 ? compressedCache.get(cacheKey) : patch.body;
      const decoded = patchCache.get(cacheKey)
        ?? (compressed ? await decodeChunkPatch(compressed) : null);
      if (!decoded) continue;
      drawPatch(ctx, decoded, region.x * decoded.width, region.z * decoded.height);
      revisions.set(cacheKey, patch.revision);
      patchCache.set(cacheKey, decoded);
      if (patch.mode !== 0) {
        compressedCache.set(cacheKey, patch.body);
        persistRegion(cacheKey, patch.revision, patch.body);
      }
      loaded++;
    }
  }
  status.textContent = message('regionsLoaded', {
    loaded, total: regions.length, lod
  });
}

async function requestRegions(regions, lod) {
  await (requestGate = requestGate.then(async () => {
    const delay = 105 - (performance.now() - lastRequestAt);
    if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay));
    lastRequestAt = performance.now();
  }));
  await Promise.all(regions.map(region => restoreRegion(key(region, lod))));
  const bytes = new Uint8Array(8 + regions.length * 20);
  const view = new DataView(bytes.buffer);
  let p = 0;
  view.setUint8(p++, 0x0c);
  view.setInt32(p, sequence++); p += 4;
  view.setUint8(p++, Number(dimension.value));
  view.setUint8(p++, lod);
  view.setUint8(p++, regions.length);
  for (const region of regions) {
    view.setInt32(p, region.rx); p += 4;
    view.setInt32(p, region.rz); p += 4;
    view.setUint8(p++, 0); view.setUint8(p++, 0);
    view.setUint8(p++, 15); view.setUint8(p++, 15);
    view.setBigInt64(p, revisions.get(key(region, lod)) ?? -9223372036854775808n); p += 8;
  }
  const response = await fetch('/api/v1/regions', {
    method: 'POST', headers: {'content-type': 'application/octet-stream'}, body: bytes
  });
  if (!response.ok) throw new Error(`regions ${response.status}`);
  return decodeFrames(new Uint8Array(await response.arrayBuffer()));
}

function decodeFrames(bytes) {
  const reader = new Reader(bytes);
  const result = [];
  while (reader.left()) {
    const length = reader.u32();
    const frame = new Reader(reader.bytes(length));
    if (frame.u8() !== 0x0d) throw new Error('unexpected map response');
    frame.i32(); frame.u8(); frame.u8();
    const rx = frame.i32(), rz = frame.i32();
    frame.u8(); frame.u8(); frame.u8(); frame.u8();
    const mode = frame.u8();
    const revision = frame.i64();
    const body = frame.bytes(frame.u32());
    result.push({rx, rz, mode, revision, body});
  }
  return result;
}

async function decodeChunkPatch(compressed) {
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('deflate'));
  const raw = new Uint8Array(await new Response(stream).arrayBuffer());
  const r = new Reader(raw);
  const version = r.u8();
  if (version !== 2) throw new Error(`unsupported region codec ${version}`);
  const chunksX = r.u8(), chunksZ = r.u8(), samplesPerChunk = r.u8();
  readMask(r, chunksX * chunksZ);
  const evaluated = readMask(r, chunksX * chunksZ * samplesPerChunk * samplesPerChunk);
  const difference = readMask(r, chunksX * chunksZ * samplesPerChunk * samplesPerChunk);
  const indexes = bits(difference);
  const count = indexes.length;
  const biomes = r.bytes(count);
  const heights = new Int32Array(count);
  let height = 0;
  for (let i = 0; i < count; i++) { height += r.zigzag(); heights[i] = height; }
  const kinds = r.bytes(count), colors = r.bytes(count), fluids = r.bytes(count);
  r.bytes(count);
  for (let i = 0; i < chunksX * chunksZ; i++) r.zigzagLong();
  const lights = new Uint8Array(evaluated.length * 8);
  for (const index of bits(evaluated)) lights[index] = r.u8();
  return {width: chunksX * samplesPerChunk, height: chunksZ * samplesPerChunk,
    indexes, biomes, heights, kinds, colors, fluids, lights};
}

function drawPatch(ctx, patch, offsetX, offsetZ) {
  const image = ctx.createImageData(patch.width, patch.height);
  for (let i = 0; i < patch.indexes.length; i++) {
    const pixel = patch.indexes[i];
    let color = hex(palette[patch.colors[i]] ?? palette[(patch.biomes[i] % 12) + 1]);
    const light = .62 + patch.lights[pixel] / 38;
    const shade = i && patch.heights[i] > patch.heights[i - 1] ? 1.08 : 1;
    image.data[pixel * 4] = Math.min(255, color[0] * light * shade);
    image.data[pixel * 4 + 1] = Math.min(255, color[1] * light * shade);
    image.data[pixel * 4 + 2] = Math.min(255, color[2] * light * shade);
    image.data[pixel * 4 + 3] = 255;
  }
  ctx.putImageData(image, offsetX, offsetZ);
}

function readMask(r, count) {
  const out = new Uint8Array((count + 7) >> 3);
  const mode = r.u8();
  if (mode === 0) return r.bytes(out.length);
  if (mode !== 1) throw new Error('invalid mask');
  const runs = r.varint(); let end = 0;
  for (let run = 0; run < runs; run++) {
    const start = end + r.varint(), length = r.varint();
    for (let bit = start; bit < start + length; bit++) out[bit >> 3] |= 1 << (bit & 7);
    end = start + length;
  }
  return out;
}
function bits(mask) { const out=[]; for(let i=0;i<mask.length*8;i++) if(mask[i>>3]&(1<<(i&7))) out.push(i); return out; }
function key(r,lod) { return `${dimension.value}:${lod}:${r.rx}:${r.rz}`; }
function hex(value) { const n=parseInt(value.slice(1),16); return [n>>16,(n>>8)&255,n&255]; }
function playerIcon(player) {
  const root = document.createElement('div');
  root.className = `web-player${player.translucent ? ' translucent' : ''}`;
  const image = document.createElement('img');
  image.alt = '';
  image.src = `/api/v1/avatars/${player.id}.png`;
  image.addEventListener('load', () => root.classList.add('skin-loaded'));
  image.addEventListener('error', () => image.remove());
  const fallback = document.createElement('span');
  fallback.textContent = player.name.slice(0, 1).toUpperCase();
  root.append(image, fallback);
  return L.divIcon({className: '', html: root, iconSize: [28, 28], iconAnchor: [14, 14]});
}

function selectLocale() {
  const saved = localStorage.getItem('conflux-map-locale');
  if (supportedLocales.includes(saved)) return saved;
  return navigator.languages?.some(candidate => candidate.toLowerCase().startsWith('zh'))
    ? 'zh-CN' : 'en';
}
function message(key, values = {}) {
  return Object.entries(values).reduce(
    (text, [name, value]) => text.replaceAll(`{${name}}`, String(value)),
    messages[key] ?? key
  );
}
function translatePage() {
  document.querySelectorAll('[data-i18n]').forEach(element => {
    element.textContent = message(element.dataset.i18n);
  });
  document.querySelector('#map').setAttribute('aria-label', message('worldMap'));
  document.querySelector('.controls').setAttribute('aria-label', message('mapControls'));
  status.textContent = message('connecting');
}
function localizeZoomControl() {
  const zoomIn = map.zoomControl._zoomInButton;
  const zoomOut = map.zoomControl._zoomOutButton;
  zoomIn.title = message('zoomIn');
  zoomIn.setAttribute('aria-label', message('zoomIn'));
  zoomOut.title = message('zoomOut');
  zoomOut.setAttribute('aria-label', message('zoomOut'));
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
function transaction(db, mode, operation) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction('regions', mode);
    const request = operation(tx.objectStore('regions'));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

class Reader {
  constructor(bytes){this.b=bytes;this.v=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);this.p=0}
  left(){return this.b.length-this.p} u8(){return this.v.getUint8(this.p++)}
  i32(){const n=this.v.getInt32(this.p);this.p+=4;return n} u32(){const n=this.v.getUint32(this.p);this.p+=4;return n}
  i64(){const n=this.v.getBigInt64(this.p);this.p+=8;return n}
  bytes(n){const x=this.b.slice(this.p,this.p+n);this.p+=n;return x}
  varint(){let n=0,s=0,b;do{b=this.u8();n|=(b&127)<<s;s+=7}while(b&128);return n}
  zigzag(){const n=this.varint();return (n>>>1)^-(n&1)}
  zigzagLong(){let n=0n,s=0n,b;do{b=BigInt(this.u8());n|=(b&127n)<<s;s+=7n}while(b&128n);return (n>>1n)^-(n&1n)}
}
