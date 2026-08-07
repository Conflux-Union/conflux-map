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
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.ModelWithHead;
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
    private static final int CONTENT_PAD = 3;
    private static final int TARGET_VISUAL_SPAN = 22;

    private record RawVertex(float x, float y, float z, float u, float v) {
    }

    private record RawQuad(List<RawVertex> vertices, float depth) {
    }

    private EntityHeadGeometry() {
    }

    static float[] project(final EntityModel<?> model, final String entityType, final int cellX, final int cellY) {
        final List<ModelPart> parts = selectParts(model, entityType);
        if (parts.isEmpty()) {
            return new float[0];
        }
        final List<RawQuad> quads = new ArrayList<>();
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
                part.forEachCuboid(matrices, (entry, path, index, cuboid) -> appendCuboid(
                    quads, entry, cuboid, yawCos, yawSin
                ));
            } finally {
                // Entity models are renderer-owned singletons; do not leak the portrait pose back
                // into the next world render or another entity that shares this model.
                part.setAngles(pitch, yaw, roll);
            }
        }
        if (quads.isEmpty()) {
            return new float[0];
        }

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (final RawQuad quad : quads) {
            for (final RawVertex vertex : quad.vertices()) {
                minX = Math.min(minX, vertex.x());
                minY = Math.min(minY, vertex.y());
                maxX = Math.max(maxX, vertex.x());
                maxY = Math.max(maxY, vertex.y());
            }
        }
        final float width = maxX - minX;
        final float height = maxY - minY;
        if (!(width > 0f) || !(height > 0f)) {
            return new float[0];
        }
        final PortraitLayout.Fit fit = PortraitLayout.fit(
            width, height, CELL_PX, CONTENT_PAD, TARGET_VISUAL_SPAN
        );
        final float scale = fit.scale();
        final float offsetX = cellX + fit.left();
        final float offsetY = cellY + fit.top();

        quads.sort(Comparator.comparingDouble(RawQuad::depth).reversed());
        final float[] projected = new float[quads.size() * 20];
        int out = 0;
        for (final RawQuad quad : quads) {
            for (final RawVertex vertex : quad.vertices()) {
                projected[out++] = offsetX + (vertex.x() - minX) * scale;
                projected[out++] = offsetY + (vertex.y() - minY) * scale;
                projected[out++] = 0f;
                projected[out++] = vertex.u();
                projected[out++] = vertex.v();
            }
        }
        return projected;
    }

    private static List<ModelPart> selectParts(final EntityModel<?> model, final String entityType) {
        //#if MC>=12103
        //$$ final ModelPart root = model.getRootPart();
        //$$ final Map<String, ModelPart> byPath = new LinkedHashMap<>();
        //$$ collectPaths(root, "root", byPath, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        //$$ final Set<String> selectedPaths = HeadPartSelector.select(entityType, byPath.keySet());
        //$$ final List<ModelPart> selected = new ArrayList<>();
        //$$ for (final String path : selectedPaths) {
        //$$     boolean coveredByAncestor = false;
        //$$     for (final String other : selectedPaths) {
        //$$         if (!path.equals(other) && path.startsWith(other + "/")) {
        //$$             coveredByAncestor = true;
        //$$             break;
        //$$         }
        //$$     }
        //$$     if (!coveredByAncestor) {
        //$$         selected.add(byPath.get(path));
        //$$     }
        //$$ }
        //$$ return selected;
        //#else
        if (HeadPartSelector.usesFullModel(entityType)) {
            return topLevelParts(model);
        }
        if (model instanceof ModelWithHead) {
            return List.of(((ModelWithHead) model).getHead());
        }
        final List<ModelPart> headGroup = smallestPartGroup(model);
        return headGroup.isEmpty() ? topLevelParts(model) : headGroup;
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
    private static Map<String, ModelPart> children(final ModelPart part) {
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

    private static void appendCuboid(
        final List<RawQuad> output,
        final MatrixStack.Entry entry,
        final ModelPart.Cuboid cuboid,
        final float yawCos,
        final float yawSin
    ) {
        final Object sides = firstArrayField(cuboid);
        if (sides == null) {
            return;
        }
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
                output.add(new RawQuad(List.copyOf(converted), depth / 4f));
            }
        }
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
