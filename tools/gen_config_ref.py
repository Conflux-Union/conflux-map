"""Generate the bilingual config-reference wiki pages from the mod lang files.

Run from the repo root. Output is fully derived from the lang JSON, so
rerunning after lang changes refreshes the pages.
"""

import json
import pathlib
import sys

TOOLS = pathlib.Path(__file__).resolve().parent
REPO = TOOLS.parent
LANG_DIR = REPO / "src/main/resources/assets/confluxmap/lang"
OUT = {
    "en_us": REPO / "docs/en/config/index.md",
    "zh_cn": REPO / "docs/zh/config/index.md",
}
PREFIX = "confluxmap.config."

# Keys under confluxmap.config. that are runtime messages, not settings entries.
BLOCKLIST = {
    # Banner shown while a resource pack overrides the player marker texture.
    "confluxmap.config.player_marker.disabled_by_resource_pack",
}

# Enum-value labels (4-segment keys) and the 3-segment entry they belong to.
# Names must match ConfigValueLabels.java; "Values:" lines render from these.
ENUM_PARENTS = {
    "confluxmap.config.shape.circle": "confluxmap.config.minimap.shape",
    "confluxmap.config.shape.square": "confluxmap.config.minimap.shape",
    "confluxmap.config.layer_override.auto": "confluxmap.config.layers.override",
    "confluxmap.config.layer_override.force_surface": "confluxmap.config.layers.override",
    "confluxmap.config.layer_override.force_underground": "confluxmap.config.layers.override",
    "confluxmap.config.layer_override.force_slice": "confluxmap.config.layers.override",
    "confluxmap.config.prediction.mode.everywhere": "confluxmap.config.prediction.view_mode",
    "confluxmap.config.prediction.mode.generated_only": "confluxmap.config.prediction.view_mode",
    "confluxmap.config.prediction.mode.visited_only": "confluxmap.config.prediction.view_mode",
}

# 4-segment keys that are real per-object entries, not enum values: the parent
# prefix names a family of sibling settings sharing one tooltip.
SUB_ENTRIES = {
    "confluxmap.config.portals.icon_texture",
}

# Tooltips that are dynamic format strings, not prose. zoom_limit's tooltip
# lives only on the unmerged 26.3 branch (master-half1); kept as a guard for
# when that branch merges.
SKIP_TOOLTIPS = {
    "confluxmap.config.map.zoom_limit.tooltip",
}

INTRO = {
    "en_us": (
        "---\ntitle: Configuration\n---\n\n"
        "# Configuration\n\n"
        "Every option below is available in the in-game settings screen"
        " (`Mod Menu → Conflux Map → Settings`, or the config hotkey)."
        " This page is generated from the mod's own translations; entries"
        " without a description yet only show their on-screen label.\n"
    ),
    "zh_cn": (
        "---\ntitle: 配置参考\n---\n\n"
        "# 配置参考\n\n"
        "以下选项均可在游戏内设置界面调整(`Mod Menu → Conflux Map → 设置`,"
        "或配置快捷键)。本页由模组自身的翻译文件生成;"
        "暂无说明的条目仅显示界面标签。\n"
    ),
}


def strip_label(label: str) -> str:
    # en labels end ": %s", zh labels end "：%s" (fullwidth colon, no space).
    for suffix in (": %s", "：%s"):
        if label.endswith(suffix):
            return label[: -len(suffix)]
    return label


def is_entry(key: str, blocklist: set) -> bool:
    rest = key.removeprefix(PREFIX)
    if key == rest or key in blocklist or key in ENUM_PARENTS:
        return False
    if rest.count(".") == 1:
        return True
    if rest.count(".") == 2:
        if rest.endswith(".tooltip"):
            return False
        parent = rest.rsplit(".", 1)[0]
        return PREFIX + parent in SUB_ENTRIES
    return False


def collect_entries(lang: dict) -> dict:
    entries = {}
    for key in lang:
        if not is_entry(key, BLOCKLIST):
            continue
        rest = key.removeprefix(PREFIX)
        if rest.count(".") == 1:
            group, name = rest.split(".")
            tooltip_key = key + ".tooltip"
        else:
            # SUB_ENTRIES member: group is the first segment, tooltip lives
            # on the 3-segment family prefix.
            group, name = rest.split(".", 1)
            tooltip_key = PREFIX + rest.rsplit(".", 1)[0] + ".tooltip"
        tooltip = "" if tooltip_key in SKIP_TOOLTIPS else lang.get(tooltip_key, "")
        entries.setdefault(group, []).append(
            [name, strip_label(lang[key]), tooltip]
        )
    by_parent = {}
    for enum_key, parent in ENUM_PARENTS.items():
        if enum_key in lang and parent in lang:
            by_parent.setdefault(parent, []).append(lang[enum_key])
    for parent, values in by_parent.items():
        parent_group, parent_name = parent.removeprefix(PREFIX).split(".")
        for item in entries.get(parent_group, ()):
            if item[0] == parent_name:
                joined = "Values: " + ", ".join(values) + "."
                item[2] = (item[2] + " " if item[2] else "") + joined
    for group in entries:
        entries[group].sort()
    return {g: [tuple(i) for i in items] for g, items in entries.items()}


def _cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ").strip()


def _enum_values(lang: dict, key: str) -> list:
    prefix = key + "."
    values = []
    for other in lang:
        rest = other.removeprefix(prefix)
        if other != rest and rest != "tooltip" and "." not in rest:
            values.append((rest, lang[other]))
    values.sort()
    return [label for _suffix, label in values]


def render_page(locale: str, lang: dict) -> str:
    lines = [INTRO[locale]]
    for group, items in sorted(collect_entries(lang).items()):
        lines.append(f"\n## {group}\n")
        lines.append("| Option | Key | Description |")
        lines.append("| --- | --- | --- |")
        for name, label, tooltip in items:
            key = PREFIX + group + "." + name
            values = _enum_values(lang, key)
            desc = tooltip
            if values:
                joined = ", ".join(values)
                desc = f"{desc} Values: {joined}." if desc else f"Values: {joined}."
            lines.append(f"| {_cell(label)} | `{key}` | {_cell(desc) or '—'} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    for locale, path in OUT.items():
        lang = json.loads((LANG_DIR / f"{locale}.json").read_text(encoding="utf-8"))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(render_page(locale, lang), encoding="utf-8")
        print(f"wrote {path.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
