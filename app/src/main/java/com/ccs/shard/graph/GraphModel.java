package com.ccs.shard.graph;

import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteIndex;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The graph as flat primitive arrays.
 *
 * <p>Structure-of-arrays rather than a node object per note: the layout loop
 * touches every node several hundred times, and on a budget SoC the difference
 * between walking three {@code float[]}s and chasing several thousand object
 * references is the difference between a graph that settles smoothly and one
 * that stutters through garbage collection.
 */
public final class GraphModel {

    public static final int KIND_NOTE = 0;
    public static final int KIND_TAG = 1;
    /** A wiki link whose target does not exist yet. */
    public static final int KIND_MISSING = 2;

    public int nodeCount;
    public float[] x = new float[0];
    public float[] y = new float[0];
    public float[] vx = new float[0];
    public float[] vy = new float[0];
    public int[] degree = new int[0];
    public int[] kind = new int[0];
    /** Note id, tag name, or unresolved link key depending on {@link #kind}. */
    public String[] key = new String[0];
    public String[] label = new String[0];

    public int edgeCount;
    public int[] edgeFrom = new int[0];
    public int[] edgeTo = new int[0];

    private final Map<String, Integer> indexByKey = new HashMap<>();

    public int indexOf(String nodeKey) {
        Integer index = indexByKey.get(nodeKey);
        return index == null ? -1 : index;
    }

    public boolean isEmpty() { return nodeCount == 0; }

    /**
     * Builds the whole-vault graph.
     *
     * @param includeTags     add a node per tag, linking the notes that carry it
     * @param includeOrphans  keep notes with no links at all
     * @param includeMissing  add placeholder nodes for unresolved wiki links
     */
    public static GraphModel build(NoteIndex index, boolean includeTags,
                                   boolean includeOrphans, boolean includeMissing) {
        GraphModel model = new GraphModel();
        List<Note> notes = index.all();

        // First pass: which notes take part at all.
        Set<String> linked = new HashSet<>();
        for (Note note : notes) {
            boolean hasOutgoing = !note.getOutgoingLinks().isEmpty();
            boolean hasIncoming = index.backlinkCount(note) > 0;
            boolean hasTags = includeTags && !note.getTags().isEmpty();
            if (hasOutgoing || hasIncoming || hasTags) linked.add(note.getId());
        }

        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();

        for (Note note : notes) {
            if (!includeOrphans && !linked.contains(note.getId())) continue;
            model.indexByKey.put(note.getId(), keys.size());
            keys.add(note.getId());
            labels.add(note.getTitle() == null ? note.getId() : note.getTitle());
            kinds.add(KIND_NOTE);
        }

        List<int[]> edges = new ArrayList<>();

        for (Note note : notes) {
            int from = model.indexOf(note.getId());
            if (from < 0) continue;
            for (String target : note.getOutgoingLinks()) {
                Note resolved = index.resolveLink(target);
                int to;
                if (resolved != null) {
                    to = model.indexOf(resolved.getId());
                    if (to < 0) {
                        // Target exists but was filtered out; pull it back in so the
                        // edge is not silently dropped.
                        model.indexByKey.put(resolved.getId(), keys.size());
                        to = keys.size();
                        keys.add(resolved.getId());
                        labels.add(resolved.getTitle());
                        kinds.add(KIND_NOTE);
                    }
                } else if (includeMissing) {
                    String placeholder = "?" + target;
                    to = model.indexOf(placeholder);
                    if (to < 0) {
                        model.indexByKey.put(placeholder, keys.size());
                        to = keys.size();
                        keys.add(placeholder);
                        labels.add(target);
                        kinds.add(KIND_MISSING);
                    }
                } else {
                    continue;
                }
                if (from != to) edges.add(new int[]{from, to});
            }

            if (includeTags) {
                for (String tag : note.getTags()) {
                    String tagKey = "#" + tag;
                    int to = model.indexOf(tagKey);
                    if (to < 0) {
                        model.indexByKey.put(tagKey, keys.size());
                        to = keys.size();
                        keys.add(tagKey);
                        labels.add("#" + tag);
                        kinds.add(KIND_TAG);
                    }
                    edges.add(new int[]{from, to});
                }
            }
        }

        model.allocate(keys.size(), edges.size());
        for (int i = 0; i < keys.size(); i++) {
            model.key[i] = keys.get(i);
            model.label[i] = labels.get(i);
            model.kind[i] = kinds.get(i);
        }
        for (int i = 0; i < edges.size(); i++) {
            int[] edge = edges.get(i);
            model.edgeFrom[i] = edge[0];
            model.edgeTo[i] = edge[1];
            model.degree[edge[0]]++;
            model.degree[edge[1]]++;
        }
        model.seedPositions();
        return model;
    }

