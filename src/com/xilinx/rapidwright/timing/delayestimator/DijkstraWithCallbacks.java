package com.xilinx.rapidwright.timing.delayestimator;

// I would extend DijkstraShortestPath or BaseShortestPathAlgorithm, but it is final or package-private.

import com.xilinx.rapidwright.util.Pair;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.graph.GraphWalk;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;


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
        void apply(Graph<V,E> g, V v, E e, short x, short y, double dist);
    }
    public static interface DiscoverVertex<V, E> {
        short[] apply(Graph<V,E> g, V v, E e, short x, short y, double dist);
    }
    public static interface UpdateVertex<V, E> {
        double apply(Graph<V,E> g, V v, E e, double dist);
    }
    protected ExamineEdge<V,E> examineEdge;
    protected DiscoverVertex<V,E> discoverVertex;
    protected UpdateVertex<V,E> updateVertex;
    protected Predicate<E> edgePredicate;
    protected short srcX;
    protected short srcY;

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
                    srcX, srcY, examineEdge, discoverVertex, updateVertex);

            while(it.hasNext()) {
                V vertex = (V) it.next();
                if (vertex.equals(sink)) {
                    break;
                }
            }

            return it.getPaths().getPath(sink);
        }
    }

    public Pair<Double,Boolean> getPathWeightWithPredicate(V source, V sink) {
        GraphPath<V, E> p = this.getPath(source, sink);
        double w = p == null ? 1.0D / 0.0 : p.getWeight();
        boolean predicateIsTrue = false;
        if(p != null){//TODO Yun changed for debugging
        	for (E e : p.getEdgeList()) {
                if (edgePredicate.test(e)) {
                    predicateIsTrue = true;
                    break;
                }
             }
        }
        
        return new Pair<>(w,predicateIsTrue);
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
                    srcX, srcY, examineEdge, discoverVertex, updateVertex);

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

    public static <V, E> GraphPath<V, E> findPathBetween(Graph<V, E> graph, V source, V sink, short srcX, short srcY,
                                         ExamineEdge<V,E> examineEdge, DiscoverVertex<V,E> discoverVertex,
                                         UpdateVertex<V,E> updateVertex) {
        return (new DijkstraWithCallbacks(graph, srcX, srcY, examineEdge, discoverVertex, updateVertex)).getPath(source, sink);
    }
    public static <V,E> Pair<Double,Boolean> findMinWeightBetween(Graph<V, E> graph, V source, V sink, short srcX, short srcY,
                                                    ExamineEdge<V,E> examineEdge, DiscoverVertex<V,E> discoverVertex,
                                                    UpdateVertex<V,E> updateVertex, Predicate<E> edgePredicate) {
        return (new DijkstraWithCallbacks(graph, srcX, srcY, examineEdge, discoverVertex, updateVertex,
                                          edgePredicate)).getPathWeightWithPredicate(source, sink);
    }
    public static <V,E> Double findMinWeightBetween(Graph<V, E> graph, V source, V sink, short srcX, short srcY,
                               ExamineEdge<V,E> examineEdge, DiscoverVertex<V,E> discoverVertex,
                               UpdateVertex<V,E> updateVertex) {
            return (new DijkstraWithCallbacks(graph, srcX, srcY, examineEdge, discoverVertex, updateVertex)).getPathWeight(source, sink);
    }
    public DijkstraWithCallbacks(Graph<V, E> graph, short srcX, short srcY,
                                 ExamineEdge<V,E> examineEdge, DiscoverVertex<V,E> discoverVertex, UpdateVertex<V,E> updateVertex) {
        this.graph = (Graph) Objects.requireNonNull(graph, "Graph is null");
        this.examineEdge    = examineEdge;
        this.discoverVertex = discoverVertex;
        this.srcX           = srcX;
        this.srcY           = srcY;
        this.edgePredicate  = null;
    }
    public DijkstraWithCallbacks(Graph<V, E> graph, short srcX, short srcY,
                                 ExamineEdge<V,E> examineEdge, DiscoverVertex<V,E> discoverVertex,
                                 UpdateVertex<V,E> updateVertex, Predicate<E> edgePredicate) {
        this.graph = (Graph) Objects.requireNonNull(graph, "Graph is null");
        this.examineEdge    = examineEdge;
        this.discoverVertex = discoverVertex;
        this.srcX           = srcX;
        this.srcY           = srcY;
        this.edgePredicate  = edgePredicate;
    }
}
