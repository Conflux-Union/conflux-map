package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.core.radar.HeadPartSelector;
import cn.net.rms.confluxmap.core.radar.PortraitLayout;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.ModelPart;
//#if MC<12103
import net.minecraft.client.model.TexturedModelData;
//#endif
import net.minecraft.client.render.entity.model.EntityModel;
//#if MC<12103
import net.minecraft.client.render.entity.model.CompositeEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.EntityModels;
//#endif
import net.minecraft.client.render.entity.model.ModelWithHead;
//#if MC<12103
import net.minecraft.client.render.entity.model.SinglePartEntityModel;
//#endif
import net.minecraft.client.util.math.MatrixStack;
//#if MC>=11900
//$$ import org.joml.Vector3f;
//#else
import net.minecraft.util.math.Vec3f;
import net.minecraft.util.math.Vector4f;
//#endif

/** Extracts textured quads for only the face-like portion of a neutralized vanilla entity model. */
final class EntityHeadGeometry {
    private static final int CELL_PX = 32;
    /** Transparent margin that stops a scaled portrait from sampling its atlas neighbour. */
    private static final int CONTENT_PAD = 1;
    /** Keeps equally dominant cuboids together for multi-part subjects such as wither heads. */
    private static final float DOMINANT_SCORE_RATIO = 0.99f;
    /** Target raster area for the complete silhouette after the compact subject establishes scale. */
    private static final float TARGET_OCCUPIED_AREA_RATIO = 0.54f;
    /** Minimum share of the total-area target reserved for the compact subject itself. */
    private static final float MIN_SUBJECT_OCCUPIED_RATIO = 0.8f;
    /** Keeps a readable compact face even when its angled raster footprint looks deceptively full. */
    private static final float MIN_SUBJECT_VISUAL_AREA_RATIO = 0.81f;
    //#if MC<12103
    private static final String MAIN_LAYER = "main";
    private static final Map<String, ModelPart> DATA_ROOTS = new LinkedHashMap<>();

    private static Map<String, TexturedModelData> mainLayerData;
    //#endif

    private record RawVertex(float x, float y, float z, float u, float v) {
    }

    private record RawQuad(List<RawVertex> vertices, float depth) {
    }

    private record Bounds(
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ
    ) {
        float width() {
            return maxX - minX;
        }

        float height() {
            return maxY - minY;
        }

        float depth() {
            return maxZ - minZ;
        }

        float dominance() {
            // The two thinnest dimensions describe a cuboid's compact cross-section. A long horn
            // therefore stays subordinate, and a zero-depth tendril plane cannot tie a solid head.
            final float[] dimensions = {width(), height(), depth()};
            java.util.Arrays.sort(dimensions);
            return dimensions[0] * dimensions[1];
        }

        float projectedDominance() {
            final float thickness = Math.min(width(), height());
            return thickness * thickness;
        }
    }

    private record RawCuboid(List<RawQuad> quads, Bounds bounds) {
    }

    private EntityHeadGeometry() {
    }

    static float[] project(final EntityModel<?> model, final String entityType, final int cellX, final int cellY) {
        return project(selectParts(model, entityType), entityType, cellX, cellY);
    }

