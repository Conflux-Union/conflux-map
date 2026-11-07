#ifndef ADAPTIVE_MACRO_H
#define ADAPTIVE_MACRO_H

static inline void cfxEvaluateAdaptiveMacroGrid(
    const CfxContext *ctx, int *macro, int gridW, int gridH,
    int minX4, int minZ4, const CfxNoiseDetail *detail
)
{
    const int W = gridW;
    const int H = gridH;
    for (int k = 0; k < W * H; k++) macro[k] = -9999;

    // 1. 步长为 4 的网格 (对应 16x16 方块大格子, 33x33 = 1089 点)
    for (int j = 0; j < H; j += 4) {
        for (int i = 0; i < W; i += 4) {
            macro[j * W + i] = cfxModernOverviewHeight(ctx, minX4 + i, minZ4 + j, detail);
        }
    }

    // 2. 检查 16x16 大格子的落差
    for (int j = 0; j < H - 1; j += 4) {
        for (int i = 0; i < W - 1; i += 4) {
            int c00 = macro[j * W + i];
            int c10 = macro[j * W + (i + 4)];
            int c01 = macro[(j + 4) * W + i];
            int c11 = macro[(j + 4) * W + (i + 4)];

            int min_h = c00, max_h = c00;
            if (c10 < min_h) min_h = c10;
            if (c10 > max_h) max_h = c10;
            if (c01 < min_h) min_h = c01;
            if (c01 > max_h) max_h = c01;
            if (c11 < min_h) min_h = c11;
            if (c11 > max_h) max_h = c11;

            if (max_h - min_h > 6) {
                for (int dy = 0; dy <= 4; dy += 2) {
                    for (int dx = 0; dx <= 4; dx += 2) {
                        int pos = (j + dy) * W + (i + dx);
                        if (macro[pos] == -9999) {
                            macro[pos] = cfxModernOverviewHeight(ctx, minX4 + i + dx, minZ4 + j + dy, detail);
                        }
                    }
                }
            } else {
                for (int dy = 0; dy <= 4; dy += 2) {
                    for (int dx = 0; dx <= 4; dx += 2) {
                        int pos = (j + dy) * W + (i + dx);
                        if (macro[pos] == -9999) {
                            int top = c00 + ((c10 - c00) * dx) / 4;
                            int bot = c01 + ((c11 - c01) * dx) / 4;
                            macro[pos] = top + ((bot - top) * dy) / 4;
                        }
                    }
                }
            }
        }
    }

    // 3. 对 8x8 格子再做第二级自适应填充到 4x4 (129x129)
    for (int j = 0; j < H - 1; j += 2) {
        for (int i = 0; i < W - 1; i += 2) {
            int c00 = macro[j * W + i];
            int c10 = macro[j * W + (i + 2)];
            int c01 = macro[(j + 2) * W + i];
            int c11 = macro[(j + 2) * W + (i + 2)];

            int min_h = c00, max_h = c00;
            if (c10 < min_h) min_h = c10;
            if (c10 > max_h) max_h = c10;
            if (c01 < min_h) min_h = c01;
            if (c01 > max_h) max_h = c01;
            if (c11 < min_h) min_h = c11;
            if (c11 > max_h) max_h = c11;

            if (max_h - min_h > 5) {
                if (macro[j * W + (i + 1)] == -9999) macro[j * W + (i + 1)] = cfxModernOverviewHeight(ctx, minX4 + i + 1, minZ4 + j, detail);
                if (macro[(j + 1) * W + i] == -9999) macro[(j + 1) * W + i] = cfxModernOverviewHeight(ctx, minX4 + i, minZ4 + j + 1, detail);
                if (macro[(j + 1) * W + (i + 1)] == -9999) macro[(j + 1) * W + (i + 1)] = cfxModernOverviewHeight(ctx, minX4 + i + 1, minZ4 + j + 1, detail);
                if (macro[(j + 1) * W + (i + 2)] == -9999) macro[(j + 1) * W + (i + 2)] = cfxModernOverviewHeight(ctx, minX4 + i + 2, minZ4 + j + 1, detail);
                if (macro[(j + 2) * W + (i + 1)] == -9999) macro[(j + 2) * W + (i + 1)] = cfxModernOverviewHeight(ctx, minX4 + i + 1, minZ4 + j + 2, detail);
            } else {
                if (macro[j * W + (i + 1)] == -9999) macro[j * W + (i + 1)] = (c00 + c10 + 1) / 2;
                if (macro[(j + 1) * W + i] == -9999) macro[(j + 1) * W + i] = (c00 + c01 + 1) / 2;
                if (macro[(j + 1) * W + (i + 2)] == -9999) macro[(j + 1) * W + (i + 2)] = (c10 + c11 + 1) / 2;
                if (macro[(j + 2) * W + (i + 1)] == -9999) macro[(j + 2) * W + (i + 1)] = (c01 + c11 + 1) / 2;
                if (macro[(j + 1) * W + (i + 1)] == -9999) macro[(j + 1) * W + (i + 1)] = (c00 + c10 + c01 + c11 + 2) / 4;
            }
        }
    }
}

#endif