    /**
     * Builds the neighbourhood of one note out to {@code depth} hops — the
     * "local graph", which is the view that is actually useful on a phone once a
     * vault passes a few hundred notes.
     */
    public static GraphModel buildLocal(NoteIndex index, String focusNoteId,
                                        int depth, boolean includeTags) {
        Note focus = index.get(focusNoteId);
        if (focus == null) return new GraphModel();

        Set<String> included = new HashSet<>();
        ArrayDeque<String> frontier = new ArrayDeque<>();
        Map<String, Integer> depthOf = new HashMap<>();
        included.add(focusNoteId);
        depthOf.put(focusNoteId, 0);
        frontier.add(focusNoteId);

        while (!frontier.isEmpty()) {
            String currentId = frontier.poll();
            int currentDepth = depthOf.get(currentId);
            if (currentDepth >= depth) continue;
            Note current = index.get(currentId);
            if (current == null) continue;

            for (String target : current.getOutgoingLinks()) {
                Note resolved = index.resolveLink(target);
                if (resolved == null) continue;
                if (included.add(resolved.getId())) {
                    depthOf.put(resolved.getId(), currentDepth + 1);
                    frontier.add(resolved.getId());
                }
            }
            for (Note source : index.backlinksOf(current)) {
                if (included.add(source.getId())) {
                    depthOf.put(source.getId(), currentDepth + 1);
                    frontier.add(source.getId());
                }
            }
        }

        GraphModel model = new GraphModel();
        List<String> keys = new ArrayList<>(included);
        List<String> labels = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String id = keys.get(i);
            model.indexByKey.put(id, i);
            Note note = index.get(id);
            labels.add(note == null ? id : note.getTitle());
            kinds.add(KIND_NOTE);
        }

        List<int[]> edges = new ArrayList<>();
        for (String id : keys) {
            Note note = index.get(id);
            if (note == null) continue;
            int from = model.indexOf(id);
            for (String target : note.getOutgoingLinks()) {
                Note resolved = index.resolveLink(target);
                if (resolved == null) continue;
                int to = model.indexOf(resolved.getId());
                if (to >= 0 && to != from) edges.add(new int[]{from, to});
            }
            if (includeTags) {
                for (String tag : note.getTags()) {
                    String tagKey = "#" + tag;
                    int to = model.indexOf(tagKey);
                    if (to < 0) {
                        to = keys.size();
                        model.indexByKey.put(tagKey, to);
                        keys.add(tagKey);
                        labels.add("#" + tag);
                        kinds.add(KIND_TAG);
                    }
                    edges.add(new int[]{from, to});
                }
            }
        }

        model.allocate(keys.size(), edges.size());
        for (int i = 0; i < keys.size(); i++) {
            model.key[i] = keys.get(i);
            model.label[i] = labels.get(i);
            model.kind[i] = kinds.get(i);
        }
        for (int i = 0; i < edges.size(); i++) {
            int[] edge = edges.get(i);
            model.edgeFrom[i] = edge[0];
            model.edgeTo[i] = edge[1];
            model.degree[edge[0]]++;
            model.degree[edge[1]]++;
        }
        model.seedPositions();
        return model;
    }

    private void allocate(int nodes, int edges) {
        nodeCount = nodes;
        edgeCount = edges;
        x = new float[nodes];
        y = new float[nodes];
        vx = new float[nodes];
        vy = new float[nodes];
        degree = new int[nodes];
        kind = new int[nodes];
        key = new String[nodes];
        label = new String[nodes];
        edgeFrom = new int[edges];
        edgeTo = new int[edges];
    }

    /**
     * Seeds positions on a phyllotactic spiral. A deterministic, evenly spread
     * start converges faster than random placement and — because it is
     * deterministic — reopening the graph does not reshuffle a layout the user
     * had already learned.
     */
    private void seedPositions() {
        float golden = 2.399963f;
        float spacing = 26f;
        for (int i = 0; i < nodeCount; i++) {
            float radius = spacing * (float) Math.sqrt(i + 1);
            float angle = golden * i;
            x[i] = radius * (float) Math.cos(angle);
            y[i] = radius * (float) Math.sin(angle);
        }
    }

    /** Node indices adjacent to {@code node}. */
    public int[] neighbours(int node) {
        int count = 0;
        for (int i = 0; i < edgeCount; i++) {
            if (edgeFrom[i] == node || edgeTo[i] == node) count++;
        }
        int[] out = new int[count];
        int at = 0;
        for (int i = 0; i < edgeCount; i++) {
            if (edgeFrom[i] == node) out[at++] = edgeTo[i];
            else if (edgeTo[i] == node) out[at++] = edgeFrom[i];
        }
        return out;
    }
}
