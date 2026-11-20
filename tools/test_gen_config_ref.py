import unittest

from tools.gen_config_ref import (
    BLOCKLIST,
    SKIP_TOOLTIPS,
    collect_entries,
    is_entry,
    strip_label,
)


class StripLabelTest(unittest.TestCase):
    def test_strips_trailing_percent_s_suffix(self):
        self.assertEqual(strip_label("Zoom: %s"), "Zoom")

    def test_strips_fullwidth_colon_suffix(self):
        self.assertEqual(strip_label("区块网格：%s"), "区块网格")

    def test_keeps_label_without_suffix(self):
        self.assertEqual(
            strip_label("Photo pan and rotation"), "Photo pan and rotation"
        )


class IsEntryTest(unittest.TestCase):
    def test_plain_entry_is_entry(self):
        self.assertTrue(is_entry("confluxmap.config.map.quality", BLOCKLIST))

    def test_key_outside_config_namespace_is_not_entry(self):
        self.assertFalse(is_entry("confluxmap.prediction.backend.cpu", BLOCKLIST))

    def test_blocklisted_key_is_not_entry(self):
        self.assertFalse(
            is_entry(
                "confluxmap.config.player_marker.disabled_by_resource_pack",
                BLOCKLIST,
            )
        )

    def test_tooltip_key_is_not_entry(self):
        self.assertFalse(
            is_entry("confluxmap.config.map.zoom_limit.tooltip", BLOCKLIST)
        )


class CollectEntriesTest(unittest.TestCase):
    def test_groups_and_sorts_entries_with_tooltips(self):
        lang = {
            "confluxmap.config.map.zebra": "Zebra: %s",
            "confluxmap.config.map.alpha": "Alpha: %s",
            "confluxmap.config.map.alpha.tooltip": "Controls alpha.",
            "confluxmap.config.radar.range": "Radar Range: %s",
            "confluxmap.prediction.backend.cpu": "CPU",
        }
        entries = collect_entries(lang)
        self.assertEqual(sorted(entries), ["map", "radar"])
        self.assertEqual(
            entries["map"],
            [("alpha", "Alpha", "Controls alpha."), ("zebra", "Zebra", "")],
        )
        self.assertEqual(entries["radar"], [("range", "Radar Range", "")])

    def test_skips_blocklisted_and_dynamic_tooltip(self):
        lang = {
            "confluxmap.config.player_marker.disabled_by_resource_pack": "msg",
            "confluxmap.config.map.zoom_limit": "Zoom Limit: %s",
            "confluxmap.config.map.zoom_limit.tooltip": "Original limit: %s",
        }
        entries = collect_entries(lang)
        self.assertEqual(
            entries["map"], [("zoom_limit", "Zoom Limit", "")]
        )


if __name__ == "__main__":
    unittest.main()
