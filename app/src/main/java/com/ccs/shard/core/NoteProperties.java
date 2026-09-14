package com.ccs.shard.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Simple scalar YAML properties stored in a note's preserved front matter. */
public final class NoteProperties {

    private NoteProperties() {}

    public static Map<String, String> read(Note note) {
        Map<String, String> values = new LinkedHashMap<>();
        if (note == null) return values;
        if (!note.getAliases().isEmpty()) values.put("aliases", join(note.getAliases()));
        for (String line : note.getFrontMatterExtra()) {
            if (line == null || line.startsWith(" ") || line.startsWith("\t")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            if (!validKey(key)) continue;
            values.put(key, unquote(line.substring(colon + 1).trim()));
        }
        return values;
    }

    public static boolean put(Note note, String key, String value) {
        key = key == null ? "" : key.trim();
        value = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (note == null || !validKey(key)) return false;
        if (key.equalsIgnoreCase("aliases") || key.equalsIgnoreCase("alias")) {
            note.setAliases(splitAliases(value));
            return true;
        }
        if (reserved(key)) return false;
        List<String> lines = new ArrayList<>(note.getFrontMatterExtra());
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int colon = line == null ? -1 : line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(key)) {
                lines.set(i, key + ": " + yamlScalar(value));
                replaced = true;
                break;
            }
        }
        if (!replaced) lines.add(key + ": " + yamlScalar(value));
        note.setFrontMatterExtra(lines);
        return true;
    }

    public static void remove(Note note, String key) {
        if (note == null || key == null) return;
        if (key.equalsIgnoreCase("aliases") || key.equalsIgnoreCase("alias")) {
            note.setAliases(new ArrayList<String>(0));
            return;
        }
        List<String> lines = new ArrayList<>(note.getFrontMatterExtra());
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            int colon = line == null ? -1 : line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(key)) {
                lines.remove(i);
                // Also remove YAML sequence continuation lines belonging to this key.
                while (i < lines.size() && (lines.get(i).startsWith(" ")
                        || lines.get(i).startsWith("\t"))) lines.remove(i);
            }
        }
        note.setFrontMatterExtra(lines);
    }

    private static boolean validKey(String key) {
        if (key == null || key.isEmpty()) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') return false;
        }
        return true;
    }

    private static boolean reserved(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("tags") || k.equals("tag") || k.equals("created")
                || k.equals("modified") || k.equals("emoji") || k.equals("icon")
                || k.equals("color") || k.equals("pinned") || k.equals("archived")
                || k.equals("bookmarked") || k.equals("starred") || k.equals("title")
                || k.equals("aliases") || k.equals("alias");
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(value.trim());
        }
        return out.toString();
    }

    private static List<String> splitAliases(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String alias = unquote(part.trim());
            if (!alias.isEmpty() && !out.contains(alias)) out.add(alias);
        }
        return out;
    }

    private static String yamlScalar(String value) {
        if (value.isEmpty()) return "\"\"";
        if (value.matches("[A-Za-z0-9_./ -]+")
                && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\"' && last == '\"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
