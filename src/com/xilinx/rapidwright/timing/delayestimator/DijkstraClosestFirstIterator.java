package com.xilinx.rapidwright.timing.delayestimator;

// from package org.jgrapht.alg.shortestpath;
// add callback


import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.TreeSingleSourcePathsImpl;
import org.jgrapht.alg.util.Pair;
import org.jgrapht.util.FibonacciHeap;
import org.jgrapht.util.FibonacciHeapNode;

class DijkstraClosestFirstIterator<V, E> implements Iterator<V> {

    // Called when examine an edge (from a settle vertex)
    private DijkstraWithCallbacks.ExamineEdge<V,E> examineEdge;
    // Called when examine an edge lead to a discovered vertex
    private DijkstraWithCallbacks.UpdateVertex<V,E> updateVertex;
    // Called when examine an edge lead to a undiscovered vertex
    private DijkstraWithCallbacks.DiscoverVertex<V,E> discoverVertex;

    private final Graph<V, E> graph;
    private final V source;
    private final double radius;
    private final FibonacciHeap<DijkstraClosestFirstIterator<V, E>.QueueEntry> heap;
    private final Map<V, FibonacciHeapNode<DijkstraClosestFirstIterator<V, E>.QueueEntry>> seen;

    public DijkstraClosestFirstIterator(Graph<V, E> graph, V source, short x, short y,
                                        DijkstraWithCallbacks.ExamineEdge<V,E> examineEdge,
                                        DijkstraWithCallbacks.DiscoverVertex<V,E> discoverVertex,
                                        DijkstraWithCallbacks.UpdateVertex<V,E> updateVertex) {
            this(graph, source, 1.0D / 0.0, x, y, examineEdge, discoverVertex, updateVertex);
    }

    public DijkstraClosestFirstIterator(Graph<V, E> graph, V source, double radius, short srcX, short srcY,
                                        DijkstraWithCallbacks.ExamineEdge<V,E> examineEdge,
                                        DijkstraWithCallbacks.DiscoverVertex<V,E> discoverVertex,
                                        DijkstraWithCallbacks.UpdateVertex<V,E> updateVertex) {
        this.graph = (Graph) Objects.requireNonNull(graph, "Graph cannot be null");
        this.source = Objects.requireNonNull(source, "Sourve vertex cannot be null");
        this.examineEdge = examineEdge;
        this.discoverVertex = discoverVertex;
        this.updateVertex = updateVertex;
        if (radius < 0.0D) {
            throw new IllegalArgumentException("Radius must be non-negative");
        } else {
            this.radius = radius;
            this.heap = new FibonacciHeap();
            this.seen = new HashMap();

            FibonacciHeapNode<DijkstraClosestFirstIterator<V, E>.QueueEntry> node =
                    new FibonacciHeapNode(new DijkstraClosestFirstIterator.QueueEntry((E)null, source, srcX, srcY));
            this.heap.insert(node, 0.0D);
            this.seen.put(source, node);

            this.updateDistance(source, (E)null, 0.0D);
        }
    }


    public boolean hasNext() {
        if (this.heap.isEmpty()) {
            return false;
        } else {
            FibonacciHeapNode<DijkstraClosestFirstIterator<V, E>.QueueEntry> vNode = this.heap.min();
            double vDistance = vNode.getKey();
            if (this.radius < vDistance) {
                this.heap.clear();
                return false;
            } else {
                return true;
            }
        }
    }

    public V next() {
        if (!this.hasNext()) {
            throw new NoSuchElementException();
        } else {
            FibonacciHeapNode<DijkstraClosestFirstIterator<V, E>.QueueEntry> vNode = this.heap.removeMin();
            V v = (V) ((DijkstraClosestFirstIterator.QueueEntry)vNode.getData()).v;
            double vDistance = vNode.getKey();
            short x = vNode.getData().x;
            short y = vNode.getData().y;
            Iterator var5 = this.graph.outgoingEdgesOf(v).iterator();

            while(var5.hasNext()) {
                E e = (E) var5.next();
                V u = Graphs.getOppositeVertex(this.graph, e, v);
                examineEdge.apply(this.graph, u, e, x, y, vDistance);
                double eWeight = this.graph.getEdgeWeight(e);
                if (eWeight < 0.0D) {
                    throw new IllegalArgumentException("Negative edge weight not allowed");
                }

                this.updateDistance(u, e, vDistance + eWeight);
            }

            return v;
        }
    }

    public ShortestPathAlgorithm.SingleSourcePaths<V, E> getPaths() {
        return new TreeSingleSourcePathsImpl(this.graph, this.source, this.getDistanceAndPredecessorMap());
    }

    public Map<V, Pair<Double, E>> getDistanceAndPredecessorMap() {
        Map<V, Pair<Double, E>> distanceAndPredecessorMap = new HashMap();
        Iterator var2 = this.seen.values().iterator();

        while(var2.hasNext()) {
            FibonacciHeapNode<DijkstraClosestFirstIterator<V, E>.QueueEntry> vNode = (FibonacciHeapNode)var2.next();
            double vDistance = vNode.getKey();
            if (this.radius >= vDistance) {
                V v = (V) ((DijkstraClosestFirstIterator.QueueEntry)vNode.getData()).v;
                distanceAndPredecessorMap.put(v, Pair.of(vDistance, (E)((DijkstraClosestFirstIterator.QueueEntry)vNode.getData()).e));
            }
        }

        return distanceAndPredecessorMap;
    }

    private void updateDistance(V v, E e, double distance) {
        FibonacciHeapNode<DijkstraClosestFirstIterator<V, E>.QueueEntry> node = (FibonacciHeapNode)this.seen.get(v);
        if (node == null) {
            V u = Graphs.getOppositeVertex(this.graph, e, v);
            FibonacciHeapNode<DijkstraClosestFirstIterator<V, E>.QueueEntry> prevNode = (FibonacciHeapNode)this.seen.get(u);
            short x = 0;
            short y = 0;
            if (discoverVertex != null) {
                short[] xy = discoverVertex.apply(this.graph, u, e, prevNode.getData().x, prevNode.getData().y, distance);
                x = xy[0];
                y = xy[1];
            }
            node = new FibonacciHeapNode(new DijkstraClosestFirstIterator.QueueEntry(e, v, x, y));
            this.heap.insert(node, distance);
            this.seen.put(v, node);
        } else if (distance < node.getKey()) {
            if (updateVertex != null) {
                updateVertex.apply(this.graph, v, e, distance);
            }
            this.heap.decreaseKey(node, distance);
            ((DijkstraClosestFirstIterator.QueueEntry)node.getData()).e = e;
        }
    }

    class QueueEntry {
        E e;
        V v;
        short x;
        short y;

        public QueueEntry(E e, V v, short x, short y) {
            this.e = e;
            this.v = v;
            this.x = x;
            this.y = y;
        }
    }
}