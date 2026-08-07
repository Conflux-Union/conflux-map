import {readFile} from 'node:fs/promises';

const source = await readFile(process.argv[2], 'utf8');

function declaration(prefix) {
  const start = source.indexOf(prefix);
  if (start < 0) throw new Error(`missing ${prefix}`);
  const body = source.indexOf('{', start);
  let depth = 0;
  for (let index = body; index < source.length; index++) {
    if (source[index] === '{') depth++;
    else if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`unterminated ${prefix}`);
}

const paletteStart = source.indexOf('const palette = [');
const paletteEnd = source.indexOf('\n];', paletteStart);
if (paletteStart < 0 || paletteEnd < 0) throw new Error('missing map palette');
const dependencies = [
  'function drawPatch(',
  'function authoritativeBaseColor(',
  'function authoritativeKindColor(',
  'function authoritativeRelief(',
  'function authoritativeHeight(',
  'function paintsLiteralMapColor(',
  'function mapColor(',
  'function scaleColor(',
  'function applyNetherLight(',
  'function netherLightTint(',
  'function mix(',
  'function applyHeightShade(',
  'function applyShade(',
  'function shadeColor(',
  'function applyBrightness(',
  'function multiplyColor(',
  'function blendOver(',
  'function clampColor('
];
const renderer = new Function(`
  ${source.slice(paletteStart, paletteEnd + 3)}
  const fallbackPredictionBiome = {
    waterBiome: false, surfaceColor: 0x123456,
    canopyColor: 0x234567, waterTint: 0x3f76e4
  };
  const predictionBiomes = new Map([[1, fallbackPredictionBiome], [0, {
    ...fallbackPredictionBiome, waterBiome: true
  }]]);
  ${dependencies.map(declaration).join('\n')}
  return drawPatch;
`)();

function patch(overrides = {}) {
  return {
    width: 3,
    height: 3,
    indexes: [0, 1, 2, 3, 4, 5, 6, 7, 8],
    biomes: new Uint8Array(9).fill(1),
    heights: new Int32Array(9).fill(80),
    kinds: new Uint8Array(9).fill(1),
    colors: new Uint8Array(9).fill(11),
    fluids: new Uint8Array(9),
    floorColors: new Uint8Array(9).fill(255),
    lights: new Uint8Array(9),
    ...overrides
  };
}

function render(input, lod = 0, initial = [0, 0, 0, 0]) {
  const output = new Uint8ClampedArray(3 * 3 * 4);
  for (let pixel = 0; pixel < 9; pixel++) output.set(initial, pixel * 4);
  const context = {
    getImageData: () => ({data: output}),
    putImageData: () => {}
  };
  renderer(context, input, 0, 0, lod, false);
  return [...output.slice(4 * 4, 4 * 5)];
}

function expect(name, actual, expected) {
  if (actual.join(',') !== expected.join(',')) {
    console.error(`${name}: expected ${expected}, got ${actual}`);
    process.exitCode = 1;
  } else {
    console.log(`${name}: ${actual}`);
  }
}

expect('flat authoritative stone', render(patch()), [112, 112, 112, 255]);

const reliefHeights = new Int32Array(9).fill(80);
reliefHeights[6] = 82;
expect(
  'spatial southwest relief',
  render(patch({heights: reliefHeights})),
  [146, 146, 146, 255]
);

expect(
  'biome-tinted grass map color',
  render(patch({colors: new Uint8Array(9).fill(1)})),
  [18, 52, 86, 255]
);

expect(
  'surface kind fallback is independent of biome kind',
  render(patch({
    kinds: new Uint8Array(9).fill(5),
    colors: new Uint8Array(9).fill(255)
  })),
  [247, 250, 255, 255]
);

expect(
  'water over depth-shaded map floor',
  render(patch({
    biomes: new Uint8Array(9),
    kinds: new Uint8Array(9).fill(2),
    fluids: new Uint8Array(9).fill(12),
    floorColors: new Uint8Array(9).fill(11)
  })),
  [57, 99, 189, 255]
);

expect(
  'unknown authority preserves prediction underlay',
  render(patch({kinds: new Uint8Array(9)}), 0, [9, 8, 7, 6]),
  [9, 8, 7, 6]
);

if (process.argv[3]) {
  const decoder = new Function(`
    ${declaration('class Reader')}
    ${declaration('async function decodeTilePatch(')}
    ${declaration('function readMaterialPlane(')}
    ${declaration('function readSparseTileMask(')}
    ${declaration('function bits(')}
    return decodeTilePatch;
  `)();
  const decoded = await decoder(new Uint8Array(await readFile(process.argv[3])));
  expect('decoded floor map color', [decoded.floorColors[0]], [11]);
  expect(
    'decoded material identifiers',
    [decoded.materialIds[0], decoded.floorMaterialIds[0]],
    ['minecraft:grass_block', 'minecraft:stone']
  );
}
