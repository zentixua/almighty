package ua.zentix.airstrikegm.view;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Картинка снимка и PNG без AWT: на сервере в контейнере может не быть ни графической библиотеки Java, ни шрифтов.
 * Пиксели — {@code 0xRRGGBB}; увеличение — ближайшим соседом, с линиями сетки.
 */
public final class Png {
    private final int width, height;
    private final int[] rgb;

    public Png(int width, int height) {
        this.width = width;
        this.height = height;
        this.rgb = new int[width * height];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void set(int x, int y, int color) {
        rgb[y * width + x] = color & 0xFFFFFF;
    }

    public int get(int x, int y) {
        return rgb[y * width + x];
    }

    /**
     * Каждый пиксель — квадратом {@code k × k}; линия сетки (затемнение) — по левому и верхнему краю клеток, чей номер
     * со сдвигом ({@code offX}, {@code offY}) делится на {@code grid} (0 — без сетки; при {@code k < 3} сетки нет).
     */
    public Png upscale(int k, int grid, int offX, int offY) {
        if (k == 1) return this;
        Png out = new Png(width * k, height * k);
        for (int y = 0; y < out.height; y++) {
            boolean row = grid > 0 && k >= 3 && y % k == 0 && Math.floorMod(y / k + offY, grid) == 0;
            for (int x = 0; x < out.width; x++) {
                int c = rgb[(y / k) * width + x / k];
                boolean line = row || (grid > 0 && k >= 3 && x % k == 0 && Math.floorMod(x / k + offX, grid) == 0);
                out.rgb[y * out.width + x] = line ? shade(c, 0.6) : c;
            }
        }
        return out;
    }

    /** Цвет, умноженный на {@code k} (0..1). */
    public static int shade(int c, double k) {
        int r = (int) (((c >> 16) & 255) * k), g = (int) (((c >> 8) & 255) * k), b = (int) ((c & 255) * k);
        return (r << 16) | (g << 8) | b;
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            file.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(header);
            h.writeInt(width);
            h.writeInt(height);
            h.writeByte(8); // глубина
            h.writeByte(2); // RGB
            h.writeByte(0);
            h.writeByte(0);
            h.writeByte(0);
            chunk(file, "IHDR", header.toByteArray());
            // фильтр Sub: соседние пиксели карты часто одного цвета — сжимается лучше
            byte[] raw = new byte[height * (1 + width * 3)];
            int i = 0;
            for (int y = 0; y < height; y++) {
                raw[i++] = 1;
                int prev = 0;
                for (int x = 0; x < width; x++) {
                    int c = rgb[y * width + x];
                    raw[i++] = (byte) (((c >> 16) & 255) - ((prev >> 16) & 255));
                    raw[i++] = (byte) (((c >> 8) & 255) - ((prev >> 8) & 255));
                    raw[i++] = (byte) ((c & 255) - (prev & 255));
                    prev = c;
                }
            }
            Deflater deflater = new Deflater(6);
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream data = new ByteArrayOutputStream(raw.length / 4 + 64);
            byte[] buffer = new byte[1 << 16];
            while (!deflater.finished()) data.write(buffer, 0, deflater.deflate(buffer));
            deflater.end();
            chunk(file, "IDAT", data.toByteArray());
            chunk(file, "IEND", new byte[0]);
            return file.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void chunk(ByteArrayOutputStream file, String type, byte[] data) throws IOException {
        DataOutputStream out = new DataOutputStream(file);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(name);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
