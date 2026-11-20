package cn.net.rms.confluxmap.core.waypoint.image;

import java.util.List;

/**
 * Built-in description templates the editor can insert at the cursor. Pure
 * data: the UI picks the label matching the client language.
 */
public final class WaypointDescriptionTemplates {
    private WaypointDescriptionTemplates() {
    }

    public record Template(String id, String zh, String en) {
    }

    public static List<Template> builtIn() {
        return List.of(
            new Template("produce", "产物:", "Produces:"),
            new Template("acquire", "获取方式:", "How to get:"),
            new Template("refresh", "刷新时间:", "Refresh time:"),
            new Template("danger", "危险提示:", "Danger:"),
            new Template("progress", "任务进度:", "Task progress:"),
            new Template("coords", "坐标说明:", "Coordinate notes:")
        );
    }
}
