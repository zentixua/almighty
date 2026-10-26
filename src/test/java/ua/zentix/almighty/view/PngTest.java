package ua.zentix.almighty.view;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Our PNG is read unchanged by Java's standard decoder. */
class PngTest {
    @Test
    void decodesToSamePixels() throws Exception {
        Png png = new Png(7, 5);
        for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) png.set(x, y, (x * 37) << 16 | (y * 51) << 8 | ((x + y) * 13));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png.encode()));
        assertEquals(7, image.getWidth());
        assertEquals(5, image.getHeight());
        for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) assertEquals(png.get(x, y), image.getRGB(x, y) & 0xFFFFFF, x + "," + y);
    }

    @Test
    void upscaleWithGridOffset() throws Exception {
        Png png = new Png(4, 4);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) png.set(x, y, 0xC8C8C8);
        Png big = png.upscale(4, 2, 1, 0);
        assertEquals(16, big.width());
        // Lines at cells whose offset indices are divisible by 2: cell columns 1 and 3, cell rows 0 and 2.
        assertEquals(Png.shade(0xC8C8C8, 0.6), big.get(4, 1));
        assertEquals(0xC8C8C8, big.get(9, 1));
        assertEquals(Png.shade(0xC8C8C8, 0.6), big.get(9, 0));
        assertEquals(0xC8C8C8, big.get(1, 5));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(big.encode()));
        assertEquals(big.get(4, 1), image.getRGB(4, 1) & 0xFFFFFF);
    }
}
