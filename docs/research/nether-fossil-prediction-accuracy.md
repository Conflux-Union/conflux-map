# Nether fossil prediction accuracy

Research date: 2026-09-05. Scope: the pinned Conflux cubiomes fork, its xpple
ancestry, current online upstream sources, and vanilla Java world generation.

Implementation update: the investigation below describes the original
`9afc103` baseline. The subsequent repair and measured results are recorded at
the end of this document.

## Result

The current predictor returns biome-qualified generation attempts, not confirmed
Nether fossils. It omits vanilla's random column and terrain rejection. The
32-block placement grid is correct for the inspected modern vanilla data, so
high candidate density is partly intrinsic and partly unfiltered failed attempts.
No measured precision percentage is available from this investigation.

## Fork provenance and runtime boundary

- The pinned submodule is `9afc1038ea5a7b1fd22c8e7ce18dae46b4ca5891`.
- Merge `408c4199c019e4f433a490836e3dae24b9f78d57` incorporates
  `xpple/cubiomes` at `cf7123b`. Comparing only Cubitect upstream is insufficient.
- xpple commit `6d8aeb15f4f050e39f1c1acad1cf4893af0cda01` added Nether
  terrain; `67c562b` subsequently fixed region indexing. Both are in this pin.
- `terrainnoise.c` provides `sampleNetherNoiseColumn` and
  `generateNetherColumn`, producing a 128-block solid/non-solid column.
  `setupTerrainNoise` and `initTerrainNoise` reject MC <= 1.17.
- This file is absent from `build.gradle`'s `nativeSources`. Its presence in
  the submodule does not mean the JNI predictor uses it.
- Conflux commit `9afc103` adds fossil placement and the soul-sand-valley
  whitelist, but no terrain check.
- The online xpple master inspected on the research date now also has fossil
  placement, random offsets, initial height, rotation, and template information
  in `getVariant`. Its `isViableStructurePos` still uses the generic Nether
  biome check for fossils. Updating that function alone would not fill the
  terrain-validation gap. Local `xpple/master` is an older cached reference.

