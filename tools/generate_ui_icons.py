#!/usr/bin/env python
"""Generate Conflux's deterministic 16px monochrome pixel UI icons."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/confluxmap/textures/gui"
WHITE = (255, 255, 255, 255)


def icon(name, draw_icon):
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw_icon(draw)
    image.resize((32, 32), Image.Resampling.NEAREST).save(OUTPUT / name)


def line(draw, points, width=1):
    draw.line(points, fill=WHITE, width=width)


def eye(draw):
    line(draw, [(1, 8), (4, 5), (8, 3), (12, 5), (15, 8), (12, 11), (8, 13), (4, 11), (1, 8)])
    draw.rectangle((7, 7, 9, 9), outline=WHITE)


def pin(draw, offset=0):
    draw.ellipse((4 + offset, 1, 11 + offset, 8), outline=WHITE)
    draw.rectangle((7 + offset, 4, 8 + offset, 5), fill=WHITE)
    line(draw, [(5 + offset, 7), (8 + offset, 14), (10 + offset, 7)])


def actions(draw):
    for x in (3, 8, 13):
        draw.rectangle((x - 1, 7, x, 8), fill=WHITE)


def world(draw):
    draw.ellipse((1, 1, 14, 14), outline=WHITE)
    line(draw, [(1, 7), (14, 7)])
    line(draw, [(8, 1), (8, 14)])
    draw.arc((4, 1, 11, 14), 90, 270, fill=WHITE)
    draw.arc((4, 1, 11, 14), 270, 90, fill=WHITE)


def terrain(draw):
    line(draw, [(1, 13), (5, 6), (8, 10), (11, 3), (15, 13)])
    line(draw, [(3, 13), (13, 13)])
    line(draw, [(4, 8), (5, 6), (7, 9)])
    line(draw, [(9, 7), (11, 3), (13, 7)])


def grid(draw):
    draw.rectangle((2, 2, 13, 13), outline=WHITE)
    for pos in (6, 10):
        line(draw, [(pos, 2), (pos, 13)])
        line(draw, [(2, pos), (13, pos)])


def leaf(draw):
    line(draw, [(2, 13), (12, 3)])
    line(draw, [(4, 11), (3, 7), (5, 4), (9, 2), (13, 3), (14, 7), (12, 11), (8, 13), (4, 11)])
    line(draw, [(6, 8), (10, 8), (10, 4)])


def export(draw):
    line(draw, [(2, 10), (2, 14), (13, 14), (13, 10)])
    line(draw, [(8, 1), (8, 10)])
    line(draw, [(4, 6), (8, 10), (12, 6)])


def search(draw):
    draw.ellipse((2, 2, 10, 10), outline=WHITE)
    line(draw, [(9, 9), (14, 14)], width=2)


def shared_pins(draw):
    draw.ellipse((1, 2, 7, 8), outline=WHITE)
    line(draw, [(2, 7), (4, 13), (6, 7)])
    draw.ellipse((8, 1, 14, 7), outline=WHITE)
    line(draw, [(9, 6), (11, 12), (13, 6)])


def list_pin(draw):
    pin(draw, -2)
    for y in (4, 8, 12):
        line(draw, [(10, y), (15, y)])


def pencil(draw):
    line(draw, [(2, 13), (4, 9), (11, 2), (14, 5), (7, 12), (2, 13)])
    line(draw, [(4, 9), (7, 12)])


def collapse(draw):
    line(draw, [(3, 6), (8, 11), (13, 6)], width=2)


def eraser(draw):
    line(draw, [(2, 10), (9, 3), (14, 8), (8, 14), (4, 14), (2, 12), (2, 10)])
    line(draw, [(6, 7), (11, 11)])


def persistence(draw):
    draw.rectangle((2, 1, 13, 14), outline=WHITE)
    draw.rectangle((5, 2, 10, 6), outline=WHITE)
    draw.rectangle((5, 9, 10, 13), outline=WHITE)


def pointer(draw):
    line(draw, [(3, 1), (3, 13), (6, 10), (8, 15), (10, 14), (8, 9), (13, 9), (3, 1)])


def diagonal(draw):
    line(draw, [(2, 13), (13, 2)], width=2)


def circle(draw):
    draw.ellipse((2, 2, 13, 13), outline=WHITE)


def rectangle(draw):
    draw.rectangle((2, 3, 13, 12), outline=WHITE)


def freehand(draw):
    line(draw, [(1, 11), (3, 8), (5, 10), (7, 5), (9, 8), (11, 4), (14, 6)], width=2)


def label(draw):
    line(draw, [(2, 3), (13, 3)])
    line(draw, [(8, 3), (8, 13)], width=2)
    line(draw, [(5, 13), (11, 13)])


def undo(draw):
    line(draw, [(6, 3), (2, 7), (6, 11)])
    draw.arc((3, 3, 14, 13), 205, 505, fill=WHITE, width=2)


def redo(draw):
    line(draw, [(10, 3), (14, 7), (10, 11)])
    draw.arc((1, 3, 12, 13), 35, 335, fill=WHITE, width=2)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    icons = {
        "group_view.png": eye,
        "group_waypoints.png": pin,
        "group_actions.png": actions,
        "world_profile.png": world,
        "map_terrain.png": terrain,
        "chunk_load_state.png": grid,
        "map_biome.png": leaf,
        "map_export.png": export,
        "structure_search.png": search,
        "waypoint_local.png": pin,
        "waypoint_shared.png": shared_pins,
        "waypoint_manage.png": list_pin,
        "annotation_drawing.png": pencil,
        "annotation_collapse.png": collapse,
        "annotation_eraser.png": eraser,
        "annotation_persistence.png": persistence,
        "annotation_select.png": pointer,
        "annotation_line.png": diagonal,
        "annotation_circle.png": circle,
        "annotation_rectangle.png": rectangle,
        "annotation_freehand.png": freehand,
        "annotation_label.png": label,
        "annotation_undo.png": undo,
        "annotation_redo.png": redo,
    }
    for name, draw_icon in icons.items():
        icon(name, draw_icon)


if __name__ == "__main__":
    main()
