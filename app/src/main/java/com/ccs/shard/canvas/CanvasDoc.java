package com.ccs.shard.canvas;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An infinite canvas of cards and connections.
 *
 * <p>Serialised as JSON in the <a href="https://jsoncanvas.org">JSON Canvas</a>
 * shape that Obsidian uses for its {@code .canvas} files, so a canvas made here
 * opens there and vice versa. Unknown root, node and edge keys are preserved so
 * a file round-trips without losing another tool's data.
 *
 * <p>The previous canvas kept everything in memory only: nodes vanished the
 * moment the screen closed. Persisting to a real file is what turns it from a
 * demo into a feature.
 */
public final class CanvasDoc {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_FILE = "file";
    public static final String TYPE_GROUP = "group";

    /** A freehand world-space stroke, stored in a Shard extension of JSON Canvas. */
    public static final class Stroke {
        public String id;
        public String color = "";
        /** Stroke width in world dp. */
        public float width = 2.5f;
        /** Consecutive x/y world-coordinate pairs. */
        public final List<Float> points = new ArrayList<>();

        public void addPoint(float x, float y) {
            if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)) {
                return;
            }
            points.add(x);
            points.add(y);
        }

        public int pointCount() { return points.size() / 2; }
    }

    /** One card. */
    public static final class Node {
        public String id;
        public String type = TYPE_TEXT;
        public float x;
        public float y;
        public float width = 260f;
        public float height = 120f;
        /** Body for a text card. */
        public String text = "";
        /** Vault-relative note path for a file card. */
        public String file = "";
        /** Visible title for a group node. */
        public String label = "";
        /** Optional vault-relative note id attached to a text card. */
        public String note = "";
        /** Obsidian colour index ("1".."6") or a hex value; empty means default. */
        public String color = "";
        /** Keys this app does not understand, kept so the file round-trips. */
        JSONObject extras;

        public boolean isFile() { return TYPE_FILE.equals(type); }

        public boolean isGroup() { return TYPE_GROUP.equals(type); }

        public float right() { return x + width; }

        public float bottom() { return y + height; }

        public float centreX() { return x + width / 2f; }

        public float centreY() { return y + height / 2f; }

        public boolean contains(float worldX, float worldY) {
            return worldX >= x && worldX <= right() && worldY >= y && worldY <= bottom();
        }
    }

    /** A connection between two cards. */
    public static final class Edge {
        public String id;
        public String fromNode;
        public String toNode;
        public String fromSide = "right";
        public String toSide = "left";
        public String label = "";
        public String color = "";
        JSONObject extras;
    }

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<Stroke> strokes = new ArrayList<>();
    /** Lazily rebuilt O(1) lookup used for every edge on every canvas frame. */
    private final Map<String, Node> nodesById = new HashMap<>();
    private boolean nodeIndexDirty = true;
    private JSONObject rootExtras;
    private long idCounter = 1;

    public List<Node> nodes() { return nodes; }

    public List<Edge> edges() { return edges; }

    public List<Stroke> strokes() { return strokes; }

    public boolean isEmpty() { return nodes.isEmpty() && strokes.isEmpty(); }

    public Node nodeById(String id) {
        if (nodeIndexDirty) {
            nodesById.clear();
            for (Node node : nodes) if (node.id != null) nodesById.put(node.id, node);
            nodeIndexDirty = false;
        }
        return nodesById.get(id);
    }

    /** Deterministic ids keep the file stable across saves. */
    private String nextId(String prefix) {
        String id;
        do {
            id = prefix + Long.toString(idCounter++, 36);
        } while (hasId(id));
        return id;
    }

    private boolean hasId(String id) {
        for (Node node : nodes) if (same(node.id, id)) return true;
        for (Edge edge : edges) if (same(edge.id, id)) return true;
        for (Stroke stroke : strokes) if (same(stroke.id, id)) return true;
        return false;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public Node addTextNode(String text, float x, float y) {
        Node node = new Node();
        node.id = nextId("t");
        node.type = TYPE_TEXT;
        node.text = text == null ? "" : text;
        node.x = x;
        node.y = y;
        nodes.add(node);
        nodeIndexDirty = true;
        return node;
    }

    public Node addFileNode(String notePath, float x, float y) {
        Node node = new Node();
        node.id = nextId("f");
        node.type = TYPE_FILE;
        node.file = notePath == null ? "" : notePath;
        node.x = x;
        node.y = y;
        node.height = 96f;
        nodes.add(node);
        nodeIndexDirty = true;
        return node;
    }

    public Node addGroupNode(String label, float x, float y) {
        Node node = new Node();
        node.id = nextId("g");
        node.type = TYPE_GROUP;
        node.label = label == null ? "" : label;
        node.x = x;
        node.y = y;
        node.width = 520f;
        node.height = 320f;
        // Groups live behind cards in file order as well as in Shard's renderer.
        nodes.add(0, node);
        nodeIndexDirty = true;
        return node;
    }

    /** Starts a freehand stroke. Points are added by the view while the finger moves. */
    public Stroke addStroke(String color, float width, float x, float y) {
        Stroke stroke = new Stroke();
        stroke.id = nextId("d");
        stroke.color = color == null ? "" : color;
        stroke.width = Math.max(0.5f, Math.min(24f, width));
        stroke.addPoint(x, y);
        strokes.add(stroke);
        return stroke;
    }

    public void removeStroke(Stroke stroke) { strokes.remove(stroke); }

    public void remove(Node node) {
        if (node == null) return;
        nodes.remove(node);
        nodeIndexDirty = true;
        for (int i = edges.size() - 1; i >= 0; i--) {
            Edge edge = edges.get(i);
            if (same(edge.fromNode, node.id) || same(edge.toNode, node.id)) edges.remove(i);
        }
    }

    /** Clears every saved canvas element and invalidates the fast node lookup. */
    public void clear() {
        nodes.clear();
        edges.clear();
        strokes.clear();
        nodesById.clear();
        nodeIndexDirty = true;
    }

    /** Connects two cards, or removes the connection if it already exists. */
    public boolean toggleEdge(Node from, Node to) {
        if (from == null || to == null || from == to) return false;
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i);
            boolean forward = same(edge.fromNode, from.id) && same(edge.toNode, to.id);
            boolean reverse = same(edge.fromNode, to.id) && same(edge.toNode, from.id);
            if (forward || reverse) {
                edges.remove(i);
                return false;
            }
        }
        addEdge(from, to);
        return true;
    }

    /** Adds a directed arrow using the sides nearest to the other card. */
    public Edge addEdge(Node from, Node to) {
        if (from == null || to == null || from == to) return null;
        Edge edge = new Edge();
        edge.id = nextId("e");
        edge.fromNode = from.id;
        edge.toNode = to.id;
        updateEdgeSides(edge, from, to);
        edges.add(edge);
        return edge;
    }

    /** Keeps arrow endpoints attached to the closest sides while cards move. */
    public void refreshEdgeSidesFor(Node node) {
        if (node == null) return;
        for (Edge edge : edges) {
            if (!same(edge.fromNode, node.id) && !same(edge.toNode, node.id)) continue;
            Node from = nodeById(edge.fromNode);
            Node to = nodeById(edge.toNode);
            if (from != null && to != null) updateEdgeSides(edge, from, to);
        }
    }

    private static void updateEdgeSides(Edge edge, Node from, Node to) {
        float dx = to.centreX() - from.centreX();
        float dy = to.centreY() - from.centreY();
        if (Math.abs(dx) >= Math.abs(dy)) {
            edge.fromSide = dx >= 0 ? "right" : "left";
            edge.toSide = dx >= 0 ? "left" : "right";
        } else {
            edge.fromSide = dy >= 0 ? "bottom" : "top";
            edge.toSide = dy >= 0 ? "top" : "bottom";
        }
    }

    public List<Edge> edgesOf(Node node) {
        List<Edge> out = new ArrayList<>();
        if (node == null) return out;
        for (Edge edge : edges) {
            if (same(edge.fromNode, node.id) || same(edge.toNode, node.id)) out.add(edge);
        }
        return out;
    }

    /** Moves a node to the end so it draws on top. */
    public void bringToFront(Node node) {
        if (nodes.remove(node)) nodes.add(node);
    }

    // ---------------------------------------------------------------- JSON

    public static CanvasDoc parse(String json) {
        CanvasDoc doc = new CanvasDoc();
        if (json == null || json.trim().isEmpty()) return doc;
        try {
            JSONObject root = new JSONObject(json);
            doc.rootExtras = extrasOf(root, "nodes", "edges", "shardDrawings");
            JSONArray nodeArray = root.optJSONArray("nodes");
            if (nodeArray != null) {
                for (int i = 0; i < nodeArray.length(); i++) {
                    JSONObject object = nodeArray.optJSONObject(i);
                    if (object == null) continue;
                    Node node = new Node();
                    node.id = object.optString("id", "n" + i);
                    node.type = object.optString("type", TYPE_TEXT);
                    node.x = (float) object.optDouble("x", 0);
                    node.y = (float) object.optDouble("y", 0);
                    node.width = Math.max(24f, (float) object.optDouble("width", 260));
                    node.height = Math.max(24f, (float) object.optDouble("height", 120));
                    node.text = object.optString("text", "");
                    node.file = object.optString("file", "");
                    node.label = object.optString("label", "");
                    node.note = object.optString("shardNote", "");
                    node.color = object.optString("color", "");
                    node.extras = extrasOf(object, "id", "type", "x", "y", "width",
                            "height", "text", "file", "label", "shardNote", "color");
                    doc.nodes.add(node);
                    doc.nodeIndexDirty = true;
                }
            }
            JSONArray edgeArray = root.optJSONArray("edges");
            if (edgeArray != null) {
                for (int i = 0; i < edgeArray.length(); i++) {
                    JSONObject object = edgeArray.optJSONObject(i);
                    if (object == null) continue;
                    Edge edge = new Edge();
                    edge.id = object.optString("id", "e" + i);
                    edge.fromNode = object.optString("fromNode", "");
                    edge.toNode = object.optString("toNode", "");
                    edge.fromSide = object.optString("fromSide", "right");
                    edge.toSide = object.optString("toSide", "left");
                    edge.label = object.optString("label", "");
                    edge.color = object.optString("color", "");
                    edge.extras = extrasOf(object, "id", "fromNode", "toNode",
                            "fromSide", "toSide", "label", "color");
                    if (!edge.fromNode.isEmpty() && !edge.toNode.isEmpty()) {
                        doc.edges.add(edge);
                    }
                }
            }
            JSONArray drawings = root.optJSONArray("shardDrawings");
            if (drawings != null) {
                for (int i = 0; i < drawings.length(); i++) {
                    JSONObject object = drawings.optJSONObject(i);
                    if (object == null) continue;
                    JSONArray points = object.optJSONArray("points");
                    if (points == null || points.length() < 2) continue;
                    Stroke stroke = new Stroke();
                    stroke.id = object.optString("id", "d" + i);
                    stroke.color = object.optString("color", "");
                    stroke.width = Math.max(0.5f, (float) object.optDouble("width", 2.5));
                    for (int p = 0; p + 1 < points.length(); p += 2) {
                        stroke.addPoint((float) points.optDouble(p),
                                (float) points.optDouble(p + 1));
                    }
                    if (stroke.pointCount() > 0) doc.strokes.add(stroke);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("ShardCanvas", "cannot parse canvas", t);
        }
        // nextId also checks existing ids. Starting after the object count keeps
        // normal files compact while still handling arbitrary ids from other apps.
        doc.idCounter = doc.nodes.size() + doc.edges.size() + doc.strokes.size() + 1L;
        return doc;
    }

    private static JSONObject extrasOf(JSONObject source, String... known) {
        JSONObject extras = null;
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            boolean isKnown = false;
            for (String candidate : known) {
                if (candidate.equals(key)) {
                    isKnown = true;
                    break;
                }
            }
            if (isKnown) continue;
            if (extras == null) extras = new JSONObject();
            try {
                extras.put(key, source.get(key));
            } catch (Throwable ignored) { }
        }
        return extras;
    }

    private static void putExtras(JSONObject into, JSONObject extras) throws Exception {
        if (extras == null) return;
        java.util.Iterator<String> keys = extras.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            into.put(key, extras.get(key));
        }
    }

    public String toJson() {
        try {
            JSONArray nodeArray = new JSONArray();
            for (Node node : nodes) {
                JSONObject object = new JSONObject();
                object.put("id", node.id);
                object.put("type", node.type);
                object.put("x", Math.round(node.x));
                object.put("y", Math.round(node.y));
                object.put("width", Math.round(node.width));
                object.put("height", Math.round(node.height));
                if (node.isFile()) object.put("file", node.file);
                else if (node.isGroup()) object.put("label", node.label);
                else object.put("text", node.text);
                if (node.note != null && !node.note.isEmpty()) object.put("shardNote", node.note);
                if (node.color != null && !node.color.isEmpty()) object.put("color", node.color);
                putExtras(object, node.extras);
                nodeArray.put(object);
            }
            JSONArray edgeArray = new JSONArray();
            for (Edge edge : edges) {
                JSONObject object = new JSONObject();
                object.put("id", edge.id);
                object.put("fromNode", edge.fromNode);
                object.put("fromSide", edge.fromSide);
                object.put("toNode", edge.toNode);
                object.put("toSide", edge.toSide);
                if (edge.label != null && !edge.label.isEmpty()) object.put("label", edge.label);
                if (edge.color != null && !edge.color.isEmpty()) object.put("color", edge.color);
                putExtras(object, edge.extras);
                edgeArray.put(object);
            }
            JSONObject root = new JSONObject();
            putExtras(root, rootExtras);
            root.put("nodes", nodeArray);
            root.put("edges", edgeArray);
            if (!strokes.isEmpty()) {
                JSONArray drawings = new JSONArray();
                for (Stroke stroke : strokes) {
                    if (stroke == null || stroke.pointCount() == 0) continue;
                    JSONObject object = new JSONObject();
                    object.put("id", stroke.id);
                    if (stroke.color != null && !stroke.color.isEmpty()) {
                        object.put("color", stroke.color);
                    }
                    object.put("width", stroke.width);
                    JSONArray points = new JSONArray();
                    for (Float point : stroke.points) points.put(point);
                    object.put("points", points);
                    drawings.put(object);
                }
                if (drawings.length() > 0) root.put("shardDrawings", drawings);
            }
            return root.toString(1);
        } catch (Throwable t) {
            android.util.Log.e("ShardCanvas", "cannot serialise canvas", t);
            return "{\"nodes\":[],\"edges\":[]}";
        }
    }

    /** Bounding box of every node, or null when the canvas is empty. */
    public float[] bounds() {
        if (nodes.isEmpty() && strokes.isEmpty()) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Node node : nodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.right());
            maxY = Math.max(maxY, node.bottom());
        }
        for (Stroke stroke : strokes) {
            if (stroke == null) continue;
            for (int i = 0; i + 1 < stroke.points.size(); i += 2) {
                float x = stroke.points.get(i);
                float y = stroke.points.get(i + 1);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return new float[]{minX, minY, maxX, maxY};
    }
}
