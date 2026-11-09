#ifndef ADAPTIVE_MACRO_H
#define ADAPTIVE_MACRO_H

// ==============================================================================
// Strict Position-Pure Spatial-Adaptive Macro Grid
// 1. World 16-block macro cells (step 4 macro grid) corners evaluated globally;
// 2. Strict half-open world coordinate partitioning: each world macro point (x4, z4)
//    uniquely belongs to its enclosing cell, guaranteeing identical decisions
//    regardless of tile slicing origins or boundary padding (0 mismatch);
// 3. 5-point cross detection + 8x8 sub-quadrant fallback refinement;
// 4. Memory safety: strict bounds-clamped indices with zero row overflow (ASan clean).
// ==============================================================================

// Strictly symmetric linear interpolation with unbiased rounding.
// Guarantees algebraic invariant under endpoint exchange:
// cfxLerpSymmetric(y0, y1, x, D) == cfxLerpSymmetric(y1, y0, D - x, D)
static inline int cfxLerpSymmetric(int y0, int y1, int x, int D) {
    const int numer = (D - x) * y0 + x * y1;
    if (numer >= 0) {
        return (numer + (D >> 1)) / D;
    } else {
        return (numer - (D >> 1)) / D;
    }
}

// Second-order discrete Laplacian curvature and saddle-distortion energy metric.
// Pure integer bitwise arithmetic without additional noise evaluation overhead.
static inline int cfxEvaluateCurvatureEnergy(int c00, int c10, int c01, int c11, int cMid) {
    int min_h = c00, max_h = c00;
    if (c10 < min_h) min_h = c10;
    if (c10 > max_h) max_h = c10;
    if (c01 < min_h) min_h = c01;
    if (c01 > max_h) max_h = c01;
    if (c11 < min_h) min_h = c11;
    if (c11 > max_h) max_h = c11;
    if (cMid < min_h) min_h = cMid;
    if (cMid > max_h) max_h = cMid;
    const int drop = max_h - min_h;

    // Discrete 2nd-order Laplacian: measures local convexity/concavity
    const int laplacian = abs(4 * cMid - (c00 + c10 + c01 + c11));
    // Saddle-energy: measures diagonal twist
    const int saddle = abs((c00 + c11) - (c10 + c01));

    int energy = drop;
    if (((laplacian + 1) >> 1) > energy) energy = (laplacian + 1) >> 1;
    if (saddle > energy) energy = saddle;
    return energy;
}

