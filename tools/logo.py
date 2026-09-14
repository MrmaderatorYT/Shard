"""
Generates every launcher-icon asset for Shard from one geometric definition.

The mark is a faceted crystal shard: an asymmetric five-sided silhouette split
by a centre ridge into three facets, plus a tapered rim light along the top-left
edge. Asymmetry is deliberate — a symmetric diamond reads as "gem", while the
stepped lower-right edge reads as a fragment, which is what the app is named for.

Everything derives from the same normalised outline, so the vector drawables,
the legacy bitmaps and the monochrome layer can never drift apart.
"""

from PIL import Image, ImageDraw
import os

# --------------------------------------------------------------------- geometry

# Normalised into a unit box, y pointing down.
#
# A cut gem seen slightly from above: a bright top face (the "table") and two
# side faces converging on a bottom apex. Three faces at clearly separated
# lightness levels is what makes the form read as a solid crystal at 48 px,
# where finer faceting turns to mush. The apex sits right of centre and the
# right face is steeper, so the shape is a fragment rather than a tidy diamond.
TL = (0.175, 0.345)   # table, left corner
TT = (0.505, 0.040)   # table, top corner
TR = (0.905, 0.290)   # table, right corner
TB = (0.445, 0.520)   # table, bottom corner - where the side faces meet
BA = (0.570, 1.000)   # bottom apex

OUTLINE = [TL, TT, TR, BA]

FACE_TABLE = [TL, TT, TR, TB]   # brightest
FACE_LEFT  = [TL, TB, BA]       # mid
FACE_RIGHT = [TB, TR, BA]       # deepest

# ----------------------------------------------------------------------- colour

BG_FROM      = (0x31, 0x3C, 0x6B)
BG_TO        = (0x0F, 0x13, 0x24)
TABLE_FROM   = (0xC8, 0xDC, 0xFF)
TABLE_TO     = (0x79, 0xA6, 0xFC)
LEFT_FROM    = (0x55, 0x87, 0xF4)
LEFT_TO      = (0x32, 0x5C, 0xCB)
# Kept clearly lighter than the background: at 48 px on a dark wallpaper an
# almost-black facet makes the silhouette dissolve into the icon's own backdrop.
RIGHT_FROM   = (0x30, 0x53, 0xB8)
RIGHT_TO     = (0x1C, 0x32, 0x7C)

def hexof(rgb):
    return "#%02X%02X%02X" % rgb

# ------------------------------------------------------------------- rasteriser

SS = 8  # supersampling factor

def gradient_image(size, c_from, c_to, angle="diag"):
    """A linear gradient the size of the canvas, used as a paint source."""
    img = Image.new("RGB", (size, size))
    pixels = img.load()
    for y in range(size):
        for x in range(size):
            if angle == "diag":
                t = (x + y) / (2.0 * (size - 1))
            elif angle == "vertical":
                t = y / float(size - 1)
            else:
                t = x / float(size - 1)
            pixels[x, y] = (
                int(c_from[0] + (c_to[0] - c_from[0]) * t),
                int(c_from[1] + (c_to[1] - c_from[1]) * t),
                int(c_from[2] + (c_to[2] - c_from[2]) * t),
            )
    return img

def scaled(points, origin, span):
    return [(origin[0] + px * span, origin[1] + py * span) for px, py in points]

def paint_polygon(target, points, c_from, c_to, angle="vertical", alpha=255):
    """Fills a polygon on `target` with a linear gradient."""
    size = target.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).polygon(points, fill=alpha)
    target.paste(gradient_image(size, c_from, c_to, angle).convert("RGBA"), (0, 0), mask)

def shape_mask(size, kind):
    """Legacy icons carry their own shape: a squircle-ish rounded square, or a circle."""
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    if kind == "round":
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        radius = int(size * 0.235)
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask

def render(size, kind, content_fraction, with_background=True):
    """Renders one icon at `size` px, supersampled then downsampled."""
    big = size * SS
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))

    if with_background:
        bg = gradient_image(big, BG_FROM, BG_TO, "diag").convert("RGBA")
        canvas.paste(bg, (0, 0), shape_mask(big, kind))

    span = big * content_fraction
    origin = ((big - span) / 2.0, (big - span) / 2.0)

    paint_polygon(canvas, scaled(FACE_RIGHT, origin, span), RIGHT_FROM, RIGHT_TO)
    paint_polygon(canvas, scaled(FACE_LEFT, origin, span), LEFT_FROM, LEFT_TO)
    paint_polygon(canvas, scaled(FACE_TABLE, origin, span), TABLE_FROM, TABLE_TO)

    if with_background:
        # Keep facets inside the icon shape even if the artwork overshoots.
        canvas.putalpha(Image.composite(canvas.getchannel("A"),
                                        Image.new("L", (big, big), 0),
                                        shape_mask(big, kind)))

    return canvas.resize((size, size), Image.LANCZOS)

