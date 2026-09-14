package com.ccs.shard.graph;

import com.ccs.shard.core.Io;

/**
 * Force-directed layout, run off the main thread.
 *
 * <p>The naive form of this algorithm compares every node with every other one,
 * which is 25 million comparisons per iteration for a 5000-note vault — hopeless
 * on a phone. Here repulsion is computed against a uniform spatial grid and
 * cut off past a radius, making each iteration linear in node count. A 2000-node
 * graph settles in well under a second on a mid-range device.
 *
 * <p>Positions are published to the view periodically while it runs, so the
 * graph visibly unfolds instead of appearing after a freeze.
 */
public final class ForceLayout {

    public interface Callback {
        /** Called on the main thread with a fresh layout, roughly 20 times a second. */
        void onLayoutUpdated(boolean finished);
    }

    private static final int MAX_ITERATIONS = 420;
    /**
     * Repulsion cutoff, in world units. Scaled from the ideal edge length so a
     * dense graph still separates: with a fixed cutoff, high-degree hubs ended up
     * inside each other because they were never far enough apart to feel a force.
     */
    private float cutoff = 190f;
    private float cell = 190f;

    private final GraphModel model;
    private final Callback callback;

    private volatile boolean cancelled;
    private Thread worker;

    /** Grid buckets: cellStart[c]..cellStart[c+1] index into cellItems. */
    private int[] cellStart;
    private int[] cellItems;
    private int columns;
    private int rows;
    private float minX;
    private float minY;

    public ForceLayout(GraphModel model, Callback callback) {
        this.model = model;
        this.callback = callback;
    }

    public void start() {
        if (model.nodeCount == 0) {
            Io.onMain(() -> callback.onLayoutUpdated(true));
            return;
        }
        cancelled = false;
        worker = new Thread(this::run, "shard-graph-layout");
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
    }

    public void cancel() {
        cancelled = true;
        Thread local = worker;
        if (local != null) local.interrupt();
    }

    private void run() {
        int n = model.nodeCount;
        // Ideal edge length: spread wider as the graph grows so labels stay legible.
        float k = (float) (56.0 * Math.sqrt(Math.max(1.0, 900.0 / Math.max(1, n))) + 34.0);
        cutoff = Math.max(190f, k * 3.2f);
        cell = cutoff;
        float temperature = 120f;
        float cooling = (float) Math.pow(0.02, 1.0 / MAX_ITERATIONS);
        long lastPublish = 0;

        for (int iteration = 0; iteration < MAX_ITERATIONS && !cancelled; iteration++) {
            java.util.Arrays.fill(model.vx, 0f);
            java.util.Arrays.fill(model.vy, 0f);

            buildGrid();
            applyRepulsion(k);
            applyAttraction(k);
            applyGravity();
            applyDisplacement(temperature);

            temperature *= cooling;

            // Publish on a wall-clock interval rather than every iteration: the
            // point is a visible unfolding, not a redraw per step.
            long now = System.nanoTime();
            if (now - lastPublish > 50_000_000L) {
                lastPublish = now;
                Io.onMain(() -> callback.onLayoutUpdated(false));
            }
        }
        if (!cancelled) Io.onMain(() -> callback.onLayoutUpdated(true));
    }

    /** Buckets nodes into a uniform grid so repulsion only looks at close pairs. */
    private void buildGrid() {
        int n = model.nodeCount;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        minX = Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (model.x[i] < minX) minX = model.x[i];
            if (model.x[i] > maxX) maxX = model.x[i];
            if (model.y[i] < minY) minY = model.y[i];
            if (model.y[i] > maxY) maxY = model.y[i];
        }
        columns = Math.max(1, (int) Math.ceil((maxX - minX) / cell) + 1);
        rows = Math.max(1, (int) Math.ceil((maxY - minY) / cell) + 1);
        // Cap the grid so a pathological spread cannot allocate a huge table.
        if ((long) columns * rows > 400_000L) {
            columns = Math.min(columns, 630);
            rows = Math.min(rows, 630);
        }

        int cells = columns * rows;
        if (cellStart == null || cellStart.length < cells + 1) {
            cellStart = new int[cells + 1];
        } else {
            java.util.Arrays.fill(cellStart, 0, cells + 1, 0);
        }
        if (cellItems == null || cellItems.length < n) cellItems = new int[n];

