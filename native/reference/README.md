# Nether fossil vanilla references

These standalone programs compile against **Mojang-mapped Minecraft jars** and
the matching vanilla libraries. They do not load Conflux Map or cubiomes.
They are intentionally outside the mod's Yarn-preprocessed sources.

| Version | Program | Source of expected validity |
| --- | --- | --- |
| 1.17.1 | `NetherFossilOracle117` | Vanilla RNG, biome source and `getBaseColumn`; landing loop transcribed from `NetherFossilFeature.FeatureStart.generatePieces` |
| 1.18.2 | `NetherFossilOracle118` | Calls vanilla `NetherFossilFeature.pieceGeneratorSupplier` |
| 1.21.1, 26.2 | `NetherFossilOracleModern` | Calls vanilla `Structure.findValidGenerationPoint` |

Use the normal vanilla Nether settings with a 256-block dimension height (the
noise generator itself is 128 blocks tall). The modern programs use a direct
soul-sand-valley predicate because the bootstrap registries do not bind datapack
tags. This matches the jar's `has_structure/nether_fossil` tag. Template placement
and customized datapacks are outside these references.

Set `FOSSIL_REFERENCE_CP` to the Mojang-mapped jar plus the libraries for that
version, separated by your platform's classpath separator. Loom's cache may
contain both Yarn and Mojang-mapped jars; select the latter. Do not mix library
versions. Use Java 25 for 26.2 and a suitable Java runtime for older versions.
For example, from this directory with the 1.17.1 classpath:

```sh
mkdir -p /tmp/nether-fossil-reference
javac -proc:none -d /tmp/nether-fossil-reference -cp "$FOSSIL_REFERENCE_CP" NetherFossilOracle117.java
cd /tmp/nether-fossil-reference
java -cp ".:$FOSSIL_REFERENCE_CP" NetherFossilOracle117 146008555 > oracle.log
```

Run in a scratch directory because vanilla bootstrap writes `logs/`. Pack its
output from this reference directory with:

```sh
python pack_nether_fossils.py 1.17.1 /tmp/nether-fossil-reference/oracle.log
```

Repeat for seeds `146008555`, `0`, `1`, `-1`, and `3791470854434097407`.
For other versions select the corresponding program in the table and compile
into a separate scratch directory. Each run examines 1,024 attempt chunks in
regions `[-16,15]` on each axis, with Z as the outer loop. Its output includes
the random anchor, starting height, validity, and the 128-block base-column
air/non-air mask for diagnostic comparisons.

The packed rows live in
`common/src/test/resources/cn/net/rms/confluxmap/nativepredict/nether-fossils.csv`.
`NetherFossilNativeTest` compares every accepted and rejected location, the batch
query, and the nearest result against those independent fixtures. Run:

```sh
./gradlew :common:test --tests 'cn.net.rms.confluxmap.nativepredict.NetherFossilNativeTest' --no-daemon --max-workers=1 --no-parallel
```

Run the Gradle command from the repository root. Rebuild native artifacts in a
**separate Gradle invocation** before running this test; native builds are
deliberately outside the normal verification lifecycle.
