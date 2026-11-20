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

# Tooltips that are dynamic format strings, not prose.
SKIP_TOOLTIPS = {
    # "Original limit (100%%): %s\nCurrent limit: %s"
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
    if key == rest or rest.count(".") != 1:
        return False
    return key not in blocklist


def collect_entries(lang: dict) -> dict:
    entries = {}
    for key in lang:
        if not is_entry(key, BLOCKLIST):
            continue
        _prefix, group, name = key.rsplit(".", 2)
        tooltip_key = key + ".tooltip"
        tooltip = "" if tooltip_key in SKIP_TOOLTIPS else lang.get(tooltip_key, "")
        entries.setdefault(group, []).append((name, strip_label(lang[key]), tooltip))
    for group in entries:
        entries[group].sort()
    return entries


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
