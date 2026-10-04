package ua.zentix.almighty.world;

import com.google.gson.JsonObject;
import ua.zentix.almighty.work.Budget;
import ua.zentix.almighty.work.Job;

/** Ожидание готовности подготовленного района ({@code area.prepare}); сам район живёт своим сроком, работа его не держит. */
public final class PrepareJob extends Job {
    private final Areas.Lease lease;

    public PrepareJob(Areas.Lease lease) {
        super("prepare");
        this.lease = lease;
    }

    @Override
    protected void step(Budget budget) {
        if (lease.released()) fail("Район №" + lease.id() + " отпущен раньше, чем загрузился");
        else if (lease.isReady()) finish();
        else if (lease.refusal() != null) fail(lease.refusal());
    }

    @Override
    protected void release() {}

    @Override
    protected void details(JsonObject out) {
        out.add("area", lease.describe());
    }
}
