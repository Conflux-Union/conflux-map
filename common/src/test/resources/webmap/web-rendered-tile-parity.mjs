import {readFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';

const [wasmPath, rendererPath, manifestPath, expectedPath, versionText, seedText, lodText, dimensionText,
  blockXText, blockZText, patchPath]
  = process.argv.slice(2);
const rendererModule = await import(pathToFileURL(rendererPath));
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const imports = {wasi_snapshot_preview1: new Proxy({}, {get: () => () => 0})};
const {instance} = await WebAssembly.instantiate(await readFile(wasmPath), imports);
const version = Number(versionText);
const seed = BigInt(seedText);
const lod = Number(lodText);
const nativeDimension = Number(dimensionText);
const blockX = Number(blockXText);
const blockZ = Number(blockZText);
if (instance.exports.cfxWebInit(version, seed, nativeDimension, 0) !== 0) {
  throw new Error('predictor initialization failed');
}
if (instance.exports.cfxWebGenerateTile(blockX, blockZ, lod, 1) !== 0) {
  throw new Error('tile prediction failed');
}
const cells = 258 * 258;
const copy = (pointer, length) => new Int32Array(
  new Int32Array(instance.exports.memory.buffer, pointer, length)
);
const subCells = lod >= 3 && nativeDimension !== 1 ? cells * 4 : 0;
const predicted = {
  biomes: copy(instance.exports.cfxWebBiomeData(), cells),
  heights: copy(instance.exports.cfxWebHeightData(), cells),
  surfaces: copy(instance.exports.cfxWebSurfaceData(), cells),
  canopies: copy(instance.exports.cfxWebCanopyData(), cells),
  subBiomes: copy(instance.exports.cfxWebSubBiomeData(), subCells),
  subSurfaces: copy(instance.exports.cfxWebSubSurfaceData(), subCells),
  subCanopies: copy(instance.exports.cfxWebSubCanopyData(), subCells)
};
const renderer = rendererModule.createMapRenderer(manifest);
const actual = patchPath
  ? renderer.renderCorrectedArgb(
    predicted,
    await renderer.decodeTilePatch(new Uint8Array(await readFile(patchPath))),
    lod, nativeDimension === -1, blockX, blockZ
  )
  : renderer.renderPredictedArgb(
    predicted, lod, nativeDimension === -1, blockX, blockZ
  );
const expectedBytes = await readFile(expectedPath);
if (expectedBytes.length !== actual.length * 4) throw new Error('invalid expected image');
let mismatches = 0;
let first = '';
for (let index = 0; index < actual.length; index++) {
  const expected = expectedBytes.readUInt32BE(index * 4);
  if ((actual[index] >>> 0) !== expected) {
    mismatches++;
    if (!first) {
      const x = index & 255, z = index >>> 8, grid = (z + 1) * 258 + x + 1;
      first = `index ${index}: expected ${expected.toString(16)}, got ${(actual[index] >>> 0).toString(16)}`
        + `; biome=${predicted.biomes[grid]} height=${predicted.heights[grid]}`
        + ` surface=${predicted.surfaces[grid]} canopy=${predicted.canopies[grid]}`
        + (predicted.subBiomes.length
          ? ` subBiomes=${[...predicted.subBiomes.slice(grid * 4, grid * 4 + 4)]}`
            + ` subCanopies=${[...predicted.subCanopies.slice(grid * 4, grid * 4 + 4)]}`
          : '');
    }
  }
}
if (mismatches) throw new Error(`${mismatches} rendered pixels differ; ${first}`);
console.log(`LOD ${lod}: ${actual.length} pixels match`);
