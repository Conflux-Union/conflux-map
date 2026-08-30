package cn.net.rms.confluxmap.core.terrain;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class CaveFloorEngine {
    private static final int UPWARD_SCAN_CAP = 10;

    public CaveChunkResult select(
        final ChunkVolume volume,
        final int requestedPivotY,
        final Map<Integer, MaterialDescriptor> materials
    ) throws MissingMaterialsException {
        final Set<Integer> missing = new HashSet<>();
        for (final int stateId : volume.stateIds()) {
            if (!materials.containsKey(stateId)) {
                missing.add(stateId);
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingMaterialsException(missing);
        }

        final short[] surfaceY = new short[256];
        final int[] floor = new int[256];
        final int[] overlay = new int[256];
        final boolean[] crossSection = new boolean[256];
        final int minY = volume.minY();
        final int maxY = minY + volume.height() - 1;
        final int pivot = Math.max(minY, Math.min(maxY, requestedPivotY));
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int index = z * 16 + x;
                final int pivotState = volume.state(x, pivot, z);
                final boolean pivotOpen = materials.get(pivotState).openForFloorScan();
                int solidY;
                if (pivotOpen) {
                    int y = pivot;
                    while (y > minY && materials.get(volume.state(x, y - 1, z)).openForFloorScan()) {
                        y--;
                    }
                    solidY = y - 1;
                    if (solidY < minY) {
                        surfaceY[index] = (short) Math.max(Short.MIN_VALUE + 1, pivot + 1);
                        floor[index] = -1;
                        overlay[index] = -1;
                        continue;
                    }
                } else {
                    final int upper = Math.min(maxY, pivot + UPWARD_SCAN_CAP);
                    int y = pivot + 1;
                    while (y <= upper && !materials.get(volume.state(x, y, z)).openForFloorScan()) {
                        y++;
                    }
                    if (y > upper) {
                        solidY = pivot;
                        crossSection[index] = true;
                    } else {
                        solidY = y - 1;
                    }
                }

                final int floorState = volume.state(x, solidY, z);
                surfaceY[index] = clampY(solidY);
                floor[index] = floorState;
                if (!crossSection[index] && solidY < maxY) {
                    final int candidate = volume.state(x, solidY + 1, z);
                    overlay[index] = materials.get(candidate).overlayCandidate() ? candidate : -1;
                } else {
                    overlay[index] = -1;
                }
            }
        }
        return new CaveChunkResult(
            volume.chunkX(), volume.chunkZ(), volume.revision(), requestedPivotY,
            surfaceY, floor, overlay, crossSection
        );
    }

    private static short clampY(final int y) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, y));
    }
}
