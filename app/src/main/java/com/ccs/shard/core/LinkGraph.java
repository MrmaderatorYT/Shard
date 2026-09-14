package com.ccs.shard.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinkGraph {
    private final Map<String, Set<String>> adjacency;
    private final Map<String, Set<String>> reverseAdjacency;
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]|]+?)(?:\\|([^\\]]+?))?\\]\\]");

    public LinkGraph() {
        adjacency = new HashMap<>();
        reverseAdjacency = new HashMap<>();
    }

    public void addNote(String noteId) {
        if (!adjacency.containsKey(noteId)) {
            adjacency.put(noteId, new HashSet<>());
        }
        if (!reverseAdjacency.containsKey(noteId)) {
            reverseAdjacency.put(noteId, new HashSet<>());
        }
    }

    public void addLink(String fromId, String toId) {
        addNote(fromId);
        addNote(toId);
        adjacency.get(fromId).add(toId);
        reverseAdjacency.get(toId).add(fromId);
    }

    public void removeNote(String noteId) {
        Set<String> outgoing = adjacency.remove(noteId);
        if (outgoing != null) {
            for (String target : outgoing) {
                Set<String> incoming = reverseAdjacency.get(target);
                if (incoming != null) incoming.remove(noteId);
            }
        }
        Set<String> incoming = reverseAdjacency.remove(noteId);
        if (incoming != null) {
            for (String source : incoming) {
                Set<String> out = adjacency.get(source);
                if (out != null) out.remove(noteId);
            }
        }
    }

    public Set<String> getLinksFrom(String noteId) {
        Set<String> links = adjacency.get(noteId);
        return links != null ? new HashSet<>(links) : new HashSet<>();
    }

    public Set<String> getLinksTo(String noteId) {
        Set<String> links = reverseAdjacency.get(noteId);
        return links != null ? new HashSet<>(links) : new HashSet<>();
    }

    public Set<String> getAllNotes() {
        return new HashSet<>(adjacency.keySet());
    }

    public int getNodeCount() {
        return adjacency.size();
    }

    public int getEdgeCount() {
        int count = 0;
        for (Set<String> links : adjacency.values()) {
            count += links.size();
        }
        return count;
    }

    public static Map<String, String> parseWikiLinks(String content) {
        Map<String, String> links = new HashMap<>();
        Matcher matcher = WIKI_LINK.matcher(content);
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            String alias = matcher.group(2);
            if (alias == null) alias = target;
            links.put(target, alias);
        }
        return links;
    }

    public static Set<String> parseTags(String content) {
        Set<String> tags = new HashSet<>();
        Pattern tagPattern = Pattern.compile("#([a-zA-Z][a-zA-Z0-9_]*)");
        Matcher matcher = tagPattern.matcher(content);
        while (matcher.find()) {
            tags.add(matcher.group(1));
        }
        return tags;
    }

    public String toGraphJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"nodes\":[");
        boolean first = true;
        for (String noteId : adjacency.keySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"id\":\"").append(escapeJson(noteId)).append("\"");
            json.append(",\"connections\":").append(adjacency.get(noteId).size());
            json.append(",\"backlinks\":").append(
                reverseAdjacency.containsKey(noteId) ? reverseAdjacency.get(noteId).size() : 0
            );
            json.append("}");
        }
        json.append("],\"edges\":[");
        first = true;
        for (Map.Entry<String, Set<String>> entry : adjacency.entrySet()) {
            for (String target : entry.getValue()) {
                if (!first) json.append(",");
                first = false;
                json.append("{\"from\":\"").append(escapeJson(entry.getKey())).append("\"");
                json.append(",\"to\":\"").append(escapeJson(target)).append("\"}");
            }
        }
        json.append("]}");
        return json.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
