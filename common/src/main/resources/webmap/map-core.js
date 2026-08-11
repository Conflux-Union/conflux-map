export const PATCH_MODE_UNCHANGED = 0;
export const PATCH_MODE_RESIDUAL = 1;
export const PATCH_MODE_ABSOLUTE = 2;
export const PATCH_MODE_UNAVAILABLE = 3;
export const PATCH_MODE_PARTIAL = 4;
export const CACHE_SCHEMA_VERSION = 2;
export const SUPPORTED_LOCALES = ['en', 'zh-CN'];

/** Describes how one wire patch may affect committed browser state. */
export function patchAction(mode, viewMode) {
  if (mode === PATCH_MODE_PARTIAL) {
    return {
      applyBody: false,
      commitRevision: false,
      persistBody: false,
      validate: false,
      discardAuthority: false,
      replacement: 'keep',
      retry: true
    };
  }
  if (mode === PATCH_MODE_UNAVAILABLE) {
    return {
      applyBody: false,
      commitRevision: false,
      persistBody: false,
      validate: true,
      discardAuthority: true,
      replacement: viewMode === 'all' ? 'prediction' : 'clear',
      retry: false
    };
  }
  return {
    applyBody: true,
    commitRevision: true,
    persistBody: mode === PATCH_MODE_RESIDUAL || mode === PATCH_MODE_ABSOLUTE,
    validate: true,
    discardAuthority: false,
    replacement: 'keep',
    retry: false
  };
}

export function mapErrorAction(code) {
  if (code === 1) return {retry: true, showLoadError: false};
  if (code === 3) return {retry: true, showLoadError: true};
  return {retry: false, showLoadError: true};
}

/** Mirrors TileMath.lodForScale for Leaflet's scale of 2^zoom screen pixels per block. */
export function lodForLeafletZoom(zoom) {
  if (!Number.isFinite(zoom)) return 0;
  return Math.max(0, Math.min(4, Math.floor(-zoom + 1.0e-10)));
}

export function tileZoomForLeafletZoom(zoom) {
  const lod = lodForLeafletZoom(zoom);
  return lod === 0 ? 0 : -lod;
}

/** Uses protocol-stable world and dimension identities, never a restart-local dimension index. */
export function cacheTileKey(manifest, dimensionIndex, lod, tileX, tileZ) {
  const dimensionId = manifest.dimensions.find(item => item.index === dimensionIndex)?.id;
  if (!dimensionId) throw new Error(`unknown dimension index ${dimensionIndex}`);
  const renderer = `${manifest.worldgenVersion}:${manifest.predictionVersion ?? -1}`;
  return [
    'tile', `v${CACHE_SCHEMA_VERSION}`, encodeURIComponent(manifest.worldId), renderer,
    encodeURIComponent(dimensionId), lod, tileX, tileZ
  ].join(':');
}

export function localeForPreferences(storedLocale, browserLanguages = []) {
  if (SUPPORTED_LOCALES.includes(storedLocale)) return storedLocale;
  for (const language of browserLanguages) {
    if (typeof language === 'string' && language.toLowerCase().startsWith('zh')) {
      return 'zh-CN';
    }
    if (typeof language === 'string' && language.toLowerCase().startsWith('en')) {
      return 'en';
    }
  }
  return 'en';
}

export function requestLimits(manifest) {
  const configuredTiles = Number(manifest?.limits?.maxTilesPerRequest);
  const maxTilesPerRequest = Number.isInteger(configuredTiles)
    && configuredTiles >= 1 && configuredTiles <= 255 ? configuredTiles : 8;
  const configuredInterval = Number(manifest?.limits?.minRequestIntervalMs);
  const minRequestIntervalMs = Number.isFinite(configuredInterval)
    ? Math.max(0, Math.min(60000, configuredInterval)) : 100;
  return {
    maxTilesPerRequest,
    requestIntervalMs: minRequestIntervalMs + 25
  };
}

export function mergeMapState(current, message) {
  const snapshot = message?.snapshot ?? {};
  if (message?.type === 'map-state') {
    return {
      players: snapshot.players ?? [],
      waypoints: snapshot.waypoints ?? []
    };
  }
  if (message?.type === 'players') {
    return {...current, players: snapshot.players ?? []};
  }
  if (message?.type === 'waypoints') {
    return {...current, waypoints: snapshot.waypoints ?? []};
  }
  return current;
}

export function formatZoomMultiplier(leafletZoom) {
  const zoomStep = Math.log2(1.26);
  const steps = Math.round((leafletZoom + 1) / zoomStep);
  const multiplier = Math.max(0.0625, Math.min(4, 0.5 * Math.pow(1.26, steps)));
  const fixedPrecision = multiplier.toFixed(4);
  const minimumLength = fixedPrecision.indexOf('.') + 3;
  let length = fixedPrecision.length;
  while (length > minimumLength && fixedPrecision.charAt(length - 1) === '0') length--;
  return `${fixedPrecision.slice(0, length)}x`;
}
