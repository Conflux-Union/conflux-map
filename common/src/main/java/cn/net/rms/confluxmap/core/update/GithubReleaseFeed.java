package cn.net.rms.confluxmap.core.update;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tolerant reader for the GitHub {@code /releases} list-endpoint payload. Only the
 * fields the update check needs are extracted; entries without a tag are dropped
 * instead of failing the whole feed, since the check must survive whatever the
 * API happens to return.
 */
public final class GithubReleaseFeed {
    private static final Gson GSON = new Gson();

    public record Release(String tagName, String htmlUrl, boolean draft, boolean prerelease) {
    }

    private GithubReleaseFeed() {
    }

    public static List<Release> parse(final String json) {
        final JsonElement root = GSON.fromJson(json, JsonElement.class);
        if (root == null || !root.isJsonArray()) {
            throw new JsonParseException("expected a JSON array of releases");
        }
        final List<Release> releases = new ArrayList<>();
        for (final JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject release = element.getAsJsonObject();
            final String tag = stringOrNull(release, "tag_name");
            if (tag == null || tag.isBlank()) {
                continue;
            }
            releases.add(new Release(
                tag,
                stringOrNull(release, "html_url"),
                boolOrFalse(release, "draft"),
                boolOrFalse(release, "prerelease")
            ));
        }
        return releases;
    }

    private static String stringOrNull(final JsonObject obj, final String key) {
        final JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString()
            : null;
    }

    private static boolean boolOrFalse(final JsonObject obj, final String key) {
        final JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
            && value.getAsBoolean();
    }
}
