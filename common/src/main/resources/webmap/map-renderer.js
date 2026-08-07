const MAP_COLORS = [
  0x000000,0x7fb238,0xf7e9a3,0xc7c7c7,0xff0000,0xa0a0ff,0xa7a7a7,0x007c00,
  0xffffff,0xa4a8b8,0x976d4d,0x707070,0x4040ff,0x8f7748,0xfffcf5,0xd87f33,
  0xb24cd8,0x6699d8,0xe5e533,0x7fcc19,0xf27fa5,0x4c4c4c,0x999999,0x4c7f99,
  0x7f3fb2,0x334cb2,0x664c33,0x667f33,0x993333,0x191919,0xfaee4d,0x5cdbd5,
  0x4a80ff,0x00d93a,0x815631,0x700200,0xd1b1a1,0x9f5224,0x95576c,0x706c8a,
  0xba8524,0x677535,0xa04d4e,0x392923,0x876b62,0x575c5c,0x7a4958,0x4c3e5c,
  0x4c3223,0x4c522a,0x8e3c2e,0x251610,0xbd3031,0x943f61,0x5c191d,0x167e86,
  0x3a8e8c,0x562c3e,0x14b485,0x646464,0xd8af93,0x7fa796
];
const NETHER_ROOF_MAP_COLOR_ID = 11;

