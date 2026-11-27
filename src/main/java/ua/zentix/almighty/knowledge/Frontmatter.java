package ua.zentix.almighty.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent Skills frontmatter (https://agentskills.io/specification): an optional block between two {@code ---} lines at
 * the top of a Markdown file, with {@code key: value} lines ({@code name}, {@code description} and others). Only flat
 * string values are read; a quoted value loses its quotes; nested YAML is kept as text. A file without the block is
 * all body (legacy notes).
 */
public record Frontmatter(Map<String, String> fields, String body) {
    public Frontmatter {
        fields = Map.copyOf(fields);
    }

    public static Frontmatter parse(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (normalized.startsWith("﻿")) normalized = normalized.substring(1);
        if (!normalized.startsWith("---\n")) return new Frontmatter(Map.of(), normalized.strip());
        int end = normalized.indexOf("\n---", 3);
        while (end >= 0) {
            int after = end + 4;
            if (after == normalized.length() || normalized.charAt(after) == '\n') break;
            end = normalized.indexOf("\n---", after);
        }
        if (end < 0) return new Frontmatter(Map.of(), normalized.strip());
        Map<String, String> fields = new LinkedHashMap<>();
        String key = null;
        for (String line : normalized.substring(4, end).split("\n", -1)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int colon = line.indexOf(':');
            boolean continuation = Character.isWhitespace(line.charAt(0)) || colon < 0;
            if (continuation) {
                // A folded continuation line or nested YAML: append to the previous key as text.
                if (key != null) fields.merge(key, line.strip(), (a, b) -> a.isEmpty() ? b : a + " " + b);
                continue;
            }
            key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.equals(">") || value.equals("|") || value.equals(">-") || value.equals("|-")) value = "";
            fields.put(key, unquote(value));
        }
        int bodyStart = Math.min(normalized.length(), end + 4);
        return new Frontmatter(fields, normalized.substring(bodyStart).strip());
    }

    /** Text with this block first; values with a colon, a quote or a leading special character are quoted. */
    public String render() {
        StringBuilder out = new StringBuilder();
        if (!fields.isEmpty()) {
            out.append("---\n");
            fields.forEach((k, v) -> out.append(k).append(": ").append(quote(v)).append('\n'));
            out.append("---\n\n");
        }
        return out.append(body).append('\n').toString();
    }

    public String get(String key) {
        return fields.get(key);
    }

    /** The {@code description} field, else the first non-empty body line without Markdown heading marks. */
    public String description() {
        String d = fields.get("description");
        if (d != null && !d.isBlank()) return d.strip();
        for (String line : body.split("\n")) {
            String s = line.strip();
            while (s.startsWith("#")) s = s.substring(1);
            s = s.strip();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            String inner = v.substring(1, v.length() - 1);
            return v.startsWith("\"") ? inner.replace("\\\"", "\"").replace("\\\\", "\\") : inner.replace("''", "'");
        }
        return v;
    }

    private static String quote(String v) {
        String one = v.replace('\n', ' ').strip();
        boolean plain = !one.isEmpty() && !one.contains(": ") && !one.contains(" #") && "\"'#&*!|>%@`{}[],-?:".indexOf(one.charAt(0)) < 0;
        return plain ? one : "\"" + one.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
