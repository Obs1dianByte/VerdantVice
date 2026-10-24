"""Editable 32px art source. Integer geometry only: no noise, blur or background removal."""
from PIL import Image, ImageDraw

P = {
    "ink": "#26352e", "shadow": "#394b37", "green": "#527e43", "leaf": "#81a957",
    "light": "#b9cb7a", "cream": "#f0dfb1", "paper": "#d4c394", "sand": "#bca16b",
    "soil": "#493b31", "earth": "#6b4b35", "clay": "#a76545", "terra": "#cf8960",
    "wood": "#8b613f", "woodlight": "#bc8b55", "metal": "#6b8a8b", "silver": "#b0c5bd",
    "white": "#e3e9d5", "blue": "#679aaf", "water": "#3e6e83", "gold": "#d3af61",
    "amber": "#b57e40", "purple": "#9c7aa5", "red": "#b96d5e",
}

# UI identifiers only, not claims of photographed or genetically authentic phenotypes.
STRAINS = [
    ("berry_fog", "Blueberry", "#7c8db1", "#b9c6d4"),
    ("concrete_kush", "Hindu Kush", "#8ba272", "#c1cd99"),
    ("lemon_static", "Super Lemon Haze", "#c1b459", "#e3d58c"),
    ("green_alley", "Skunk #1", "#81ad68", "#b7ce8f"),
    ("neon_haze", "Super Silver Haze", "#88b3a3", "#c5ddd0"),
    ("night_widow", "White Widow", "#b9c8b4", "#e3e9d5"),
    ("purple_crash", "Purple #1", "#a581aa", "#cfb5cb"),
    ("velvet_smoke", "Northern Lights", "#6c9b99", "#b1cebd"),
    ("ghost_garden", "AK-47", "#b7a777", "#d6d4ae"),
    ("royal_static", "Jack Herer", "#bd9958", "#e2c881"),
    ("black_orchard", "Black Domina", "#8b859c", "#bab4c4"),
    ("verdant_vice", "OG Kush", "#75995a", "#b6c77b"),
]
STAGES = ["seed", "sprout", "seedling", "vegetative", "maturing", "ready", "overripe", "spoiled"]
ITEMS = [
    ("seed", "wheat_seeds", 12001), ("planting_pot", "decorated_pot", 12002),
    ("watering_can", "glass_bottle", 12003), ("plant_scanner", "clock", 12004),
    ("cleaning_kit", "brush", 12005), ("treatment_spray", "honey_bottle", 12006),
    ("nutrient_pack", "bone_meal", 12007), ("repair_kit", "iron_nugget", 12008),
    ("drying_rack", "barrel", 12009), ("curing_container", "chiseled_bookshelf", 12010),
    ("grinder", "stonecutter", 12011), ("farm_manager_table", "lectern", 12012),
    ("selection_wand", "blaze_rod", 12013), ("rolling_paper", "paper", 12014),
    ("filter", "stick", 12015), ("package", "bundle", 12016), ("joint", "stick", 12017),
    ("fresh_batch", "kelp", 12018), ("dry_batch", "dried_kelp", 12019),
    ("cured_batch", "dried_kelp", 12020), ("ground_product", "dried_kelp", 12021),
    ("mixture", "dried_kelp", 12022), ("plantation_contract", "written_book", 12023),
    ("guide_book", "written_book", 12024), ("advanced_book", "written_book", 12025),
]

class Canvas:
    def __init__(self):
        self.image = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.image)
    def color(self, value): return P.get(value, value)
    def box(self, bounds, color): self.d.rectangle(bounds, fill=self.color(color))
    def poly(self, points, color, outline=None):
        self.d.polygon(points, fill=self.color(color), outline=self.color(outline) if outline else None)
    def line(self, points, color, width=1): self.d.line(points, fill=self.color(color), width=width)

def fan(c, x, y, scale=1.0, base="green", highlight="leaf"):
    paths = [[(0, 2), (-2, -6), (0, -12), (2, -6)],
             [(0, 2), (-7, -1), (-10, -8), (-4, -5)],
             [(0, 2), (7, -1), (10, -8), (4, -5)],
             [(0, 2), (-9, 2), (-13, -2), (-6, -2)],
             [(0, 2), (9, 2), (13, -2), (6, -2)]]
    for points in paths:
        c.poly([(round(x + px * scale), round(y + py * scale)) for px, py in points], base, "ink")
    c.line([(x, round(y + 3 * scale)), (x, round(y - 10 * scale))], highlight)
    c.line([(x - round(8 * scale), y - round(6 * scale)), (x, y + 1),
            (x + round(8 * scale), y - round(6 * scale))], highlight)

