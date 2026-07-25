package cn.net.rms.confluxmap.core.predict;

/** One seed-predicted, version-specific natural vegetation placement candidate. */
public record TreeCandidate(int x, int y, int z, int type, int biomeId, int flags) {
}
