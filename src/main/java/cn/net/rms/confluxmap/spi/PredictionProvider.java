package cn.net.rms.confluxmap.spi;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import java.util.Optional;

/**
 * Extension seam reserved for M2 seed-based chunk prediction, planned to be backed by
 * a Java port/binding of <a href="https://github.com/Cubitect/cubiomes">cubiomes</a>
 * (MIT-licensed - see {@code THIRD_PARTY_NOTICES.md} once that adaptation actually
 * lands). M1 has no implementation of this interface and nothing in the mod calls it
 * yet; {@link ChunkSnapshot}s are produced exclusively by real chunk capture
 * ({@code mc.snapshot}) today.
 *
 * <p><b>Planned M2 shape.</b> Given the world seed, a provider reproduces the vanilla
 * world generator's biome and terrain-height decisions well enough to synthesize a
 * plausible {@link ChunkSnapshot} for a column the player has never actually loaded.
 * {@code core.store.ColumnStore} would merge that in as a
 * {@code SampleSource.PREDICTED} entry, below {@code REAL_LIVE}/{@code REAL_CACHED}
 * and above {@code UNKNOWN} in the merge priority (see the M1 plan's store package
 * note) - so a real snapshot always overwrites a stale prediction the instant the
 * player actually explores that column, and the fullscreen map can show a "greyed-out
 * guess" for unexplored terrain instead of blank void in the meantime.
 *
 * <p>Implementations must stay outside {@code core}/{@code bridge}/{@code spi}'s
 * Minecraft-free isolation only in the sense that the *seed and dimension registry
 * lookup* they need comes from {@code mc}; the provider implementation itself may
 * still live in {@code core} (or a future {@code predict} package) as long as it only
 * consumes the seed/version data it's handed, not live MC objects.
 */
public interface PredictionProvider {
    /**
     * Best-effort predicted snapshot for one chunk column, or {@link Optional#empty()}
     * if this provider cannot predict the given dimension/layer combination right now
     * (no seed known yet, an unsupported dimension or world generator, or a layer that
     * isn't terrain-shaped at all, such as a manual Y-slice).
     */
    Optional<ChunkSnapshot> predict(DimensionId dimensionId, int chunkX, int chunkZ, MapLayer layer);

    /**
     * Capability probe: whether this provider is currently able to predict anything
     * for the given dimension - e.g. the world seed is known and a matching generator
     * profile is loaded. Callers should check this once per session/dimension rather
     * than calling {@link #predict} speculatively for every missing column and reading
     * {@link Optional#empty()} back each time.
     */
    boolean isAvailable(DimensionId dimensionId);
}
