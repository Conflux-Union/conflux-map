import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {pathToFileURL} from 'node:url';

const core = await import(pathToFileURL(process.argv[2]));

assert.deepEqual(core.patchAction(core.PATCH_MODE_PARTIAL, 'all'), {
  applyBody: false,
  commitRevision: false,
  persistBody: false,
  validate: false,
  discardAuthority: false,
  replacement: 'keep',
  retry: true
});
assert.deepEqual(core.patchAction(core.PATCH_MODE_UNAVAILABLE, 'all'), {
  applyBody: false,
  commitRevision: false,
  persistBody: false,
  validate: true,
  discardAuthority: true,
  replacement: 'prediction',
  retry: false
});
assert.equal(core.patchAction(core.PATCH_MODE_UNAVAILABLE, 'generated').replacement, 'clear');
assert.equal(core.patchAction(core.PATCH_MODE_RESIDUAL, 'all').persistBody, true);
assert.equal(core.patchAction(core.PATCH_MODE_UNCHANGED, 'all').applyBody, true);
assert.deepEqual(core.mapErrorAction(1), {retry: true, showLoadError: false});
assert.deepEqual(core.mapErrorAction(2), {retry: false, showLoadError: true});
assert.deepEqual(core.mapErrorAction(3), {retry: true, showLoadError: true});
assert.deepEqual(core.mapErrorAction(4), {retry: false, showLoadError: true});

assert.equal(core.lodForLeafletZoom(-0.67), 0);
assert.equal(core.lodForLeafletZoom(-1), 1);
assert.equal(core.lodForLeafletZoom(-1.99), 1);
assert.equal(core.lodForLeafletZoom(-2), 2);
assert.equal(core.lodForLeafletZoom(2), 0);
assert.equal(core.tileZoomForLeafletZoom(-0.67), 0);
assert.equal(core.tileZoomForLeafletZoom(-2.4), -2);
assert.deepEqual(
  [-4, -1, -1 + Math.log2(1.26), 2].map(core.formatZoomMultiplier),
  ['0.0625x', '0.50x', '0.63x', '4.00x']
);

const firstManifest = {
  worldId: 'world-a', worldgenVersion: '1.21.8', predictionVersion: 123,
  dimensions: [
    {index: 0, id: 'minecraft:overworld'},
    {index: 1, id: 'minecraft:the_nether'}
  ]
};
const reorderedManifest = {
  ...firstManifest,
  dimensions: [
    {index: 0, id: 'minecraft:the_nether'},
    {index: 1, id: 'minecraft:overworld'}
  ]
};
assert.equal(
  core.cacheTileKey(firstManifest, 1, 2, -3, 4),
  core.cacheTileKey(reorderedManifest, 0, 2, -3, 4)
);
assert.notEqual(
  core.cacheTileKey(firstManifest, 1, 2, -3, 4),
  core.cacheTileKey({...firstManifest, worldId: 'world-b'}, 1, 2, -3, 4)
);
assert.match(
  core.cacheTileKey(firstManifest, 1, 2, -3, 4),
  /^tile:v2:world-a:1\.21\.8:123:minecraft%3Athe_nether:2:-3:4$/
);

assert.equal(core.localeForPreferences('en', ['zh-CN']), 'en');
assert.equal(core.localeForPreferences(null, ['zh-Hans-CN', 'en-US']), 'zh-CN');
assert.equal(core.localeForPreferences(null, ['fr-FR']), 'en');
const localeDirectory = join(dirname(fileURLToPath(import.meta.url)), '../../main/webmap/locales');
const english = JSON.parse(await readFile(join(localeDirectory, 'en.json'), 'utf8'));
const chinese = JSON.parse(await readFile(join(localeDirectory, 'zh-CN.json'), 'utf8'));
assert.deepEqual(Object.keys(chinese).sort(), Object.keys(english).sort());
for (const key of [
  'worldMap', 'mapControls', 'dimension', 'mode', 'modeAll', 'modeGenerated',
  'language', 'connecting', 'connected', 'loadError', 'zoomIn', 'zoomOut',
  'scale', 'entityPanel', 'players', 'noOnlinePlayers', 'goToPlayer'
]) {
  assert.equal(typeof english[key], 'string', `missing English ${key}`);
  assert.equal(typeof chinese[key], 'string', `missing Chinese ${key}`);
}

console.log('web map core patch lifecycle: ok');
