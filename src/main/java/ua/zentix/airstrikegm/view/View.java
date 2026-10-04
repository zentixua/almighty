package ua.zentix.airstrikegm.view;

import com.google.gson.JsonObject;
import ua.zentix.airstrikegm.work.Job;

import java.util.Base64;

/**
 * Снимок мира работой под бюджетом тика: поток сервера читает блоки по строкам, картинку в PNG кодирует поток моста
 * ({@link #attach}) — сжатие не занимает тик. Картинка держится до выдачи или до ухода работы из истории.
 */
public abstract class View extends Job {
    private volatile Png image;
    protected final JsonObject legend = new JsonObject();
    private int upscale = 1, grid, gridX, gridY;

    protected View(String kind) {
        super(kind);
    }

    /** Готовая картинка (поток сервера, перед {@code finish}); увеличение до ~512 точек, сетка — каждые {@code grid}. */
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

    /** Снимок короткий, его ответ ждёт вызов: в ленту не попадает. */
    @Override
    public boolean announce() {
        return false;
    }

    @Override
    protected void payload(JsonObject out) {
        out.add("legend", legend.deepCopy());
    }

    /** Поток моста: к описанию кончившейся работы — картинка PNG (base64). Выдаётся один раз. */
    public JsonObject attach(JsonObject result) {
        Png png = image;
        // состояние — из описания: его составил поток сервера, а поле работы этот поток видеть не обязан
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