export function createMapRenderer(manifest) {
  const predictionBiomes = new Map(
    (manifest.predictionBiomes ?? []).map(entry => [entry.id, entry])
  );
  const fallbackPredictionBiome = predictionBiomes.get(-1) ?? {
    kind: 'LAND', waterBiome: false, surfaceColor: 0x49763b,
    canopyColor: 0x2f6d1b, waterTint: 0x3f76e4
  };
  const materialSamples = new Map(
    (manifest.materials ?? []).map(sample => [sample.id, sample])
  );

  function renderPredictedArgb(predicted, lod, netherRoof, blockX = 0, blockZ = 0) {
    const output = new Uint32Array(256 * 256);
    const size = 258;
    for (let z = 0; z < 256; z++) for (let x = 0; x < 256; x++) {
      const gridIndex = (z + 1) * size + x + 1;
      const color = predictedColor(
        predicted, gridIndex, lod, netherRoof,
        blockX + (x << lod), blockZ + (z << lod)
      );
      output[z * 256 + x] = color < 0 ? 0 : (0xff000000 | color) >>> 0;
    }
    return output;
  }

  function drawPredictedTile(ctx, predicted, lod, netherRoof, blockX = 0, blockZ = 0) {
    const argb = renderPredictedArgb(predicted, lod, netherRoof, blockX, blockZ);
    const image = ctx.createImageData(256, 256);
    for (let index = 0; index < argb.length; index++) {
      const color = argb[index];
      const pixel = index * 4;
      image.data[pixel] = color >>> 16 & 255;
      image.data[pixel + 1] = color >>> 8 & 255;
      image.data[pixel + 2] = color & 255;
      image.data[pixel + 3] = color >>> 24;
    }
    ctx.putImageData(image, 0, 0);
  }

  function predictedColor(predicted, index, lod, netherRoof, worldX, worldZ) {
    if (netherRoof) return applyNetherLight(mapColor(NETHER_ROOF_MAP_COLOR_ID), 0);
    if ((predicted.surfaces[index] & 2) !== 0) return -1;
    const biome = predictionBiomes.get(predicted.biomes[index]) ?? fallbackPredictionBiome;
    const water = (predicted.surfaces[index] & 1) !== 0;
    const depth = predicted.surfaces[index] >>> 8;
    const surfaceHeight = water ? 62 : predicted.heights[index] + predicted.canopies[index];
    const kind = predictionKind(
      biome, water, water ? 62 : predicted.heights[index], worldX, worldZ
    );
    let color = predicted.subBiomes.length
      ? averagedSubColor(predicted, index, worldX, worldZ, lod)
      : kind === 'WATER' ? predictedWaterColor(biome, depth, predicted, index, lod)
        : kind === 'FOLIAGE' || predicted.canopies[index] > 0
          && kind !== 'ICE' && kind !== 'VOID'
          ? biome.canopyColor
          : kindColor(kind, biome);
    color = applyHeightShade(color, surfaceHeight);
    return applyBrightness(color, reliefMultiplier(predicted, index, lod, false));
  }

  function averagedSubColor(predicted, index, worldX, worldZ, lod) {
    let red = 0, green = 0, blue = 0;
    const subStride = 1 << Math.max(0, lod - 1);
    for (let sample = 0; sample < 4; sample++) {
      const subIndex = index * 4 + sample;
      const biome = predictionBiomes.get(predicted.subBiomes[subIndex])
        ?? fallbackPredictionBiome;
      const surface = predicted.subSurfaces[subIndex];
      const water = (surface & 1) !== 0;
      const subSurfaceHeight = water ? 62 : predicted.heights[index];
      const subKind = predictionKind(
        biome, water, subSurfaceHeight,
        worldX + (sample & 1) * subStride,
        worldZ + (sample >> 1) * subStride
      );
      const color = subKind === 'WATER'
        ? predictedWaterBaseColor(biome, surface >>> 8, 1)
        : subKind === 'FOLIAGE'
          || predicted.subCanopies[subIndex] > 0
            && subKind !== 'ICE' && subKind !== 'VOID'
          ? biome.canopyColor
          : kindColor(subKind, biome);
      red += color >> 16;
      green += color >> 8 & 255;
      blue += color & 255;
    }
    return Math.floor(red / 4) << 16 | Math.floor(green / 4) << 8
      | Math.floor(blue / 4);
  }

  function predictionKind(biome, water, surfaceHeight, worldX, worldZ) {
    if (water) {
      const coldSurface = biome.kind === 'ICE' || biome.kind === 'SNOW';
      return coldSurface && frozenOceanFreezesAtSeaLevel(biome.id, worldX, worldZ)
        ? 'ICE' : 'WATER';
    }
    return biome.kind === 'LAND' && biome.snowLine != null
      && surfaceHeight >= biome.snowLine ? 'SNOW' : biome.kind;
  }

  function kindColor(kind, biome) {
    if (kind === 'SNOW') return 0xf7faff;
    if (kind === 'ICE') return 0xa4c6e8;
    if (kind === 'SAND') return 0xddce9b;
    if (kind === 'FOLIAGE') return biome.canopyColor;
    return biome.surfaceColor;
  }

  function predictedWaterColor(biome, depth, predicted, index, lod) {
    return predictedWaterBaseColor(
      biome, depth, reliefMultiplier(predicted, index, lod, true)
    );
  }

  function predictedWaterBaseColor(biome, depth, floorRelief) {
    const tint = biome.waterBiome ? 0x3f76e4 : biome.waterTint;
    const water = multiplyColor(0xcfe0f2, tint);
    const floorBrightness = Math.max(
      Math.fround(0.25),
      Math.fround(Math.fround(1) - Math.fround(Math.max(0, depth) / Math.fround(48)))
    );
    const floor = applyBrightness(
      scaleColorTruncate(0xc2a876, floorBrightness), floorRelief
    );
    return blendOver(floor, water, 0xcc);
  }

  function renderCorrectedArgb(
    predicted, patch, lod, netherRoof, blockX = 0, blockZ = 0
  ) {
    if (!predicted) return renderAuthorityOnlyArgb(patch, lod, netherRoof, blockX, blockZ);
    const cells = 258 * 258;
    const surface = new Int32Array(cells);
    const kinds = new Uint8Array(cells);
    const fluids = new Uint8Array(cells);
    const biomes = new Int32Array(predicted.biomes);
    for (let index = 0; index < cells; index++) {
      const empty = (predicted.surfaces[index] & 2) !== 0;
      const water = (predicted.surfaces[index] & 1) !== 0;
      const x = index % 258 - 1;
      const z = Math.floor(index / 258) - 1;
      const biome = predictionBiomes.get(biomes[index]) ?? fallbackPredictionBiome;
      let kind = empty ? 9 : kindOrdinal(predictionKind(
        biome, water, water ? 62 : predicted.heights[index],
        blockX + (x << lod), blockZ + (z << lod)
      ));
      if (predicted.canopies[index] > 0 && kind !== 2 && kind !== 6 && kind !== 9) {
        kind = 4;
      }
      kinds[index] = kind;
      fluids[index] = predicted.surfaces[index] >>> 8;
      surface[index] = water ? 62 : predicted.heights[index] + predicted.canopies[index];
    }
    const corrected = new Uint8Array(256 * 256);
    const colors = new Uint8Array(256 * 256).fill(255);
    const floorColors = new Uint8Array(256 * 256).fill(255);
    const materialIds = new Array(256 * 256).fill('');
    const floorMaterialIds = new Array(256 * 256).fill('');
    for (let sample = 0; sample < patch.indexes.length; sample++) {
      const pixel = patch.indexes[sample];
      const kind = patch.kinds[sample];
      if (kind === 0) continue;
      const x = pixel & 255;
      const z = pixel >>> 8;
      const grid = (z + 1) * 258 + x + 1;
      surface[grid] = patch.heights[sample];
      kinds[grid] = kind;
      fluids[grid] = patch.fluids[sample];
      biomes[grid] = patch.biomes[sample];
      colors[pixel] = patch.colors[sample];
      floorColors[pixel] = patch.floorColors?.[sample] ?? 255;
      materialIds[pixel] = patch.materialIds?.[sample] ?? '';
      floorMaterialIds[pixel] = patch.floorMaterialIds?.[sample] ?? '';
      corrected[pixel] = 1;
    }
    const output = new Uint32Array(256 * 256);
    for (let z = 0; z < 256; z++) for (let x = 0; x < 256; x++) {
      const pixel = z * 256 + x;
      const grid = (z + 1) * 258 + x + 1;
      const kind = kinds[grid];
      if (kind === 0 || kind === 9) continue;
      const biome = predictionBiomes.get(biomes[grid]) ?? fallbackPredictionBiome;
      const worldX = blockX + (x << lod);
      const worldZ = blockZ + (z << lod);
      let color;
      if (corrected[pixel]) {
        color = correctedBaseColor(
          kind, biome, fluids[grid], colors[pixel], floorColors[pixel],
          materialIds[pixel], floorMaterialIds[pixel], worldX, worldZ,
          surface, kinds, fluids, grid, lod
        );
      } else if (predicted.subBiomes.length) {
        color = averagedSubColor(predicted, grid, worldX, worldZ, lod);
      } else if (kind === 2) {
        color = predictedWaterBaseColor(
          biome, fluids[grid], planeRelief(surface, kinds, fluids, grid, lod, true)
        );
      } else {
        color = kind === 4 ? biome.canopyColor : kindColor(kindName(kind), biome);
      }
      if (!netherRoof) color = applyHeightShade(color, surface[grid]);
      color = applyBrightness(
        color, planeRelief(surface, kinds, fluids, grid, lod, false)
      );
      if (netherRoof) color = applyNetherLight(color, patch.lights?.[pixel] ?? 0);
      output[pixel] = (0xff000000 | color) >>> 0;
    }
    return output;
  }

  function drawCorrectedTile(
    ctx, predicted, patch, lod, netherRoof, blockX = 0, blockZ = 0
  ) {
    const argb = renderCorrectedArgb(
      predicted, patch, lod, netherRoof, blockX, blockZ
    );
    const image = ctx.createImageData(256, 256);
    for (let index = 0; index < argb.length; index++) {
      const color = argb[index];
      const pixel = index * 4;
      image.data[pixel] = color >>> 16 & 255;
      image.data[pixel + 1] = color >>> 8 & 255;
      image.data[pixel + 2] = color & 255;
      image.data[pixel + 3] = color >>> 24;
    }
    ctx.putImageData(image, 0, 0);
  }

  function correctedBaseColor(
    kind, biome, fluidDepth, mapColorId, floorMapColorId,
    materialId, floorMaterialId, worldX, worldZ,
    surface, kinds, fluids, grid, lod
  ) {
    if (kind === 2) {
      const tint = biome.waterBiome ? 0x3f76e4 : biome.waterTint;
      const fallbackWater = multiplyColor(0xcfe0f2, tint);
      const water = materialColor(
        materialId, biome, fallbackWater, worldX, worldZ
      );
      const fallbackFloor = paintsLiteralMapColor(floorMapColorId)
        ? mapColor(floorMapColorId) : 0xc2a876;
      const floorBase = materialColor(
        floorMaterialId, biome, fallbackFloor, worldX, worldZ
      );
      const brightness = Math.max(
        Math.fround(0.25),
        Math.fround(Math.fround(1) - Math.fround(Math.max(0, fluidDepth) / Math.fround(48)))
      );
      const floor = applyBrightness(
        scaleColorTruncate(floorBase, brightness),
        planeRelief(surface, kinds, fluids, grid, lod, true)
      );
      return blendOver(floor, water, 0xcc);
    }
    const fallback = paintsLiteralMapColor(mapColorId)
      ? mapColor(mapColorId) : kindColor(kindName(kind), biome);
    return materialColor(materialId, biome, fallback, worldX, worldZ);
  }

  function materialColor(materialId, biome, fallback, worldX, worldZ) {
    const sample = materialSamples.get(materialId);
    if (!sample) return fallback;
    let tint = 0xffffff;
    if (sample.tint === 'GRASS') tint = biome.grassTint ?? 0xffffff;
    else if (sample.tint === 'FOLIAGE') tint = biome.foliageTint ?? 0xffffff;
    else if (sample.tint === 'WATER') tint = biome.waterTint;
    else if (sample.tint === 'FIXED') tint = sample.fixedTintArgb & 0xffffff;
    const colored = multiplyColor(sample.baseArgb & 0xffffff, tint);
    const offsets = sample.detailOffsets;
    if (!Array.isArray(offsets) || offsets.length !== 16) return colored;
    const cell = materialHash(worldX, worldZ, sample.patternSalt) >>> 28;
    return applyBrightness(colored, 1 + offsets[cell]);
  }

  function renderAuthorityOnlyArgb(patch, lod, netherRoof, blockX, blockZ) {
    const output = new Uint32Array(256 * 256);
    const transparentPrediction = {
      biomes: new Int32Array(258 * 258), heights: new Int32Array(258 * 258),
      surfaces: new Int32Array(258 * 258).fill(2), canopies: new Int32Array(258 * 258),
      subBiomes: new Int32Array(), subSurfaces: new Int32Array(), subCanopies: new Int32Array()
    };
    const composed = renderCorrectedArgb(
      transparentPrediction, patch, lod, netherRoof, blockX, blockZ
    );
    for (const pixel of patch.indexes) output[pixel] = composed[pixel];
    return output;
  }

  async function decodeTilePatch(compressed) {
    const stream = new Blob([compressed]).stream()
      .pipeThrough(new DecompressionStream('deflate'));
    const raw = new Uint8Array(await new Response(stream).arrayBuffer());
    const reader = new Reader(raw);
    const version = reader.u8();
    if (version < 3 || version > 5) throw new Error(`unsupported tile codec ${version}`);
    const evaluated = readSparseTileMask(reader);
    const difference = readSparseTileMask(reader);
    const indexes = bits(difference);
    const count = indexes.length;
    const biomes = reader.bytes(count);
    const heights = new Int32Array(count);
    let height = 0;
    for (let i = 0; i < count; i++) {
      height += reader.zigzag();
      heights[i] = height;
    }
    const kinds = reader.bytes(count);
    const colors = reader.bytes(count);
    const fluids = reader.bytes(count);
    const floorColors = reader.bytes(count);
    const materialIds = new Array(count).fill('');
    const floorMaterialIds = new Array(count).fill('');
    if (version >= 5) {
      const decoder = new TextDecoder();
      const materials = new Array(reader.u16());
      for (let i = 0; i < materials.length; i++) {
        materials[i] = decoder.decode(reader.bytes(reader.u16()));
      }
      readMaterialPlane(reader, materials, materialIds);
      readMaterialPlane(reader, materials, floorMaterialIds);
    }
    const lights = new Uint8Array(256 * 256);
    if (version >= 4) {
      const evaluatedIndexes = bits(evaluated);
      for (let i = 0; i < evaluatedIndexes.length; i++) reader.zigzagLong();
      for (const index of evaluatedIndexes) lights[index] = reader.u8();
    }
    return {
      width: 256, height: 256, indexes, biomes, heights, kinds, colors, fluids,
      floorColors, materialIds, floorMaterialIds, lights
    };
  }

  return {
    decodeTilePatch, drawCorrectedTile, drawPredictedTile,
    renderCorrectedArgb, renderPredictedArgb
  };
}

