package cn.net.rms.confluxmap.core.predict;

/** One seed-predicted 1.17.1 tree-like vegetation placement candidate. */
public record TreeCandidate(int x, int y, int z, int type, int biomeId, int flags) {
}