def soil(c):
    c.poly([(6, 28), (9, 26), (12, 26), (14, 25), (20, 25), (25, 27), (27, 29), (6, 29)], "soil")
    c.box((9, 27, 13, 28), "earth"); c.box((18, 27, 22, 28), "wood")

def bud(c, x, y, accent, light, size=1):
    c.poly([(x, y - 3 * size), (x + 2 * size, y - size), (x + 2 * size, y + 2 * size),
            (x, y + 3 * size), (x - 2 * size, y + size), (x - 2 * size, y - size)], "green", "ink")
    c.box((x - 1, y - 2 * size, x, y + size), light)
    c.box((x + 1, y, x + size, y + 2 * size), accent)
    c.box((x - 2 * size, y, x - 2 * size, y + 1), "amber")

def plant(stage, accent="#81a957", light="#b9cb7a", variant=0):
    c = Canvas(); soil(c)
    if stage == "seed":
        c.poly([(12, 26), (13, 23), (16, 22), (19, 24), (18, 27), (15, 28)], "sand", "ink")
        c.line([(14, 24), (16, 23), (17, 24)], "cream")
    elif stage == "sprout":
        c.line([(16, 27), (16, 20)], "green", 2)
        c.poly([(16, 21), (11, 21), (8, 17), (12, 17), (16, 19)], "leaf", "ink")
        c.poly([(16, 20), (20, 16), (24, 16), (23, 20), (18, 22)], "green", "ink")
        c.line([(11, 18), (15, 20)], "light")
    elif stage == "seedling":
        c.line([(16, 27), (16, 15)], "green", 2); fan(c, 16, 18, .68)
        c.poly([(15, 25), (10, 25), (8, 22), (13, 22)], "leaf", "ink")
    elif stage in ("vegetative", "maturing", "ready"):
        tall = variant % 3 == 1
        c.line([(16, 27), (16, 9 if tall else 11)], "green", 2)
        fan(c, 15, 22, .86); fan(c, 17, 15, .7 if tall else .8)
        c.line([(16, 26), (16, 9)], "leaf")
        if stage in ("maturing", "ready"):
            bud(c, 16, 7 if tall else 9, accent, light); bud(c, 10, 17, accent, light)
        if stage == "ready":
            bud(c, 22, 19, accent, light)
            c.box((16, 4 if tall else 6, 16, 4 if tall else 6), "cream")
    elif stage == "overripe":
        c.line([(16, 27), (16, 12), (19, 9), (22, 11), (22, 14)], "wood", 2)
        c.poly([(16, 18), (6, 16), (5, 20), (10, 24), (9, 20)], "amber", "ink")
        c.poly([(17, 21), (26, 18), (27, 23), (23, 26), (24, 22)], "sand", "ink")
        bud(c, 21, 15, "clay", "gold"); c.box((9, 28, 11, 28), "amber")
    else:
        c.line([(16, 28), (15, 14), (12, 10)], "soil", 2)
        c.line([(16, 24), (22, 19), (24, 15)], "earth", 2)
        c.line([(15, 21), (10, 20), (8, 16)], "earth", 2)
        c.poly([(12, 12), (8, 11), (8, 14), (11, 16)], "shadow")
        c.poly([(22, 20), (26, 19), (26, 23), (23, 24)], "shadow")
        c.box((11, 27, 13, 28), "metal"); c.box((21, 27, 23, 28), "metal")
    return c.image

def packet(accent="green", light="leaf", index=None, nutrient=False):
    c = Canvas(); c.poly([(8, 5), (24, 5), (25, 27), (7, 27)], "ink")
    c.poly([(9, 7), (22, 7), (23, 25), (9, 25)], "paper")
    c.box((9, 5, 22, 8), accent); c.box((9, 23, 23, 25), accent)
    c.line([(11, 10), (20, 10)], "cream")
    if nutrient:
        c.box((13, 12, 18, 20), "white"); c.box((11, 14, 20, 17), "white")
    else:
        fan(c, 16, 19, .47, accent, light); c.line([(16, 19), (16, 22)], accent)
    if index is not None:
        for bit in range(4):
            c.box((11 + bit * 3, 28, 12 + bit * 3, 28), accent if (index + 1) & (1 << bit) else "sand")
    return c.image