Sources: local `git log --merges`, `git show 408c419`, `git show 9afc103`,
[build configuration](../../build.gradle),
[pinned terrain implementation](../../native/cubiomes/terrainnoise.c),
[online xpple finders](https://github.com/xpple/cubiomes/blob/master/finders.c),
[xpple Nether terrain commit](https://github.com/xpple/cubiomes/commit/6d8aeb15f4f050e39f1c1acad1cf4893af0cda01).

## Current prediction path

`StructureMarkerService` calls `CubiomesContext.viableStructures`, which reaches
the JNI `cfxIsViableStructure` helper. The helper calls cubiomes
`isViableStructurePos`; only End City gets an additional terrain check.

Fossil placement uses salt 14357921, region size 2 chunks, and a one-chunk
selection range. Therefore the generation-attempt chunk is the region origin:
one attempt per 32 x 32 blocks. An aligned 512 x 512 block area has 256 attempts
before biome filtering. This is arithmetic from placement, not a measured
number of actual fossils.

The biome check samples quart coordinates `(chunkX * 4 + 2, 0, chunkZ * 4 + 2)`
and accepts soul sand valley. The emitted marker is the chunk origin, not the
random fossil anchor. `getNetherBiome` forces noise Y to zero, so adding more
vertical biome samples does not supply the missing terrain information.

Sources: [marker service](../../src/main/java/cn/net/rms/confluxmap/mc/predict/StructureMarkerService.java),
[JNI helper](../../native/shim/confluxnative.c),
[placement and viability](../../native/cubiomes/finders.c),
[Nether biome noise](../../native/cubiomes/biomenoise.c).

## Vanilla comparison

Inspected local Minecraft jars with `javap -c -p`:

- 1.17.1: `NetherFossilFeature$FeatureStart.generatePieces`.
- 1.21.1 and 26.2: `NetherFossilStructure.findGenerationPoint`.
- 26.2: `Structure.findValidGenerationPoint` and `Structure.isValidBiome`.

All three inspected versions choose random X/Z offsets within the chunk, sample
a starting height, obtain `ChunkGenerator.getBaseColumn`, and search downward
for air over soul sand or a block with a sturdy upper face. Failure to find a
valid position above sea level cancels the structure. The exact counter and
anchor handling should be preserved per version when implementing this logic.
The condition does not require soul sand exclusively.

The 1.21.1 and 26.2 jar resources agree on spacing 2, separation 1, salt
14357921, the soul-sand-valley biome tag, and a uniform initial height from
absolute 32 to `below_top: 2`. Resources inspected:

- `data/minecraft/worldgen/structure_set/nether_fossils.json`
- `data/minecraft/worldgen/structure/nether_fossil.json`
- `data/minecraft/tags/worldgen/biome/has_structure/nether_fossil.json`

Modern vanilla validates the biome at the returned generation anchor. Sampling
the chunk center instead can therefore cause both false positives and false
negatives at biome boundaries. Missing terrain rejection independently allows
false positives even inside a large soul sand valley.

The jars were read from the local Fabric Loom cache. For 26.2 the reproducible
command is `javap -c -p -classpath ~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar net.minecraft.world.level.levelgen.structure.structures.NetherFossilStructure`.
The older jars are mapped artifacts under the cache's `minecraftMaven` directory.

## Recommended next implementation and validation

1. Reproduce vanilla's version-specific structure RNG and anchor biome check.
2. Evaluate the existing xpple Nether column sampler against vanilla
   `getBaseColumn` before using it to reject candidates. Reuse that foundation
   where accurate; establish a separate legacy path for 1.17. A full rendered
   chunk is not the input used by vanilla's fossil validity check.
3. Compare predicted and actual valid structure starts over fixed seeds and
   bounded areas, recording precision, recall, counts, version, and generation
   settings. Include biome boundaries and negative coordinates. Test fossil
   anchors separately from marker chunk origins.
4. Measure query cost and verify affected native platforms before shipping.
   Keep display thinning a separate decision: hiding candidates cannot improve
   their probability of being real.

The existing `strongholdsAndNetherFossilsUseDedicatedVanillaPlacementRules` test
checks agreement between native lookup and native viability. It does not compare
against vanilla and therefore does not establish accuracy.

The initial investigation was source, bytecode, resource, and build-wiring
analysis. The implementation work below subsequently added runtime comparisons.

## Implemented repair and independent sample results

The repair at cubiomes `ee426009a596` reuses the already-compiled xpple
`initBlendedNoise` / `sampleBase3dNoise` primitives. It does not import the whole
`terrainnoise.c` module. Default Nether noise initialization and its 4x8x4
lattice can be reused for 1.17.1 as well as modern versions. Interpolate the
slid corner densities before applying any sign-preserving density squeeze;
squeezing corners before interpolation can change the solid/air boundary.

`isViableStructurePos` now applies the fossil-specific terrain and anchor-biome
check for 1.17+. The JNI helper reuses a lazily initialized, context-owned noise
object so single, batch, and nearest queries all use the same check. Chunk-origin
marker coordinates remain unchanged. The 1.17 air-height versus modern
support-height sea-level boundary is handled explicitly.

The standalone references in [native/reference](../../native/reference/README.md)
examined five seeds and 1,024 attempts per seed for each of four versions:
20,480 validity decisions. All matched the repaired native implementation.
For 1.17.1, validity uses vanilla columns and biomes plus its transcribed landing
loop; the other versions invoke vanilla's actual generation-point/supplier method.

| Version | Old candidates | Vanilla-valid attempts | Old false positives | Old false negatives | New mismatches |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1.17.1 | 1,257 | 610 | 647 | 0 | 0 |
| 1.18.2 | 1,257 | 600 | 673 | 16 | 0 |
| 1.21.1 | 1,257 | 600 | 673 | 16 | 0 |
| 26.2 | 1,257 | 600 | 673 | 16 | 0 |

These are bounded sample results, not a universal 100% accuracy guarantee.
Biome boundaries account for the recovered modern false negatives. In these
samples the displayed candidate population before UI thinning falls by about
half. Marker thinning itself was not changed.

The JNI ABI stays at 12: function signatures, ordinals, and payload contracts did
not change. `PredictorVersion.CUBIOMES_COMMIT_12` identifies the new behavior.
Bundled native extraction is content-hashed, so a rebuilt library has a new
extraction path even with the same ABI. The browser WASM API only predicts map
tiles; it does not expose the structure queries changed here.

## Final verification

- Direct cubiomes calls and the JNI single/batch/nearest tests match the 20,480
  reference decisions. The 20 parameterized JNI tests have no skips.
- `:common:check`: 1,065 tests, no failures or skips.
- All 12 Fabric build targets pass their build lifecycle: 1.17.1, 1.18.2,
  1.20.1, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.9, 1.21.11,
  26.1.2, and 26.2. Their test reports contain 6,146 tests, 176 existing
  assumption skips, and no failures.
- `:paper:check`: 36 tests, one assumption skip, no failures.
- `buildNativesAll` succeeds for Linux x86_64/aarch64, Windows x86_64, and
  macOS x86_64/aarch64. Each final Fabric jar was checked byte-for-byte against
  all five rebuilt native artifacts, and its nested common jar contains the new
  predictor identity. The 20 JNI exports are unchanged.
- `buildWebPredictor` succeeds. Its inputs are unchanged by this repair, so the
  prior committed WASM artifact was retained and verified in the final jars.
- The three checked-in Java reference programs were recompiled for all four
  reference versions and reproduced the stored seed-146008555 fixture rows.

Native runtime verification was on Linux x86_64. Other native targets were
cross-built, not executed. No in-game visual acceptance or full placed-template
world test was performed. This change does not claim those checks passed.