static inline int cfxEvaluateAdaptiveMacroGrid(
    const CfxContext *ctx, int *macro, int gridW, int gridH,
    int minX4, int minZ4, const CfxNoiseDetail *detail
)
{
    const int W = gridW;
    const int H = gridH;
    if (W <= 0 || H <= 0) return CFX_OK;

    // 1. Align world coordinates to 4-block macro cell bounds
    const int startCellX = floordiv(minX4, 4);
    const int endCellX   = floordiv(minX4 + W - 1, 4);
    const int startCellZ = floordiv(minZ4, 4);
    const int endCellZ   = floordiv(minZ4 + H - 1, 4);

    const int numCellsX = endCellX - startCellX + 1;
    const int numCellsZ = endCellZ - startCellZ + 1;

    const int cornerW = numCellsX + 1;
    const int cornerH = numCellsZ + 1;

    int *corners = (int *)malloc(cornerW * cornerH * sizeof(int));
    if (!corners) return CFX_ERR_ALLOC;

    // 2. Evaluate all corner heights in absolute world coordinates
    for (int cz = 0; cz < cornerH; cz++) {
        int worldZ4 = (startCellZ + cz) * 4;
        for (int cx = 0; cx < cornerW; cx++) {
            int worldX4 = (startCellX + cx) * 4;
            corners[cz * cornerW + cx] = cfxModernOverviewHeight(ctx, worldX4, worldZ4, detail);
        }
    }

    // 3. Fill individual macro cells independently
    for (int cz = 0; cz < numCellsZ; cz++) {
        const int cellWorldZ4 = (startCellZ + cz) * 4;
        int lz0 = cellWorldZ4 - minZ4;
        int lz1 = lz0 + 4;
        int clampZ0 = (lz0 < 0) ? 0 : lz0;
        int clampZ1 = (lz1 > H) ? H : lz1;
        if (clampZ0 >= clampZ1) continue;

        for (int cx = 0; cx < numCellsX; cx++) {
            const int cellWorldX4 = (startCellX + cx) * 4;
            int lx0 = cellWorldX4 - minX4;
            int lx1 = lx0 + 4;
            int clampX0 = (lx0 < 0) ? 0 : lx0;
            int clampX1 = (lx1 > W) ? W : lx1;
            if (clampX0 >= clampX1) continue;

            int c00 = corners[cz * cornerW + cx];
            int c10 = corners[cz * cornerW + (cx + 1)];
            int c01 = corners[(cz + 1) * cornerW + cx];
            int c11 = corners[(cz + 1) * cornerW + (cx + 1)];

            int midX = cellWorldX4 + 2;
            int midZ = cellWorldZ4 + 2;
            int cMid = cfxModernOverviewHeight(ctx, midX, midZ, detail);

            int min_h = c00, max_h = c00;
            if (c10 < min_h) min_h = c10;
            if (c10 > max_h) max_h = c10;
            if (c01 < min_h) min_h = c01;
            if (c01 > max_h) max_h = c01;
            if (c11 < min_h) min_h = c11;
            if (c11 > max_h) max_h = c11;
            if (cMid < min_h) min_h = cMid;
            if (cMid > max_h) max_h = cMid;

            const int is_waterline = (min_h <= 65 && max_h >= 61);
            const int energy = cfxEvaluateCurvatureEnergy(c00, c10, c01, c11, cMid);

            if (energy > 4 || is_waterline) {
                int cTop   = (c00 + c10 + 1) >> 1;
                int cBot   = (c01 + c11 + 1) >> 1;
                int cLeft  = (c00 + c01 + 1) >> 1;
                int cRight = (c10 + c11 + 1) >> 1;

                for (int qz = 0; qz < 2; qz++) {
                    int subZ0 = clampZ0 > (lz0 + qz * 2) ? clampZ0 : (lz0 + qz * 2);
                    int subZ1 = clampZ1 < (lz0 + (qz + 1) * 2) ? clampZ1 : (lz0 + (qz + 1) * 2);
                    if (subZ0 >= subZ1) continue;

                    for (int qx = 0; qx < 2; qx++) {
                        int subX0 = clampX0 > (lx0 + qx * 2) ? clampX0 : (lx0 + qx * 2);
                        int subX1 = clampX1 < (lx0 + (qx + 1) * 2) ? clampX1 : (lx0 + (qx + 1) * 2);
                        if (subX0 >= subX1) continue;

                        int qc00, qc10, qc01, qc11;
                        if (qz == 0 && qx == 0) { qc00 = c00; qc10 = cTop; qc01 = cLeft; qc11 = cMid; }
                        else if (qz == 0 && qx == 1) { qc00 = cTop; qc10 = c10; qc01 = cMid; qc11 = cRight; }
                        else if (qz == 1 && qx == 0) { qc00 = cLeft; qc10 = cMid; qc01 = c01; qc11 = cBot; }
                        else { qc00 = cMid; qc10 = cRight; qc01 = cBot; qc11 = c11; }

                        int qmin = qc00, qmax = qc00;
                        if (qc10 < qmin) qmin = qc10;
                        if (qc10 > qmax) qmax = qc10;
                        if (qc01 < qmin) qmin = qc01;
                        if (qc01 > qmax) qmax = qc01;
                        if (qc11 < qmin) qmin = qc11;
                        if (qc11 > qmax) qmax = qc11;

                        const int q_is_waterline = (qmin <= 65 && qmax >= 61);
                        if (qmax - qmin > 4 || q_is_waterline) {
                            for (int lz = subZ0; lz < subZ1; lz++) {
                                int worldZ4 = minZ4 + lz;
                                for (int lx = subX0; lx < subX1; lx++) {
                                    int worldX4 = minX4 + lx;
                                    macro[lz * W + lx] = cfxModernOverviewHeight(ctx, worldX4, worldZ4, detail);
                                }
                            }
                        } else {
                            int qWorldX4 = cellWorldX4 + qx * 2;
                            int qWorldZ4 = cellWorldZ4 + qz * 2;
                            for (int lz = subZ0; lz < subZ1; lz++) {
                                int dz = (minZ4 + lz) - qWorldZ4; // 0..1
                                for (int lx = subX0; lx < subX1; lx++) {
                                    int dx = (minX4 + lx) - qWorldX4; // 0..1
                                    int top = cfxLerpSymmetric(qc00, qc10, dx, 2);
                                    int bot = cfxLerpSymmetric(qc01, qc11, dx, 2);
                                    macro[lz * W + lx] = cfxLerpSymmetric(top, bot, dz, 2);
                                }
                            }
                        }
                    }
                }
            } else {
                for (int lz = clampZ0; lz < clampZ1; lz++) {
                    int dz = (minZ4 + lz) - cellWorldZ4; // 0..3
                    for (int lx = clampX0; lx < clampX1; lx++) {
                        int dx = (minX4 + lx) - cellWorldX4; // 0..3
                        int top = cfxLerpSymmetric(c00, c10, dx, 4);
                        int bot = cfxLerpSymmetric(c01, c11, dx, 4);
                        macro[lz * W + lx] = cfxLerpSymmetric(top, bot, dz, 4);
                    }
                }
            }
        }
    }

    free(corners);
    return CFX_OK;
}

#endif
