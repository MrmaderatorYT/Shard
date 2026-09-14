package com.ccs.shard.canvas;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CanvasDocTest {

    @Test
    public void drawingAndTextNoteLink_areKeptInTheCanvasModel() {
        CanvasDoc document = new CanvasDoc();
        CanvasDoc.Node card = document.addTextNode("Plan", 20, 30);
        card.note = "Projects/Plan.md";
        CanvasDoc.Stroke stroke = document.addStroke("#3366CC", 3f, -10, 5);
        stroke.addPoint(90, 60);

        assertEquals(1, document.nodes().size());
        assertEquals("Projects/Plan.md", document.nodes().get(0).note);
        assertEquals(1, document.strokes().size());
        assertEquals(2, document.strokes().get(0).pointCount());
        assertNotNull(document.bounds());
    }

    @Test
    public void movingRelationship_automaticallyChoosesClosestArrowSides() {
        CanvasDoc document = new CanvasDoc();
        CanvasDoc.Node from = document.addTextNode("From", 0, 0);
        CanvasDoc.Node to = document.addTextNode("To", 400, 0);
        CanvasDoc.Edge edge = document.addEdge(from, to);
        assertEquals("right", edge.fromSide);
        assertEquals("left", edge.toSide);

        to.x = 0;
        to.y = 400;
        document.refreshEdgeSidesFor(to);
        assertEquals("bottom", edge.fromSide);
        assertEquals("top", edge.toSide);
    }

    @Test
    public void boardTemplates_createEditableCardsAndArrows() {
        CanvasDoc kanban = CanvasTemplates.create(CanvasTemplates.KANBAN);
        CanvasDoc project = CanvasTemplates.create(CanvasTemplates.PROJECT);

        assertFalse(kanban.isEmpty());
        assertTrue(kanban.nodes().size() >= 6);
        assertTrue(project.edges().size() >= 4);
        assertEquals(0, CanvasTemplates.create(CanvasTemplates.BLANK).nodes().size());
    }
}
