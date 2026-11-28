package ua.zentix.almighty.policy;

import ua.zentix.almighty.bridge.RpcException;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure part of {@code role {player, role, duration? | until?}} (plan 4.10, scenario O17 "make Bob a builder
 * until 23:00"): when a grant ends, and whether a change grants (needs the owner's approval when the agent acts on
 * its own) or only restricts. The rest is in {@code GmServer.role}.
 */
public final class RoleCalls {
    private RoleCalls() {}

    private static final Pattern CLOCK = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)");

    /** Milliseconds since the epoch of the next {@code HH:MM} in {@code zone} after {@code now}. */
    public static long until(String clock, ZoneId zone, Instant now) throws RpcException {
        Matcher m = CLOCK.matcher(clock.strip());
        if (!m.matches()) throw RpcException.badRequest("until: a time of day HH:MM (24-hour, the policy's timezone), not " + clock);
        ZonedDateTime here = now.atZone(zone);
        ZonedDateTime at = here.with(LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        if (!at.isAfter(here)) at = at.plusDays(1);
        return at.toInstant().toEpochMilli();
    }

    /**
     * Whether giving {@code neu} (null clears) ending at {@code until} (0: never) grants anything: a role above the
     * player's current one, or the same granted role for longer.
     */
    public static boolean grants(Role current, Roles.Grant granted, Role neu, long until) {
        if (neu == null) return false;
        if (neu.compareTo(current) > 0) return true;
        if (granted == null || granted.role() != neu) return false;
        return granted.until() != 0 && (until == 0 || until > granted.until());
    }
}