    /** Projects an already-selected part group; separated so tests can drive raw model trees. */
    static float[] project(
        final List<ModelPart> parts,
        final String entityType,
        final int cellX,
        final int cellY
    ) {
        if (parts.isEmpty()) {
            return new float[0];
        }
        final List<RawCuboid> cuboids = new ArrayList<>();
        final double yawRadians = Math.toRadians(PortraitLayout.viewYawDegrees(entityType));
        final float yawCos = (float) Math.cos(yawRadians);
        final float yawSin = (float) Math.sin(yawRadians);
        for (final ModelPart part : parts) {
            final MatrixStack matrices = new MatrixStack();
            final float pitch = part.pitch;
            final float yaw = part.yaw;
            final float roll = part.roll;
            // Keep the neutral pose chosen by the species model. In particular, horse-like
            // models pitch their long muzzle into a recognizable silhouette; flattening every
            // axis makes that muzzle project as a vertical strip. Only discard residual head yaw.
            part.setAngles(pitch, 0f, roll);
            try {
                part.forEachCuboid(matrices, (entry, path, index, cuboid) -> {
                    final RawCuboid converted = readCuboid(entry, cuboid, yawCos, yawSin);
                    if (converted != null) {
                        cuboids.add(converted);
                    }
                });
            } finally {
                // Entity models are renderer-owned singletons; do not leak the portrait pose back
                // into the next world render or another entity that shares this model.
                part.setAngles(pitch, yaw, roll);
            }
        }
        if (cuboids.isEmpty()) {
            return new float[0];
        }

        float largestDominance = 0f;
        for (final RawCuboid cuboid : cuboids) {
            largestDominance = Math.max(largestDominance, cuboid.bounds().dominance());
        }
        final boolean planarSubject = !(largestDominance > 0f);
        if (planarSubject) {
            for (final RawCuboid cuboid : cuboids) {
                largestDominance = Math.max(
                    largestDominance, cuboid.bounds().projectedDominance()
                );
            }
        }
        if (!(largestDominance > 0f)) {
            return new float[0];
        }
        float subjectMinX = Float.POSITIVE_INFINITY;
        float subjectMinY = Float.POSITIVE_INFINITY;
        float subjectMaxX = Float.NEGATIVE_INFINITY;
        float subjectMaxY = Float.NEGATIVE_INFINITY;
        final List<RawCuboid> subjectCuboids = new ArrayList<>();
        for (final RawCuboid cuboid : cuboids) {
            final float dominance = planarSubject
                ? cuboid.bounds().projectedDominance()
                : cuboid.bounds().dominance();
            if (dominance < largestDominance * DOMINANT_SCORE_RATIO) {
                continue;
            }
            subjectCuboids.add(cuboid);
            subjectMinX = Math.min(subjectMinX, cuboid.bounds().minX());
            subjectMinY = Math.min(subjectMinY, cuboid.bounds().minY());
            subjectMaxX = Math.max(subjectMaxX, cuboid.bounds().maxX());
            subjectMaxY = Math.max(subjectMaxY, cuboid.bounds().maxY());
        }
        final float width = subjectMaxX - subjectMinX;
        final float height = subjectMaxY - subjectMinY;
        if (!(width > 0f) || !(height > 0f)) {
            return new float[0];
        }
        final List<RawQuad> quads = cuboids.stream().flatMap(cuboid -> cuboid.quads().stream())
            .sorted(Comparator.comparingDouble(RawQuad::depth).reversed())
            .toList();
        final List<RawQuad> subjectQuads = subjectCuboids.stream()
            .flatMap(cuboid -> cuboid.quads().stream())
            .toList();
        final PortraitLayout.Fit fit = PortraitLayout.fit(width, height, CELL_PX, CONTENT_PAD);
        final float baselineScale = fit.scale();
        final int occupiedPixels = occupiedPixels(
            quads, subjectMinX, subjectMinY, width, height, baselineScale
        );
        final float targetOccupiedPixels = (CELL_PX - 2f * CONTENT_PAD)
            * (CELL_PX - 2f * CONTENT_PAD) * TARGET_OCCUPIED_AREA_RATIO;
        final float coverageScale = occupiedPixels > 0
            ? baselineScale * (float) Math.sqrt(targetOccupiedPixels / occupiedPixels)
            : baselineScale;
        final int subjectOccupiedPixels = occupiedPixels(
            subjectQuads, subjectMinX, subjectMinY, width, height, baselineScale
        );
        final float minimumSubjectPixels = targetOccupiedPixels * MIN_SUBJECT_OCCUPIED_RATIO;
        final float subjectVisualArea = largestCompactQuadArea(subjectQuads, baselineScale);
        final float minimumSubjectVisualArea = fit.width() * fit.height()
            * MIN_SUBJECT_VISUAL_AREA_RATIO;
        // Whole-model portraits have no smaller face to protect. For head portraits, solve a
        // second scale from the compact cuboids alone so ears, antlers and tendrils can influence
        // the total without shrinking the recognizable subject below its own pixel floor.
        final float minimumSubjectPixelScale = HeadPartSelector.usesFullModel(entityType)
            || subjectOccupiedPixels <= 0
            ? 0f
            : baselineScale * (float) Math.sqrt(minimumSubjectPixels / subjectOccupiedPixels);
        final float minimumSubjectVisualScale = HeadPartSelector.usesFullModel(entityType)
            || !(subjectVisualArea > 0f)
            ? 0f
            : baselineScale * (float) Math.sqrt(minimumSubjectVisualArea / subjectVisualArea);
        final float scale = Math.max(
            coverageScale, Math.max(minimumSubjectPixelScale, minimumSubjectVisualScale)
        );
        final float offsetX = cellX + (CELL_PX - width * scale) / 2f;
        final float offsetY = cellY + (CELL_PX - height * scale) / 2f;

        final float[] projected = new float[quads.size() * 20];
        int out = 0;
        for (final RawQuad quad : quads) {
            for (final RawVertex vertex : quad.vertices()) {
                projected[out++] = offsetX + (vertex.x() - subjectMinX) * scale;
                projected[out++] = offsetY + (vertex.y() - subjectMinY) * scale;
                projected[out++] = 0f;
                projected[out++] = vertex.u();
                projected[out++] = vertex.v();
            }
        }
        return projected;
    }