        for (int i = 0; i < n; i++) cellStart[cellOf(i) + 1]++;
        for (int c = 0; c < cells; c++) cellStart[c + 1] += cellStart[c];
        int[] cursor = new int[cells];
        for (int i = 0; i < n; i++) {
            int cell = cellOf(i);
            cellItems[cellStart[cell] + cursor[cell]] = i;
            cursor[cell]++;
        }
    }

    private int cellOf(int node) {
        int column = (int) ((model.x[node] - minX) / cell);
        int row = (int) ((model.y[node] - minY) / cell);
        if (column < 0) column = 0;
        if (column >= columns) column = columns - 1;
        if (row < 0) row = 0;
        if (row >= rows) row = rows - 1;
        return row * columns + column;
    }

    private void applyRepulsion(float k) {
        int n = model.nodeCount;
        float kSquared = k * k;
        for (int i = 0; i < n; i++) {
            int column = (int) ((model.x[i] - minX) / cell);
            int row = (int) ((model.y[i] - minY) / cell);
            for (int dr = -1; dr <= 1; dr++) {
                int r = row + dr;
                if (r < 0 || r >= rows) continue;
                for (int dc = -1; dc <= 1; dc++) {
                    int c = column + dc;
                    if (c < 0 || c >= columns) continue;
                    int cell = r * columns + c;
                    for (int slot = cellStart[cell]; slot < cellStart[cell + 1]; slot++) {
                        int j = cellItems[slot];
                        if (j <= i) continue;
                        float dx = model.x[i] - model.x[j];
                        float dy = model.y[i] - model.y[j];
                        float distanceSquared = dx * dx + dy * dy;
                        if (distanceSquared > cutoff * cutoff) continue;
                        if (distanceSquared < 0.01f) {
                            // Coincident nodes: nudge deterministically by index so
                            // the layout stays reproducible.
                            dx = ((i % 7) - 3) * 0.5f;
                            dy = ((j % 7) - 3) * 0.5f;
                            distanceSquared = dx * dx + dy * dy + 0.01f;
                        }
                        float distance = (float) Math.sqrt(distanceSquared);
                        // Weight by degree so hubs push each other apart instead of
                        // piling into one blob at the centre of a large vault.
                        float weight = 1f + 0.05f * (model.degree[i] + model.degree[j]);
                        float force = kSquared * weight / distance;
                        float fx = force * dx / distance;
                        float fy = force * dy / distance;
                        model.vx[i] += fx;
                        model.vy[i] += fy;
                        model.vx[j] -= fx;
                        model.vy[j] -= fy;
                    }
                }
            }
        }
    }

    private void applyAttraction(float k) {
        for (int e = 0; e < model.edgeCount; e++) {
            int a = model.edgeFrom[e];
            int b = model.edgeTo[e];
            float dx = model.x[b] - model.x[a];
            float dy = model.y[b] - model.y[a];
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance < 0.01f) continue;
            float force = distance * distance / k;
            // Hubs resist being dragged around by each of their many edges.
            float weightA = 1f / (1f + 0.06f * model.degree[a]);
            float weightB = 1f / (1f + 0.06f * model.degree[b]);
            float fx = force * dx / distance;
            float fy = force * dy / distance;
            model.vx[a] += fx * weightA;
            model.vy[a] += fy * weightA;
            model.vx[b] -= fx * weightB;
            model.vy[b] -= fy * weightB;
        }
    }

    /** Weak pull to the origin, which keeps disconnected clusters from drifting away. */
    private void applyGravity() {
        float strength = 0.035f;
        for (int i = 0; i < model.nodeCount; i++) {
            model.vx[i] -= model.x[i] * strength;
            model.vy[i] -= model.y[i] * strength;
        }
    }

    private void applyDisplacement(float temperature) {
        for (int i = 0; i < model.nodeCount; i++) {
            float dx = model.vx[i];
            float dy = model.vy[i];
            float magnitude = (float) Math.sqrt(dx * dx + dy * dy);
            if (magnitude < 0.0001f) continue;
            // Heavier nodes move less per step, which stops hubs from oscillating
            // and dragging their whole neighbourhood with them.
            float mass = 1f + 0.015f * model.degree[i];
            float limited = Math.min(magnitude, temperature) / mass;
            model.x[i] += dx / magnitude * limited;
            model.y[i] += dy / magnitude * limited;
        }
    }
}