let frozenOceanNoise;
let foliageNoise;

export function frozenOceanFreezesAtSeaLevel(biomeId, blockX, blockZ) {
  if (biomeId === 50) return false;
  if (biomeId !== 10) return true;
  frozenOceanNoise ??= octaveSimplexNoise(3456n, 3);
  foliageNoise ??= octaveSimplexNoise(2345n, 1);
  const broad = frozenOceanNoise(blockX * 0.05, blockZ * 0.05) * 7;
  const detail = foliageNoise(blockX * 0.2, blockZ * 0.2);
  const warmPatch = broad + detail < 0.3
    && foliageNoise(blockX * 0.09, blockZ * 0.09) < 0.8;
  return !warmPatch;
}

function octaveSimplexNoise(seed, octaveCount) {
  const random = new JavaRandom(seed);
  const octaves = Array.from({length: octaveCount}, () => simplexNoise(random));
  const persistence = 1 / (Math.pow(2, octaveCount) - 1);
  return (x, z) => {
    let result = 0, frequency = 1, amplitude = persistence;
    for (const octave of octaves) {
      result += octave(x * frequency, z * frequency) * amplitude;
      frequency /= 2;
      amplitude *= 2;
    }
    return result;
  };
}

function simplexNoise(random) {
  random.nextDouble();
  random.nextDouble();
  random.nextDouble();
  const permutation = Array.from({length: 256}, (_, index) => index);
  for (let index = 0; index < permutation.length; index++) {
    const swap = index + random.nextInt(256 - index);
    [permutation[index], permutation[swap]] = [permutation[swap], permutation[index]];
  }
  const gradients = [
    [1, 1],[-1, 1],[1, -1],[-1, -1],
    [1, 0],[-1, 0],[1, 0],[-1, 0],
    [0, 1],[0, -1],[0, 1],[0, -1]
  ];
  const skewFactor = 0.5 * (Math.sqrt(3) - 1);
  const unskewFactor = (3 - Math.sqrt(3)) / 6;
  const mapped = value => permutation[value & 255];
  const gradient = (index, x, z) => {
    let attenuation = 0.5 - x * x - z * z;
    if (attenuation < 0) return 0;
    attenuation *= attenuation;
    const direction = gradients[index];
    return attenuation * attenuation * (direction[0] * x + direction[1] * z);
  };
  return (x, z) => {
    const skew = (x + z) * skewFactor;
    const cellX = Math.floor(x + skew);
    const cellZ = Math.floor(z + skew);
    const unskew = (cellX + cellZ) * unskewFactor;
    const localX = x - (cellX - unskew);
    const localZ = z - (cellZ - unskew);
    const stepX = localX > localZ ? 1 : 0;
    const stepZ = localX > localZ ? 0 : 1;
    const middleX = localX - stepX + unskewFactor;
    const middleZ = localZ - stepZ + unskewFactor;
    const farX = localX - 1 + 2 * unskewFactor;
    const farZ = localZ - 1 + 2 * unskewFactor;
    const wrappedX = cellX & 255;
    const wrappedZ = cellZ & 255;
    const near = mapped(wrappedX + mapped(wrappedZ)) % 12;
    const middle = mapped(wrappedX + stepX + mapped(wrappedZ + stepZ)) % 12;
    const far = mapped(wrappedX + 1 + mapped(wrappedZ + 1)) % 12;
    return 70 * (
      gradient(near, localX, localZ)
      + gradient(middle, middleX, middleZ)
      + gradient(far, farX, farZ)
    );
  };
}

