"""Generate the three waypoint control icons at 32x32.

One shared base motif: an amber map pin (LOCAL_CONTROL_ACCENT #FFD83D),
supersampled for smooth curves. Badges bottom-right: hand-placed cyan person
grid (shared, SHARED_CONTROL_ACCENT #55DDE0) and a polar-math gear (manage).
The GUI quad stays 16 logical px; a 32px texture just doubles detail at
GUI scale >= 2.
"""
import math
import os
import sys
from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else "preview"
os.makedirs(OUT, exist_ok=True)

SIZE = 32
SS = 4  # supersample factor per axis

OUTLINE = (16, 16, 16, 255)
AMBER = (255, 216, 61, 255)
AMBER_SHADE = (199, 154, 30, 255)
AMBER_HI = (255, 240, 160, 255)
CYAN = (85, 221, 224, 255)
CYAN_DIM = (58, 165, 172, 255)
GEAR_GRAY = (228, 228, 228, 255)

PIN_CX, PIN_CY, PIN_R, PIN_TIP = 16.0, 11.0, 9.3, 28.0


def super_mask(inside):
    m = [[False] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            hits = 0
            for sy in range(SS):
                for sx in range(SS):
                    if inside(x + (sx + 0.5) / SS, y + (sy + 0.5) / SS):
                        hits += 1
            m[y][x] = hits >= SS * SS // 2
    return m


def edge_render(img, m, fill):
    """1px inner outline via edge pass, flat fill inside; overwrites the mask footprint."""
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not m[y][x]:
                continue
            edge = False
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if nx < 0 or nx >= SIZE or ny < 0 or ny >= SIZE or not m[ny][nx]:
                    edge = True
                    break
            px[x, y] = OUTLINE if edge else fill


def pin_inside(x, y):
    dx, dy = x - PIN_CX, y - PIN_CY
    if dx * dx + dy * dy <= PIN_R * PIN_R:
        return True
    if PIN_CY <= y <= PIN_TIP:
        return abs(dx) <= PIN_R * (1.0 - (y - PIN_CY) / (PIN_TIP - PIN_CY))
    return False


def draw_pin(img):
    m = super_mask(pin_inside)
    edge_render(img, m, AMBER)
    px = img.load()
    # Bottom-right rim shade, 2px deep.
    for y in range(SIZE):
        for x in range(SIZE):
            if px[x, y] != AMBER:
                continue
            for nx, ny in ((x + 1, y), (x + 2, y), (x, y + 1), (x, y + 2)):
                if 0 <= nx < SIZE and 0 <= ny < SIZE and px[nx, ny] == OUTLINE:
                    if (x - PIN_CX) + (y - PIN_CY) > 0:
                        px[x, y] = AMBER_SHADE
                    break
    # Punched hole.
    for y in range(SIZE):
        for x in range(SIZE):
            dx, dy = x + 0.5 - PIN_CX, y + 0.5 - PIN_CY
            if dx * dx + dy * dy <= 2.9 * 2.9:
                px[x, y] = OUTLINE
    # Top-left highlight arc just inside the outline.
    for y in range(SIZE):
        for x in range(SIZE):
            if px[x, y] != AMBER:
                continue
            dx, dy = x + 0.5 - PIN_CX, y + 0.5 - PIN_CY
            d = math.hypot(dx, dy)
            if PIN_R - 3.4 <= d <= PIN_R - 1.4 and dx < -1.5 and dy < -1.5:
                px[x, y] = AMBER_HI


# Hand-placed 14x16 person bust: O outline, C cyan fill, . transparent.
# Round 8x8 head over a rounded-shoulder dome, taller than wide.
PERSON = (
    ".....OOOO.....",
    "....OCCCCO....",
    "...OCCCCCCO...",
    "...OCCCCCCO...",
    "...OCCCCCCO...",
    "...OCCCCCCO...",
    "....OCCCCO....",
    ".....OOOO.....",
    "..OOCCCCCCOO..",
    ".OOCCCCCCCCOO.",
    ".OCCCCCCCCCCO.",
    "OCCCCCCCCCCCCO",
    "OCCCCCCCCCCCCO",
    "OCCCCCCCCCCCCO",
    "OCCCCCCCCCCCCO",
    "OOOOOOOOOOOOOO",
)


# Smaller, dimmer person peeking out up-right behind the front one.
PERSON_BACK = (
    "...OOO...",
    "..OCCCO..",
    ".OCCCCCO.",
    ".OCCCCCO.",
    "..OCCCO..",
    "...OOO...",
    ".OOCCCOO.",
    "OCCCCCCCO",
    "OCCCCCCCO",
    "OCCCCCCCO",
    "OCCCCCCCO",
    "OCCCCCCCO",
    "OCCCCCCCO",
    "OOOOOOOOO",
)


def draw_grid(img, grid, ox, oy, fill):
    px = img.load()
    for row, line in enumerate(grid):
        for col, ch in enumerate(line):
            x, y = ox + col, oy + row
            if not (0 <= x < SIZE and 0 <= y < SIZE):
                continue
            if ch == "O":
                px[x, y] = OUTLINE
            elif ch == "C":
                px[x, y] = fill


def draw_people(img):
    draw_grid(img, PERSON_BACK, 23, 11, CYAN_DIM)
    draw_grid(img, PERSON, SIZE - 14, SIZE - 16, CYAN)


# Wrench at 45 degrees like the classic icon: open-end head with the jaw
# pointing up-left, rounded handle anchoring into the bottom-right corner.
WRENCH_HEAD_X, WRENCH_HEAD_Y = 23.0, 23.0
WRENCH_HEAD_R = 4.5
WRENCH_FACE_U = 3.2  # flat truncation of the head front, gives blunt jaw tips
WRENCH_JAW_HALF = 1.5
WRENCH_HANDLE_LEN = 9.0
WRENCH_HANDLE_HALF = 1.7


def wrench_inside(x, y):
    dx, dy = x - WRENCH_HEAD_X, y - WRENCH_HEAD_Y
    u = (dx + dy) / math.sqrt(2.0)
    v = (dy - dx) / math.sqrt(2.0)
    if 0.0 <= u <= WRENCH_HANDLE_LEN and abs(v) <= WRENCH_HANDLE_HALF:
        return True
    if (u - WRENCH_HANDLE_LEN) ** 2 + v * v <= WRENCH_HANDLE_HALF ** 2:
        return True
    if u * u + v * v <= WRENCH_HEAD_R ** 2 and u >= -WRENCH_FACE_U:
        return not (abs(v) <= WRENCH_JAW_HALF and u <= -0.4)
    return False


def draw_wrench(img):
    m = super_mask(wrench_inside)
    edge_render(img, m, GEAR_GRAY)


def make(name, badge=None):
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw_pin(img)
    if badge:
        badge(img)
    img.save(os.path.join(OUT, name))


make("waypoint_local.png")
make("waypoint_shared.png", draw_people)
make("waypoint_manage.png", draw_wrench)

# Contact sheet: current 16px (top) vs new 32px (bottom), equal display size.
base = "src/main/resources/assets/confluxmap/textures/gui"
names = ["waypoint_local.png", "waypoint_shared.png", "waypoint_manage.png"]
PAD, TILE = 10, 256
sheet = Image.new("RGBA", (3 * TILE + 4 * PAD, 2 * TILE + 3 * PAD), (16, 16, 24, 255))
for row, folder in enumerate([base, OUT]):
    for col, n in enumerate(names):
        im = Image.open(os.path.join(folder, n)).convert("RGBA")
        up = im.resize((TILE, TILE), Image.NEAREST)
        bg = Image.new("RGBA", up.size, (108, 108, 108, 255))
        bg.paste(up, (0, 0), up)
        sheet.paste(bg, (PAD + col * (TILE + PAD), PAD + row * (TILE + PAD)))
sheet.save(os.path.join(OUT, "sheet.png"))
print("done")
