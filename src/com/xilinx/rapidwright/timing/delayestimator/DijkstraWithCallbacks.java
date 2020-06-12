package com.xilinx.rapidwright.timing.delayestimator;

// I would extend DijkstraShortestPath or BaseShortestPathAlgorithm, but it is final or package-private.

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.graph.GraphWalk;

import java.util.Objects;


/**
 * Representing possible connections between two timing groups.
 * The shortest path delay represents the minimum delay between the two groups.
 * However, the built-in Dijkstra algorithm can't compute this because edge weight
 * depends on the accumulative distance at its preceding node.
 * During traversal, the delay of an edge must be updated before expansion!
 *
 * DijkstraWithCallbacks is used in placed of DijkstraShortestPath
 * DijkstraClosestFirstIterator in this package is used instead of the one from jgrapht.
 */
public class DijkstraWithCallbacks<V, E> implements ShortestPathAlgorithm<V, E> {
    public static interface ExamineEdge<V,E> {
        void apply(Graph<V,E> g, V v, E e, Double val);
    }
    public static interface DiscoverVertex<V, E> {
        double apply(Graph<V,E> g, V v, E e, Double d);
    }
    protected ExamineEdge<V,E> examineEdge;
    protected DiscoverVertex<V,E> discoverVertex;
    protected Double dAtSource;

    protected final Graph<V, E> graph;
    private final static double radius = Double.POSITIVE_INFINITY; // unlimited depth

    // same as that in DijkstraShortestPath
    @Override
    public GraphPath<V, E> getPath(V source, V sink) {
        if (!this.graph.containsVertex(source)) {
            throw new IllegalArgumentException("Graph must contain the source vertex!");
        } else if (!this.graph.containsVertex(sink)) {
            throw new IllegalArgumentException("Graph must contain the sink vertex!");
        } else if (source.equals(sink)) {
            return this.createEmptyPath(source, sink);
        } else {
            DijkstraClosestFirstIterator it = new DijkstraClosestFirstIterator(this.graph, source, this.radius,
                    dAtSource, examineEdge, discoverVertex);

            while(it.hasNext()) {
                V vertex = (V) it.next();
                if (vertex.equals(sink)) {
                    break;
                }
            }

            return it.getPaths().getPath(sink);
        }
    }

    // same as that in BaseShortestPathAlgorithm<V, E>
    @Override
    public double getPathWeight(V source, V sink) {
        GraphPath<V, E> p = this.getPath(source, sink);
        return p == null ? 1.0D / 0.0 : p.getWeight();
    }

    // same as that in DijkstraShortestPath, which override one in BaseShortestPathAlgorithm
    @Override
    public SingleSourcePaths<V, E> getPaths(V source) {
        if (!this.graph.containsVertex(source)) {
            throw new IllegalArgumentException("Graph must contain the source vertex!");
        } else {
            DijkstraClosestFirstIterator it = new DijkstraClosestFirstIterator(this.graph, source, this.radius,
                    dAtSource, examineEdge, discoverVertex);

            while(it.hasNext()) {
                it.next();
            }

            return it.getPaths();
        }
    }

    // same as that in BaseShortestPathAlgorithm
    protected final GraphPath<V, E> createEmptyPath(V source, V sink) {
        return source.equals(sink) ? GraphWalk.singletonWalk(this.graph, source, 0.0D) : null;
    }

    public static <V, E> GraphPath<V, E> findPathBetween(Graph<V, E> graph, V source, V sink, Double dAtSource,
                                         ExamineEdge<V,E> examineEdge, DiscoverVertex<V,E> discoverVertex) {
        return (new DijkstraWithCallbacks(graph, dAtSource, examineEdge, discoverVertex)).getPath(source, sink);
    }
    public static <V,E> Double findMinWeightBetween(Graph<V, E> graph, V source, V sink, Double dAtSource,
                               ExamineEdge<V,E> examineEdge, DiscoverVertex<V,E> discoverVertex) {
        return (new DijkstraWithCallbacks(graph, dAtSource, examineEdge, discoverVertex)).getPathWeight(source, sink);
    }
    public DijkstraWithCallbacks(Graph<V, E> graph, Double dAtSource,
                                 ExamineEdge<V,E> examineEdge, DiscoverVertex<V,E> discoverVertex) {
        this.graph = (Graph) Objects.requireNonNull(graph, "Graph is null");
        this.examineEdge = examineEdge;
        this.discoverVertex = discoverVertex;
        this.dAtSource = dAtSource;
    }
}