class JavaRandom {
  constructor(seed) {
    this.seed = (seed ^ 0x5deece66dn) & ((1n << 48n) - 1n);
  }

  next(bits) {
    this.seed = (this.seed * 0x5deece66dn + 0xbn) & ((1n << 48n) - 1n);
    return Number(this.seed >> BigInt(48 - bits));
  }

  nextDouble() {
    return (this.next(26) * 0x8000000 + this.next(27)) / 0x20000000000000;
  }

  nextInt(bound) {
    if ((bound & -bound) === bound) {
      return Math.floor(bound * this.next(31) / 0x80000000);
    }
    let bits, value;
    do {
      bits = this.next(31);
      value = bits % bound;
    } while ((bits - value + bound - 1 | 0) < 0);
    return value;
  }
}

function kindOrdinal(kind) {
  return ['UNKNOWN','LAND','WATER','LAVA','FOLIAGE','SNOW','ICE','SAND','BEDROCK_CEILING','VOID']
    .indexOf(kind);
}

function kindName(kind) {
  return ['UNKNOWN','LAND','WATER','LAVA','FOLIAGE','SNOW','ICE','SAND','BEDROCK_CEILING','VOID'][kind]
    ?? 'UNKNOWN';
}

function paintsLiteralMapColor(id) {
  return id !== 255 && id !== 1 && id !== 7;
}