    private static float largestCompactQuadArea(final List<RawQuad> quads, final float scale) {
        float bestCompactness = 0f;
        float bestArea = 0f;
        for (final RawQuad quad : quads) {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (final RawVertex vertex : quad.vertices()) {
                minX = Math.min(minX, vertex.x());
                minY = Math.min(minY, vertex.y());
                maxX = Math.max(maxX, vertex.x());
                maxY = Math.max(maxY, vertex.y());
            }
            final float width = (maxX - minX) * scale;
            final float height = (maxY - minY) * scale;
            final float compactness = Math.min(width, height) * Math.min(width, height);
            if (compactness > bestCompactness) {
                bestCompactness = compactness;
                bestArea = width * height;
            }
        }
        return bestArea;
    }

    private static int occupiedPixels(
        final List<RawQuad> quads,
        final float subjectMinX,
        final float subjectMinY,
        final float subjectWidth,
        final float subjectHeight,
        final float scale
    ) {
        final float offsetX = (CELL_PX - subjectWidth * scale) / 2f;
        final float offsetY = (CELL_PX - subjectHeight * scale) / 2f;
        final boolean[][] occupied = new boolean[CELL_PX][CELL_PX];
        for (final RawQuad quad : quads) {
            final float[] xs = new float[4];
            final float[] ys = new float[4];
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                final RawVertex vertex = quad.vertices().get(i);
                xs[i] = offsetX + (vertex.x() - subjectMinX) * scale;
                ys[i] = offsetY + (vertex.y() - subjectMinY) * scale;
                minX = Math.min(minX, xs[i]);
                minY = Math.min(minY, ys[i]);
                maxX = Math.max(maxX, xs[i]);
                maxY = Math.max(maxY, ys[i]);
            }
            final int firstX = Math.max(CONTENT_PAD, (int) Math.floor(minX));
            final int firstY = Math.max(CONTENT_PAD, (int) Math.floor(minY));
            final int lastX = Math.min(CELL_PX - CONTENT_PAD, (int) Math.ceil(maxX));
            final int lastY = Math.min(CELL_PX - CONTENT_PAD, (int) Math.ceil(maxY));
            for (int y = firstY; y < lastY; y++) {
                for (int x = firstX; x < lastX; x++) {
                    if (occupied[y][x]) {
                        continue;
                    }
                    final float px = x + 0.5f;
                    final float py = y + 0.5f;
                    if (insideTriangle(xs, ys, 0, 1, 2, px, py)
                        || insideTriangle(xs, ys, 0, 2, 3, px, py)) {
                        occupied[y][x] = true;
                    }
                }
            }
        }
        int count = 0;
        for (final boolean[] row : occupied) {
            for (final boolean pixel : row) {
                if (pixel) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean insideTriangle(
        final float[] xs,
        final float[] ys,
        final int first,
        final int second,
        final int third,
        final float px,
        final float py
    ) {
        final float ax = xs[first];
        final float ay = ys[first];
        final float bx = xs[second];
        final float by = ys[second];
        final float cx = xs[third];
        final float cy = ys[third];
        final float triangleArea = sign(ax, ay, bx, by, cx, cy);
        if (Math.abs(triangleArea) < 0.0001f) {
            return false;
        }
        final float firstSign = sign(ax, ay, bx, by, px, py);
        final float secondSign = sign(bx, by, cx, cy, px, py);
        final float thirdSign = sign(cx, cy, ax, ay, px, py);
        final boolean hasNegative = firstSign < 0f || secondSign < 0f || thirdSign < 0f;
        final boolean hasPositive = firstSign > 0f || secondSign > 0f || thirdSign > 0f;
        return !(hasNegative && hasPositive);
    }

    private static float sign(
        final float ax,
        final float ay,
        final float bx,
        final float by,
        final float px,
        final float py
    ) {
        return (px - bx) * (ay - by) - (ax - bx) * (py - by);
    }

    /**
     * Resolves the parts a portrait draws. The named part tree is the primary source on every
     * version, because part names come from model data and survive remapping; the reflective
     * strategies below it only cover pre-1.21.3 models that never retain their root part.
     */
    static List<ModelPart> selectParts(final EntityModel<?> model, final String entityType) {
        if (HeadPartSelector.usesFullModel(entityType)) {
            return fullModelParts(model);
        }
        final ModelPart root = rootPart(model);
        if (root != null) {
            final List<ModelPart> selected = selectFromRoot(root, entityType);
            if (!selected.isEmpty()) {
                return selected;
            }
        }
        //#if MC>=12103
        //$$ return List.of();
        //#else
        if (model instanceof ModelWithHead) {
            return List.of(((ModelWithHead) model).getHead());
        }
        // AnimalModel exposes its head group only as a protected iterable, and discards the root
        // it built those parts from, so no named tree is reachable for that model family.
        // CompositeEntityModel is excluded because its only iterable is every part it owns.
        if (!(model instanceof CompositeEntityModel)) {
            final List<ModelPart> headGroup = smallestPartGroup(model);
            if (!headGroup.isEmpty()) {
                return headGroup;
            }
        }
        final ModelPart dataRoot = vanillaDataRoot(entityType);
        return dataRoot == null ? List.of() : selectFromRoot(dataRoot, entityType);
        //#endif
    }

    //#if MC<12103
    /**
     * Rebuilds a vanilla layer's named part tree from the model data the game built the live model
     * from. Models such as {@code LlamaEntityModel} keep neither their root part nor a head group,
     * so their live parts carry no name at all; the data tree is geometrically identical and the
     * portrait pose is neutral anyway. Render thread only.
     */
    private static ModelPart vanillaDataRoot(final String entityType) {
        if (entityType == null) {
            return null;
        }
        if (mainLayerData == null) {
            mainLayerData = new LinkedHashMap<>();
            for (final Map.Entry<EntityModelLayer, TexturedModelData> layer : EntityModels.getModels().entrySet()) {
                if (MAIN_LAYER.equals(layer.getKey().getName())) {
                    mainLayerData.put(layer.getKey().getId().toString(), layer.getValue());
                }
            }
        }
        final TexturedModelData data = mainLayerData.get(entityType);
        if (data == null) {
            return null;
        }
        return DATA_ROOTS.computeIfAbsent(entityType, key -> data.createModel());
    }
    //#endif

    /** Path-based selection over a named part tree, shared by every version and by the tests. */
    static List<ModelPart> selectFromRoot(final ModelPart root, final String entityType) {
        final Map<String, ModelPart> byPath = new LinkedHashMap<>();
        collectPaths(root, "root", byPath, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        final Set<String> selectedPaths = HeadPartSelector.select(entityType, byPath.keySet());
        final List<ModelPart> selected = new ArrayList<>();
        for (final String path : selectedPaths) {
            boolean coveredByAncestor = false;
            for (final String other : selectedPaths) {
                if (!path.equals(other) && path.startsWith(other + "/")) {
                    coveredByAncestor = true;
                    break;
                }
            }
            if (!coveredByAncestor) {
                selected.add(byPath.get(path));
            }
        }
        return selected;
    }

    private static ModelPart rootPart(final EntityModel<?> model) {
        //#if MC>=12103
        //$$ return model.getRootPart();
        //#else
        if (model instanceof SinglePartEntityModel) {
            return ((SinglePartEntityModel<?>) model).getPart();
        }
        final List<ModelPart> topLevel = topLevelParts(model);
        return topLevel.size() == 1 && !children(topLevel.get(0)).isEmpty() ? topLevel.get(0) : null;
        //#endif
    }

    private static List<ModelPart> fullModelParts(final EntityModel<?> model) {
        //#if MC>=12103
        //$$ return List.of(model.getRootPart());
        //#else
        final ModelPart root = rootPart(model);
        return root == null ? topLevelParts(model) : List.of(root);
        //#endif
    }

    private static void collectPaths(
        final ModelPart part,
        final String path,
        final Map<String, ModelPart> result,
        final Set<ModelPart> visited
    ) {
        if (!visited.add(part)) {
            return;
        }
        result.put(path, part);
        for (final Map.Entry<String, ModelPart> child : children(part).entrySet()) {
            collectPaths(child.getValue(), path + "/" + child.getKey(), result, visited);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, ModelPart> children(final ModelPart part) {
        for (final Field field : part.getClass().getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                final Object value = field.get(part);
                if (value instanceof Map) {
                    final Map<?, ?> map = (Map<?, ?>) value;
                    if (map.isEmpty() || map.values().iterator().next() instanceof ModelPart) {
                        return (Map<String, ModelPart>) map;
                    }
                }
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // Try the next map field; an inaccessible field is not fatal to icon fallback.
            }
        }
        return Map.of();
    }

    private static List<ModelPart> smallestPartGroup(final EntityModel<?> model) {
        List<ModelPart> smallest = List.of();
        for (Class<?> type = model.getClass(); type != null; type = type.getSuperclass()) {
            for (final Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || !Iterable.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    final List<ModelPart> parts = iterableParts(method.invoke(model));
                    if (!parts.isEmpty() && (smallest.isEmpty() || parts.size() < smallest.size())) {
                        smallest = parts;
                    }
                } catch (final ReflectiveOperationException | RuntimeException ignored) {
                    // A model without an accessible head group falls back to all top-level parts.
                }
            }
        }
        return smallest;
    }

    private static List<ModelPart> iterableParts(final Object value) {
        if (!(value instanceof Iterable<?>)) {
            return List.of();
        }
        final List<ModelPart> parts = new ArrayList<>();
        for (final Object item : (Iterable<?>) value) {
            if (item instanceof ModelPart) {
                parts.add((ModelPart) item);
            }
        }
        return parts;
    }

    private static List<ModelPart> topLevelParts(final EntityModel<?> model) {
        final LinkedHashSet<ModelPart> found = new LinkedHashSet<>();
        for (Class<?> type = model.getClass(); type != null; type = type.getSuperclass()) {
            for (final Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !ModelPart.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    final Object value = field.get(model);
                    if (value instanceof ModelPart) {
                        found.add((ModelPart) value);
                    }
                } catch (final ReflectiveOperationException | RuntimeException ignored) {
                    // Keep collecting other accessible model parts.
                }
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        final Set<ModelPart> children = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (final ModelPart part : found) {
            children.addAll(children(part).values());
        }
        found.removeAll(children);
        return List.copyOf(found);
    }

    private static RawCuboid readCuboid(
        final MatrixStack.Entry entry,
        final ModelPart.Cuboid cuboid,
        final float yawCos,
        final float yawSin
    ) {
        final Object sides = firstArrayField(cuboid);
        if (sides == null) {
            return null;
        }
        final List<RawQuad> quads = new ArrayList<>();
        for (int side = 0; side < Array.getLength(sides); side++) {
            final Object quad = Array.get(sides, side);
            final Object vertices = firstArrayField(quad);
            if (vertices == null || Array.getLength(vertices) != 4) {
                continue;
            }
            final List<RawVertex> converted = new ArrayList<>(4);
            float depth = 0f;
            for (int i = 0; i < 4; i++) {
                final Object vertex = Array.get(vertices, i);
                final RawVertex raw = readVertex(entry, vertex, yawCos, yawSin);
                if (raw == null) {
                    converted.clear();
                    break;
                }
                converted.add(raw);
                depth += raw.z();
            }
            if (converted.size() == 4) {
                quads.add(new RawQuad(List.copyOf(converted), depth / 4f));
            }
        }
        if (quads.isEmpty()) {
            return null;
        }
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (final RawQuad quad : quads) {
            for (final RawVertex vertex : quad.vertices()) {
                minX = Math.min(minX, vertex.x());
                minY = Math.min(minY, vertex.y());
                minZ = Math.min(minZ, vertex.z());
                maxX = Math.max(maxX, vertex.x());
                maxY = Math.max(maxY, vertex.y());
                maxZ = Math.max(maxZ, vertex.z());
            }
        }
        return new RawCuboid(List.copyOf(quads), new Bounds(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static RawVertex readVertex(
        final MatrixStack.Entry entry,
        final Object vertex,
        final float yawCos,
        final float yawSin
    ) {
        //#if MC>=12109
        //$$ if (!(vertex instanceof ModelPart.Vertex)) {
        //$$     return null;
        //$$ }
        //$$ final ModelPart.Vertex direct = (ModelPart.Vertex) vertex;
        //#if MC>=260100
        //$$ final Vector3f transformed = new Vector3f(
        //$$     direct.worldX(), direct.worldY(), direct.worldZ()
        //$$ ).mulPosition(entry.pose());
        //#else
        //$$ final Vector3f transformed = new Vector3f(
        //$$     direct.worldX(), direct.worldY(), direct.worldZ()
        //$$ ).mulPosition(entry.getPositionMatrix());
        //#endif
        //$$ final float x = transformed.x * yawCos + transformed.z * yawSin;
        //$$ final float z = -transformed.x * yawSin + transformed.z * yawCos;
        //$$ return new RawVertex(x, transformed.y, z, direct.u(), direct.v());
        //#else
        Object position = null;
        final List<Float> scalars = new ArrayList<>(2);
        for (final Field field : vertex.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (field.getType() == float.class) {
                    scalars.add(field.getFloat(vertex));
                } else if (position == null) {
                    position = field.get(vertex);
                }
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        if (position == null || scalars.size() < 2) {
            return null;
        }
        //#if MC>=11900
        //$$ if (!(position instanceof Vector3f)) {
        //$$     return null;
        //$$ }
        //$$ final Vector3f source = (Vector3f) position;
        //$$ final Vector3f transformed = new Vector3f(source).mul(1f / 16f)
        //$$     .mulPosition(entry.getPositionMatrix());
        //$$ final float x = transformed.x * yawCos + transformed.z * yawSin;
        //$$ final float z = -transformed.x * yawSin + transformed.z * yawCos;
        //$$ return new RawVertex(x, transformed.y, z, scalars.get(0), scalars.get(1));
        //#else
        if (!(position instanceof Vec3f)) {
            return null;
        }
        final Vec3f source = (Vec3f) position;
        final Vector4f transformed = new Vector4f(
            source.getX() / 16f, source.getY() / 16f, source.getZ() / 16f, 1f
        );
        transformed.transform(entry.getModel());
        final float x = transformed.getX() * yawCos + transformed.getZ() * yawSin;
        final float z = -transformed.getX() * yawSin + transformed.getZ() * yawCos;
        return new RawVertex(x, transformed.getY(), z, scalars.get(0), scalars.get(1));
        //#endif
        //#endif
    }

    private static Object firstArrayField(final Object owner) {
        if (owner == null) {
            return null;
        }
        for (final Field field : owner.getClass().getDeclaredFields()) {
            if (!field.getType().isArray()) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(owner);
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // Try another array field.
            }
        }
        return null;
    }
}