# --------------------------------------------------------------- vector output

def path_data(points, origin, span):
    pts = scaled(points, origin, span)
    out = "M%.2f,%.2f" % pts[0]
    for p in pts[1:]:
        out += "L%.2f,%.2f" % p
    return out + "z"

VECTOR_HEADER = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  Shard's mark: a faceted crystal shard.

  Generated from the geometry in tools/logo.py so the adaptive layers, the
  monochrome layer and the legacy bitmaps stay identical. Edit the generator, not
  this file.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
'''

def gradient_path(data, c_from, c_to, x1, y1, x2, y2):
    return '''    <path android:pathData="%s">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="%.1f" android:startY="%.1f"
                android:endX="%.1f" android:endY="%.1f">
                <item android:offset="0" android:color="%s" />
                <item android:offset="1" android:color="%s" />
            </gradient>
        </aapt:attr>
    </path>
''' % (data, x1, y1, x2, y2, hexof(c_from), hexof(c_to))

def write_vectors(res_dir):
    # Adaptive foreground: artwork must sit inside the 66dp safe zone of 108dp.
    span = 66.0
    origin = ((108 - span) / 2.0, (108 - span) / 2.0)
    top = origin[1]
    bottom = origin[1] + span

    body = VECTOR_HEADER.replace(
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"')
    body += gradient_path(path_data(FACE_RIGHT, origin, span),
                          RIGHT_FROM, RIGHT_TO, 54, top, 54, bottom)
    body += gradient_path(path_data(FACE_LEFT, origin, span),
                          LEFT_FROM, LEFT_TO, 54, top, 54, bottom)
    body += gradient_path(path_data(FACE_TABLE, origin, span),
                          TABLE_FROM, TABLE_TO, 54, top, 54, bottom)
    body += "</vector>\n"
    open(os.path.join(res_dir, "drawable/ic_launcher_foreground.xml"), "w").write(body)

    # Background: a deep indigo gradient, drawn full-bleed because the launcher masks it.
    bg = VECTOR_HEADER.replace(
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"')
    bg += '''    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108">
                <item android:offset="0" android:color="%s" />
                <item android:offset="1" android:color="%s" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
''' % (hexof(BG_FROM), hexof(BG_TO))
    open(os.path.join(res_dir, "drawable/ic_launcher_background.xml"), "w").write(bg)

    # Monochrome layer for themed icons: one solid silhouette, tinted by the system.
    mono = VECTOR_HEADER
    mono += '    <path\n        android:fillColor="#FF000000"\n        android:pathData="%s" />\n' \
            % path_data(OUTLINE, origin, span)
    mono += "</vector>\n"
    open(os.path.join(res_dir, "drawable/ic_launcher_monochrome.xml"), "w").write(mono)

    # In-app mark: flat fills only, so it renders identically on API 21 through
    # VectorDrawableCompat, which has patchy gradient support.
    span2 = 88.0
    origin2 = ((108 - span2) / 2.0, (108 - span2) / 2.0)
    mark = '''<?xml version="1.0" encoding="utf-8"?>
<!-- The Shard mark without a background, for use inside the app. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
'''
    mark += '    <path android:fillColor="#26439A" android:pathData="%s" />\n' \
            % path_data(FACE_RIGHT, origin2, span2)
    mark += '    <path android:fillColor="#3763D0" android:pathData="%s" />\n' \
            % path_data(FACE_LEFT, origin2, span2)
    mark += '    <path android:fillColor="#9CC0FE" android:pathData="%s" />\n' \
            % path_data(FACE_TABLE, origin2, span2)
    mark += "</vector>\n"
    open(os.path.join(res_dir, "drawable/ic_shard_mark.xml"), "w").write(mark)

# ------------------------------------------------------------------------- main

if __name__ == "__main__":
    import sys
    res = sys.argv[1]
    out = sys.argv[2]

    write_vectors(res)

    # Legacy bitmaps for API 21-25, which have no adaptive-icon support.
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, size in densities.items():
        folder = os.path.join(res, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        render(size, "square", 0.62).save(os.path.join(folder, "ic_launcher.png"))
        render(size, "round", 0.58).save(os.path.join(folder, "ic_launcher_round.png"))

    # Store listing and a preview sheet.
    render(512, "square", 0.62).save(os.path.join(out, "playstore-icon.png"))
    for size in (48, 72, 96, 192):
        render(size, "square", 0.62).save(os.path.join(out, "preview-%d.png" % size))
    render(192, "round", 0.58).save(os.path.join(out, "preview-round.png"))
    print("assets written")