function planeRelief(surface, kinds, fluids, index, lod, floorPlane) {
  const height = sample => {
    const kind = kinds[sample];
    if (kind === 0 || kind === 9) return null;
    return surface[sample] - (floorPlane && kind === 2 ? fluids[sample] : 0);
  };
  if (lod === 0) {
    const lit = height(index + 257);
    const center = height(index);
    if (lit == null || center == null) return 1;
    return 1 + 0.3 * Math.max(-1, Math.min(1, (lit - center) / 2));
  }
  const samples = [index - 1, index + 258, index + 257,
    index + 1, index - 258, index - 257].map(height);
  if (samples.some(value => value == null)) return 1;
  const lit = (samples[0] + samples[1] + samples[2]) / 3;
  const dark = (samples[3] + samples[4] + samples[5]) / 3;
  return 1 + 0.3 * Math.max(-1, Math.min(1, (lit - dark) / (2 * (1 << lod))));
}

function materialHash(x, z, salt) {
  let value = Math.imul(salt, 0x9e3779b9);
  value ^= Math.imul(x, 0x85ebca6b);
  value = value << 13 | value >>> 19;
  value ^= Math.imul(z, 0xc2b2ae35);
  value ^= value >>> 16;
  value = Math.imul(value, 0x7feb352d);
  value ^= value >>> 15;
  return value >>> 0;
}

