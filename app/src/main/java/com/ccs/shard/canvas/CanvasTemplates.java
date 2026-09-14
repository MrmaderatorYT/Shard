package com.ccs.shard.canvas;

/** Small, editable starting boards. They use only ordinary JSON Canvas cards and arrows. */
public final class CanvasTemplates {

    public static final String BLANK = "blank";
    public static final String KANBAN = "kanban";
    public static final String PROJECT = "project";
    public static final String MIND_MAP = "mind_map";

    private CanvasTemplates() { }

    public static CanvasDoc create(String key) {
        CanvasDoc doc = new CanvasDoc();
        if (KANBAN.equals(key)) return kanban(doc);
        if (PROJECT.equals(key)) return project(doc);
        if (MIND_MAP.equals(key)) return mindMap(doc);
        return doc;
    }

    private static CanvasDoc kanban(CanvasDoc doc) {
        CanvasDoc.Node backlog = group(doc, "Backlog", -480, -190, 360, 560, "5");
        CanvasDoc.Node progress = group(doc, "In progress", -80, -190, 360, 560, "3");
        CanvasDoc.Node done = group(doc, "Done", 320, -190, 360, 560, "4");
        card(doc, "Capture work here", -430, -120, "5");
        card(doc, "Choose the next task", -30, -120, "3");
        card(doc, "Keep completed work visible", 370, -120, "4");
        // Group nodes are intentionally unused by edges: they are visual containers.
        return doc;
    }

    private static CanvasDoc project(CanvasDoc doc) {
        CanvasDoc.Node goal = card(doc, "Project goal", -130, -230, "6");
        CanvasDoc.Node research = card(doc, "Research", -430, 40, "5");
        CanvasDoc.Node build = card(doc, "Build", -130, 40, "3");
        CanvasDoc.Node review = card(doc, "Review", 170, 40, "2");
        CanvasDoc.Node release = card(doc, "Release", 470, 40, "4");
        doc.addEdge(goal, research);
        doc.addEdge(goal, build);
        doc.addEdge(build, review);
        doc.addEdge(review, release);
        return doc;
    }

    private static CanvasDoc mindMap(CanvasDoc doc) {
        CanvasDoc.Node centre = card(doc, "Central idea", -130, -60, "6");
        CanvasDoc.Node why = card(doc, "Why?", -480, -220, "1");
        CanvasDoc.Node how = card(doc, "How?", 220, -220, "3");
        CanvasDoc.Node actions = card(doc, "Next actions", 220, 130, "4");
        CanvasDoc.Node notes = card(doc, "Notes", -480, 130, "5");
        doc.addEdge(centre, why);
        doc.addEdge(centre, how);
        doc.addEdge(centre, actions);
        doc.addEdge(centre, notes);
        return doc;
    }

    private static CanvasDoc.Node group(CanvasDoc doc, String label, float x, float y,
                                        float width, float height, String color) {
        CanvasDoc.Node node = doc.addGroupNode(label, x, y);
        node.width = width;
        node.height = height;
        node.color = color;
        return node;
    }

    private static CanvasDoc.Node card(CanvasDoc doc, String text, float x, float y,
                                       String color) {
        CanvasDoc.Node node = doc.addTextNode(text, x, y);
        node.width = 260f;
        node.height = 120f;
        node.color = color;
        return node;
    }
}
