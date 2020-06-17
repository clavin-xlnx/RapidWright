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
    private DijkstraWithCallbacks.DiscoverVertex<V,E> discoverVertex;
    private DijkstraWithCallbacks.ExamineEdge<V,E> examineEdge;
    private final Graph<V, E> graph;
    private final V source;
    private final double radius;
    private final FibonacciHeap<DijkstraClosestFirstIterator<V, E>.QueueEntry> heap;
    private final Map<V, FibonacciHeapNode<DijkstraClosestFirstIterator<V, E>.QueueEntry>> seen;

    public DijkstraClosestFirstIterator(Graph<V, E> graph, V source, double dAtSource,
                                        DijkstraWithCallbacks.ExamineEdge<V,E> examineEdge, DijkstraWithCallbacks.DiscoverVertex<V,E> discoverVertex) {
        this(graph, source, 1.0D / 0.0, dAtSource, examineEdge, discoverVertex);
    }

    public DijkstraClosestFirstIterator(Graph<V, E> graph, V source, double radius, double dAtSource,
                                        DijkstraWithCallbacks.ExamineEdge<V,E> examineEdge, DijkstraWithCallbacks.DiscoverVertex<V,E> discoverVertex) {
        this.graph = (Graph) Objects.requireNonNull(graph, "Graph cannot be null");
        this.source = Objects.requireNonNull(source, "Sourve vertex cannot be null");
        this.examineEdge = examineEdge;
        this.discoverVertex = discoverVertex;
        if (radius < 0.0D) {
            throw new IllegalArgumentException("Radius must be non-negative");
        } else {
            this.radius = radius;
            this.heap = new FibonacciHeap();
            this.seen = new HashMap();

            FibonacciHeapNode<DijkstraClosestFirstIterator<V, E>.QueueEntry> node =
                    new FibonacciHeapNode(new DijkstraClosestFirstIterator.QueueEntry((E)null, source, dAtSource));
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
            double d = vNode.getData().d;
            Iterator var5 = this.graph.outgoingEdgesOf(v).iterator();

            while(var5.hasNext()) {
                E e = (E) var5.next();
                V u = Graphs.getOppositeVertex(this.graph, e, v);
                examineEdge.apply(this.graph, u, e, d);
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
            Double d = 0.0;
            if (discoverVertex != null) {
                d = discoverVertex.apply(this.graph, u, e, prevNode.getData().d);
            }
            node = new FibonacciHeapNode(new DijkstraClosestFirstIterator.QueueEntry(e, v, d));
            this.heap.insert(node, distance);
            this.seen.put(v, node);
        } else if (distance < node.getKey()) {
            this.heap.decreaseKey(node, distance);
            ((DijkstraClosestFirstIterator.QueueEntry)node.getData()).e = e;
        }
    }

    class QueueEntry {
        E e;
        V v;
        Double d;

        public QueueEntry(E e, V v, Double d) {
            this.e = e;
            this.v = v;
            this.d = d;
        }
    }
}