function readMaterialPlane(reader, materials, output) {
  for (let i = 0; i < output.length; i++) {
    const index = reader.u16();
    if (index >= materials.length) throw new Error(`material index ${index} out of range`);
    output[i] = materials[index];
  }
}

function readSparseTileMask(reader) {
  const coarse = reader.bytes(32);
  const output = new Uint8Array(8192);
  for (let coarseIndex = 0; coarseIndex < 256; coarseIndex++) {
    if (!(coarse[coarseIndex >> 3] & 1 << (coarseIndex & 7))) continue;
    const fine = reader.bytes(32);
    for (let fineIndex = 0; fineIndex < 256; fineIndex++) {
      if (!(fine[fineIndex >> 3] & 1 << (fineIndex & 7))) continue;
      const x = (coarseIndex & 15) << 4 | fineIndex & 15;
      const z = (coarseIndex >> 4) << 4 | fineIndex >> 4;
      const pixel = z << 8 | x;
      output[pixel >> 3] |= 1 << (pixel & 7);
    }
  }
  return output;
}

function bits(mask) {
  const output = [];
  for (let index = 0; index < mask.length * 8; index++) {
    if (mask[index >> 3] & 1 << (index & 7)) output.push(index);
  }
  return output;
}

class Reader {
  constructor(bytes) {
    this.bytesValue = bytes;
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    this.position = 0;
  }
  u8() { return this.view.getUint8(this.position++); }
  u16() { const value = this.view.getUint16(this.position); this.position += 2; return value; }
  bytes(length) {
    const value = this.bytesValue.slice(this.position, this.position + length);
    this.position += length;
    return value;
  }
  varint() {
    let value = 0, shift = 0, current;
    do {
      current = this.u8();
      value |= (current & 127) << shift;
      shift += 7;
    } while (current & 128);
    return value;
  }
  zigzag() { const value = this.varint(); return value >>> 1 ^ -(value & 1); }
  zigzagLong() {
    let value = 0n, shift = 0n, current;
    do {
      current = BigInt(this.u8());
      value |= (current & 127n) << shift;
      shift += 7n;
    } while (current & 128n);
    return value >> 1n ^ -(value & 1n);
  }
}