def icon(name):
    c = Canvas()
    if name == "seed": return packet()
    if name == "nutrient_pack": return packet("blue", "white", nutrient=True)
    if name == "planting_pot":
        c.poly([(7, 13), (25, 13), (22, 28), (10, 28)], "clay", "ink")
        c.box((7, 10, 25, 14), "ink"); c.box((8, 11, 24, 13), "terra")
        c.box((9, 9, 23, 10), "soil"); c.box((11, 9, 21, 9), "earth")
        c.box((11, 16, 12, 25), "terra"); c.box((20, 16, 22, 25), "earth")
        c.poly([(16, 18), (19, 21), (16, 24), (13, 21)], "gold")
    elif name == "watering_can":
        c.line([(20, 12), (24, 7), (28, 10), (28, 18), (24, 21)], "ink", 3)
        c.line([(21, 12), (25, 9), (27, 11), (27, 17), (24, 19)], "silver")
        c.poly([(10, 18), (6, 13), (4, 14), (7, 22), (12, 24)], "metal", "ink")
        c.poly([(9, 13), (22, 13), (24, 26), (10, 28), (8, 25)], "metal", "ink")
        c.box((10, 14, 22, 16), "silver"); c.box((11, 17, 12, 24), "silver")
        c.box((13, 10, 19, 12), "ink"); c.box((14, 11, 18, 12), "water")
        c.poly([(3, 10), (6, 10), (7, 14), (3, 16), (2, 13)], "silver", "ink")
        c.line([(14, 25), (21, 24)], "water")
    elif name == "plant_scanner":
        c.box((13, 23, 18, 28), "wood"); c.box((11, 28, 21, 29), "ink")
        c.poly([(10, 5), (21, 5), (26, 10), (26, 21), (21, 26), (10, 26), (5, 21), (5, 10)], "ink")
        c.poly([(11, 7), (20, 7), (24, 11), (24, 20), (20, 24), (11, 24), (7, 20), (7, 11)], "gold")
        c.box((10, 10, 21, 21), "shadow"); fan(c, 16, 18, .42, "leaf", "light")
        c.box((12, 3, 18, 5), "metal"); c.box((25, 13, 28, 17), "silver"); c.box((10, 9, 13, 10), "cream")
    elif name == "cleaning_kit":
        c.poly([(8, 7), (12, 4), (27, 20), (23, 24)], "wood", "ink")
        c.line([(11, 6), (24, 20)], "woodlight", 2)
        c.poly([(7, 14), (17, 24), (11, 29), (2, 20)], "sand", "ink")
        c.line([(6, 17), (13, 24)], "cream", 2); c.line([(4, 20), (10, 26)], "woodlight", 2)
        c.box((21, 5, 28, 9), "blue"); c.box((22, 7, 28, 10), "water")
    elif name == "treatment_spray":
        c.box((11, 13, 23, 28), "ink"); c.box((12, 14, 22, 26), "blue")
        c.box((13, 15, 14, 24), "silver"); c.box((13, 20, 22, 26), "water")
        c.box((15, 9, 19, 13), "metal"); c.box((12, 5, 24, 8), "ink")
        c.box((13, 6, 23, 7), "red"); c.box((20, 8, 21, 11), "clay")
        c.box((9, 6, 11, 8), "silver"); c.box((4, 4, 6, 4), "blue")
        c.box((3, 9, 5, 9), "blue"); c.box((16, 18, 19, 22), "cream")
    elif name == "repair_kit":
        c.box((9, 8, 23, 11), "ink"); c.box((11, 6, 21, 7), "metal")
        c.box((5, 12, 27, 27), "ink"); c.box((6, 13, 26, 25), "clay")
        c.box((6, 14, 26, 17), "terra"); c.box((15, 16, 18, 20), "silver")
        c.box((7, 22, 11, 24), "earth"); c.box((22, 22, 25, 24), "earth")
        c.line([(8, 5), (5, 8), (7, 10), (10, 7)], "silver", 2)
    elif name == "drying_rack":
        c.box((4, 5, 6, 29), "ink"); c.box((25, 5, 27, 29), "ink")
        c.box((5, 6, 5, 27), "woodlight"); c.box((26, 6, 26, 27), "woodlight")
        c.box((3, 6, 28, 9), "wood"); c.box((4, 6, 27, 6), "woodlight")
        for x in (10, 16, 22):
            c.line([(x, 9), (x, 14)], "sand")
            c.poly([(x, 13), (x - 2, 17), (x - 1, 23), (x + 1, 25), (x + 2, 19)], "green", "ink")
            c.line([(x, 15), (x, 22)], "leaf")
        c.box((4, 27, 27, 28), "wood")
    elif name == "curing_container":
        c.box((8, 9, 24, 27), "ink"); c.box((9, 11, 23, 26), "water")
        c.box((10, 12, 11, 23), "silver"); c.box((22, 13, 22, 25), "blue")
        bud(c, 17, 20, "amber", "leaf"); bud(c, 14, 23, "gold", "light")
        c.box((7, 5, 25, 10), "ink"); c.box((8, 6, 24, 8), "woodlight")
        c.box((10, 5, 22, 5), "sand"); c.box((10, 28, 22, 28), "metal")
    elif name == "grinder":
        c.poly([(7, 12), (11, 8), (23, 8), (27, 12), (27, 24), (23, 28), (11, 28), (7, 24)], "ink")
        c.box((8, 14, 26, 24), "metal"); c.box((9, 15, 11, 23), "silver")
        c.poly([(8, 12), (12, 9), (22, 9), (26, 12), (22, 16), (12, 16)], "silver")
        c.box((14, 10, 20, 13), "shadow"); c.box((14, 11, 20, 12), "leaf")
        c.line([(9, 21), (25, 21)], "ink"); c.box((14, 24, 21, 26), "silver")
    elif name == "farm_manager_table":
        c.box((5, 18, 8, 29), "ink"); c.box((24, 18, 27, 29), "ink")
        c.box((6, 20, 7, 28), "wood"); c.box((25, 20, 26, 28), "wood")
        c.poly([(4, 12), (21, 8), (29, 15), (29, 20), (4, 20)], "ink")
        c.poly([(5, 13), (21, 10), (27, 15), (10, 18), (5, 17)], "woodlight")
        c.box((5, 18, 28, 19), "wood")
        c.poly([(11, 10), (17, 9), (20, 12), (14, 14)], "cream", "sand")
        c.box((23, 7, 26, 13), "clay"); c.line([(24, 8), (24, 3)], "green", 2)
        c.box((22, 4, 23, 5), "leaf"); c.box((25, 2, 27, 3), "leaf")
    elif name == "selection_wand":
        c.line([(7, 27), (23, 11)], "ink", 5); c.line([(7, 26), (22, 11)], "woodlight", 2)
        c.poly([(21, 3), (28, 7), (24, 15), (17, 11)], "gold", "ink")
        c.poly([(22, 5), (26, 8), (23, 12), (20, 10)], "green"); c.line([(22, 6), (24, 8)], "light")
    elif name == "rolling_paper":
        c.poly([(5, 10), (23, 5), (28, 22), (10, 27)], "ink")
        c.poly([(6, 11), (22, 7), (26, 21), (11, 25)], "cream")
        c.line([(9, 13), (22, 10)], "white", 2); c.line([(12, 23), (24, 20)], "sand")
        c.line([(5, 10), (8, 21), (11, 24)], "paper")
    elif name == "filter":
        c.poly([(8, 10), (14, 5), (26, 18), (20, 26), (15, 27), (5, 15)], "ink")
        c.poly([(9, 11), (14, 7), (24, 18), (19, 24), (16, 25), (7, 15)], "paper")
        c.line([(10, 11), (20, 22)], "cream", 2)
        c.poly([(7, 13), (11, 11), (16, 16), (15, 20), (11, 21), (7, 17)], "sand", "earth")
        c.line([(9, 14), (12, 14), (14, 17), (12, 18), (10, 16)], "cream")
    elif name == "package":
        c.poly([(5, 10), (18, 5), (27, 11), (27, 24), (14, 29), (5, 23)], "ink")
        c.poly([(6, 11), (18, 7), (25, 11), (14, 16)], "paper")
        c.poly([(6, 12), (13, 17), (13, 27), (6, 22)], "sand")
        c.poly([(15, 17), (26, 13), (26, 23), (15, 27)], "woodlight")
        c.line([(11, 9), (21, 15), (21, 25)], "green", 2); c.line([(20, 8), (10, 14), (10, 25)], "green", 2)
    elif name == "joint":
        c.poly([(4, 24), (7, 20), (24, 7), (28, 8), (28, 12), (9, 27)], "ink")
        c.poly([(7, 22), (24, 9), (27, 10), (8, 25)], "cream"); c.line([(8, 24), (25, 11)], "paper")
        c.line([(5, 24), (8, 26)], "sand", 2); c.box((25, 8, 27, 10), "clay"); c.box((27, 8, 28, 9), "amber")
    elif name in ("fresh_batch", "dry_batch"):
        color, light = ("green", "leaf") if name == "fresh_batch" else ("amber", "sand")
        c.line([(10, 28), (19, 9)], "wood", 2); fan(c, 16, 21, .65, color, light)
        bud(c, 20, 9, color, light); bud(c, 12, 19, color, light); c.line([(8, 26), (14, 28)], "sand", 2)
    elif name == "cured_batch":
        c.poly([(5, 24), (8, 17), (14, 15), (19, 19), (19, 26), (13, 29), (7, 28)], "green", "ink")
        c.poly([(14, 16), (17, 8), (22, 5), (27, 10), (26, 20), (22, 24), (17, 22)], "leaf", "ink")
        for x, y in ((10, 21), (20, 12), (22, 17), (13, 25)):
            c.box((x, y, x + 1, y + 2), "light"); c.box((x + 2, y + 1, x + 2, y + 2), "amber")
    elif name in ("ground_product", "mixture"):
        c.poly([(4, 18), (8, 14), (24, 14), (28, 18), (25, 26), (21, 29), (11, 29), (7, 25)], "ink")
        c.poly([(6, 19), (9, 16), (23, 16), (26, 19), (22, 23), (10, 23)], "earth")
        c.poly([(7, 21), (11, 25), (22, 25), (26, 21), (24, 26), (21, 27), (11, 27), (8, 25)], "woodlight")
        for x, y, color in ((10, 18, "leaf"), (13, 16, "green"), (16, 19, "leaf"), (20, 17, "light"),
                            (22, 20, "green"), (13, 21, "amber"), (18, 22, "leaf")):
            c.box((x, y, x + 2, y + 1), color)
        if name == "mixture":
            c.line([(18, 16), (24, 7)], "silver", 3); c.box((23, 5, 26, 8), "white")
            c.box((10, 19, 12, 20), "purple"); c.box((18, 20, 20, 21), "gold")
    elif name in ("guide_book", "advanced_book"):
        accent = "green" if name == "guide_book" else "purple"
        c.poly([(6, 7), (22, 4), (27, 9), (27, 26), (11, 29), (6, 24)], "ink")
        c.poly([(9, 9), (24, 7), (25, 24), (11, 27)], "paper")
        c.poly([(6, 7), (21, 5), (25, 8), (10, 11)], accent)
        c.poly([(7, 8), (10, 12), (10, 26), (7, 23)], "shadow")
        c.poly([(11, 12), (26, 9), (26, 24), (11, 27)], accent, "ink"); c.line([(13, 13), (24, 11)], "gold")
        if name == "guide_book": fan(c, 18, 22, .4, "gold", "cream")
        else:
            c.poly([(18, 14), (23, 18), (19, 24), (14, 20)], "gold")
            c.poly([(18, 16), (21, 19), (19, 22), (16, 20)], "purple")
    elif name == "plantation_contract":
        c.poly([(8, 4), (24, 4), (24, 25), (21, 28), (8, 28)], "ink")
        c.box((9, 5, 23, 26), "cream"); c.box((8, 4, 24, 6), "sand")
        for y in (10, 13, 16): c.line([(12, y), (20, y)], "woodlight")
        c.poly([(18, 20), (21, 20), (23, 23), (20, 26), (17, 24)], "red", "clay")
        c.line([(19, 25), (18, 29)], "red", 2); c.line([(21, 25), (23, 28)], "red", 2)
    else: raise ValueError("Undesigned icon: " + name)
    return c.image

def sprites():
    result = {name: icon(name) for name, _, _ in ITEMS}
    result.update({"plant_" + stage: plant(stage) for stage in STAGES})
    for index, (strain_id, _, accent, light) in enumerate(STRAINS):
        result["seed_" + strain_id] = packet(accent, light, index)
        for stage in STAGES:
            result[f"plant_{stage}_{strain_id}"] = plant(stage, accent, light, index)
    return result
