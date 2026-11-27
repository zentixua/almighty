"""Pixel-art toolkit for the Almighty brand kit.

Everything is drawn on a native pixel grid (RGBA, no anti-aliasing) and enlarged only by integer
nearest-neighbour factors. No filters, no gradients computed per pixel, no image generation.
"""
from PIL import Image, ImageDraw

T = (0, 0, 0, 0)


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


class Layer:
    def __init__(self, w, h, fill=T):
        self.im = Image.new('RGBA', (w, h), fill)
        self.d = ImageDraw.Draw(self.im)
        self.w, self.h = w, h

    def px(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.im.putpixel((x, y), c)

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.im.getpixel((x, y))
        return T

    def rect(self, x0, y0, x1, y1, c):
        """Inclusive rectangle."""
        if x1 < x0 or y1 < y0:
            return
        self.d.rectangle([x0, y0, x1, y1], fill=c)

    def poly(self, pts, c):
        self.d.polygon(pts, fill=c)

    def line(self, x0, y0, x1, y1, c):
        # Bresenham, one pixel wide.
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.px(x0, y0, c)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def sprite(self, rows, ox, oy, pal):
        """Hand-drawn ASCII sprite: each character is a palette key; '.' and ' ' are empty."""
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in '. ':
                    continue
                self.px(ox + x, oy + y, pal[ch])

    def recolor(self, src, dst, box=None):
        x0, y0, x1, y1 = box or (0, 0, self.w - 1, self.h - 1)
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if self.get(x, y) == src:
                    self.px(x, y, dst)

    def outline(self, c, diag=False):
        """1-px outline around every opaque pixel (4-neighbourhood, or 8 with diag)."""
        src = self.im.copy()
        nb = [(1, 0), (-1, 0), (0, 1), (0, -1)]
        if diag:
            nb += [(1, 1), (-1, 1), (1, -1), (-1, -1)]
        for y in range(self.h):
            for x in range(self.w):
                if src.getpixel((x, y))[3]:
                    continue
                for dx, dy in nb:
                    xx, yy = x + dx, y + dy
                    if 0 <= xx < self.w and 0 <= yy < self.h and src.getpixel((xx, yy))[3]:
                        self.im.putpixel((x, y), c)
                        break
        return self

    def shadow(self, c, dx=0, dy=1):
        """Hard drop shadow: copy of the silhouette, offset, under the layer."""
        sil = Layer(self.w, self.h)
        for y in range(self.h):
            for x in range(self.w):
                if self.im.getpixel((x, y))[3]:
                    sil.px(x + dx, y + dy, c)
        sil.im.alpha_composite(self.im)
        self.im = sil.im
        self.d = ImageDraw.Draw(self.im)
        return self

    def paste(self, other, ox, oy):
        im = other.im if isinstance(other, Layer) else other
        self.im.alpha_composite(im, (ox, oy)) if ox >= 0 and oy >= 0 else self._paste_clip(im, ox, oy)

    def _paste_clip(self, im, ox, oy):
        tmp = Image.new('RGBA', (self.w, self.h), T)
        tmp.paste(im, (ox, oy))
        self.im.alpha_composite(tmp)

    def bbox(self):
        return self.im.getbbox()


def up(im, k):
    im = im.im if isinstance(im, Layer) else im
    return im.resize((im.width * k, im.height * k), Image.NEAREST)


def crop_to_content(layer, pad=0):
    b = layer.bbox()
    im = layer.im.crop((b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad))
    out = Layer(im.width, im.height)
    out.im = im
    out.d = ImageDraw.Draw(out.im)
    return out