function reliefMultiplier(predicted, index, lod, floorPlane) {
  if (lod === 0) {
    if (predictionVoid(predicted, index + 257) || predictionVoid(predicted, index)) return 1;
    const lit = predictionHeight(predicted, index + 257, floorPlane);
    const center = predictionHeight(predicted, index, floorPlane);
    return 1 + 0.3 * Math.max(-1, Math.min(1, (lit - center) / 2));
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
  return shadeColor(color >> 16, shade) << 16
    | shadeColor(color >> 8 & 255, shade) << 8
    | shadeColor(color & 255, shade);
}

function shadeColor(channel, shade) {
  return clampColor(Math.round(shade > 0
    ? channel + shade * (255 - channel) : channel * (1 + shade)));
}

function applyBrightness(color, factor) {
  return clampColor(Math.round((color >> 16) * factor)) << 16
    | clampColor(Math.round((color >> 8 & 255) * factor)) << 8
    | clampColor(Math.round((color & 255) * factor));
}

function scaleColorTruncate(color, factor) {
  return Math.trunc(Math.fround((color >> 16) * factor)) << 16
    | Math.trunc(Math.fround((color >> 8 & 255) * factor)) << 8
    | Math.trunc(Math.fround((color & 255) * factor));
}

function multiplyColor(base, tint) {
  return Math.floor((base >> 16) * (tint >> 16) / 255) << 16
    | Math.floor((base >> 8 & 255) * (tint >> 8 & 255) / 255) << 8
    | Math.floor((base & 255) * (tint & 255) / 255);
}

function blendOver(bottom, top, alpha) {
  const inverse = 255 - alpha;
  return Math.floor(((top >> 16) * alpha + (bottom >> 16) * inverse) / 255) << 16
    | Math.floor(((top >> 8 & 255) * alpha + (bottom >> 8 & 255) * inverse) / 255) << 8
    | Math.floor(((top & 255) * alpha + (bottom & 255) * inverse) / 255);
}

function applyNetherLight(color, blockLevel) {
  const ambient = netherLightTint(0);
  const base = multiplyColor(color, ambient);
  if (!blockLevel) return base;
  const lit = netherLightTint(blockLevel);
  return Math.min(255, Math.round((base >> 16) * ((lit >> 16) / (ambient >> 16)))) << 16
    | Math.min(255, Math.round((base >> 8 & 255) * ((lit >> 8 & 255) / (ambient >> 8 & 255)))) << 8
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

function mapColor(id) {
  return MAP_COLORS[id] ?? 0x969696;
}

function mix(value, target, amount) {
  return value + (target - value) * amount;
}

function clampColor(channel) {
  return Math.max(0, Math.min(255, channel));
}
