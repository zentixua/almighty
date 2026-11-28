package ua.zentix.almighty.policy;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * One entry of an agent's {@code allow_from} (plan 9.1, scenario F1): an IP address or a CIDR range, IPv4 or IPv6
 * ({@code 203.0.113.7}, {@code 10.0.0.0/8}, {@code fd00::/8}). Literal addresses only: names are never resolved.
 */
public record AddressRange(String text, byte[] network, int bits) {
    /** Parse one entry; throws IllegalArgumentException with the reason. */
    public static AddressRange parse(String text) {
        String t = text.strip();
        int slash = t.indexOf('/');
        String host = slash < 0 ? t : t.substring(0, slash);
        if (!host.matches("[0-9a-fA-F:.]+") || !(host.contains(".") || host.contains(":"))) {
            throw new IllegalArgumentException("not an IP address or range: " + text + " (names are not looked up)");
        }
        byte[] addr;
        try {
            addr = InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not an IP address: " + text);
        }
        int bits = addr.length * 8;
        if (slash >= 0) {
            try {
                bits = Integer.parseInt(t.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad prefix length in " + text);
            }
            if (bits < 0 || bits > addr.length * 8) throw new IllegalArgumentException("prefix length out of range in " + text);
        }
        return new AddressRange(t, addr, bits);
    }

    /** Whether {@code address} (a literal IP) lies in this range; an IPv4-mapped IPv6 address counts as IPv4. */
    public boolean contains(String address) {
        byte[] a;
        try {
            a = InetAddress.getByName(address.startsWith("[") ? address.substring(1, address.length() - 1) : address).getAddress();
        } catch (UnknownHostException | IllegalArgumentException e) {
            return false;
        }
        if (a.length != network.length) return false;
        int full = bits / 8, rest = bits % 8;
        for (int i = 0; i < full; i++) if (a[i] != network[i]) return false;
        if (rest == 0) return true;
        int mask = 0xFF << (8 - rest) & 0xFF;
        return (a[full] & mask) == (network[full] & mask);
    }
}
