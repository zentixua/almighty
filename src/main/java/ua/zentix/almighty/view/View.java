package ua.zentix.almighty.view;

import com.google.gson.JsonObject;
import ua.zentix.almighty.work.Job;

import java.util.Base64;

/**
 * World snapshot as a job within the tick budget: the server thread reads blocks by rows; the bridge thread
 * encodes the image as PNG ({@link #attach}), keeping compression out of the tick. The image is retained until
 * delivered or until the job leaves history.
 */
public abstract class View extends Job {
    private volatile Png image;
    protected final JsonObject legend = new JsonObject();
    private int upscale = 1, grid, gridX, gridY;

    protected View(String kind) {
        super(kind);
    }

    /** Finished image (server thread, before {@code finish}); scale to about 512 pixels, with a grid every {@code grid} cells. */
    protected final void image(Png png, int grid, int gridX, int gridY) {
        this.upscale = Math.max(1, Math.min(8, 512 / Math.max(png.width(), png.height())));
        this.grid = grid;
        this.gridX = gridX;
        this.gridY = gridY;
        legend.addProperty("pixels_per_cell", upscale);
        this.image = png;
    }

    protected final int upscale() {
        return upscale;
    }

    @Override
    public long retained() {
        Png png = image;
        return png == null ? 0 : (long) png.width() * png.height();
    }

    @Override
    public void dropRetained() {
        image = null;
    }

    @Override
    protected void release() {}

    /** A snapshot is brief and its response has a waiting call: do not add it to the feed. */
    @Override
    public boolean announce() {
        return false;
    }

    @Override
    protected void payload(JsonObject out) {
        out.add("legend", legend.deepCopy());
    }

    /** Bridge thread: append a PNG image (base64) to the completed job description. Delivered once. */
    public JsonObject attach(JsonObject result) {
        Png png = image;
        // Use the state from the description, made by the server thread; this thread need not see the job field.
        if (png == null || !result.has("state") || !result.get("state").getAsString().equals("done")) return result;
        image = null;
        Png big = png.upscale(upscale, grid, gridX, gridY);
        result.addProperty("png_base64", Base64.getEncoder().encodeToString(big.encode()));
        result.addProperty("width", big.width());
        result.addProperty("height", big.height());
        if (!result.has("legend")) result.add("legend", legend.deepCopy());
        return result;
    }
}
