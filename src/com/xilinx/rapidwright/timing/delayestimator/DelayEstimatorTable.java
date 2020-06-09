/*
 *
 * Copyright (c) 2020 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.timing.delayestimator;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.timing.GroupDelayType;
import com.xilinx.rapidwright.timing.TimingEdge;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;
import org.jgrapht.graph.SimpleGraph;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;


/**
 *
 * Build table-based delay estimator. Tables are built for the given width and height.
 * A connection whose distance larger than the table will be broken up into 3 sections.
 * The begin and end section will use tables, while the middle will use consecutive long TGs.
 *
 * Use knowledge about the architecture to build these tables.
 * TODO: make it generic it might be slower or allow overriding for other architectures.
 *
 */
public class DelayEstimatorTable extends DelayEstimatorBase {

    private int width;
    private int height;
    private InterconnectInfo ictInfo;

    /**
     * To be delted.
     */
    private static class SimpleDelayGraphEntry {
        public Graph<Object, DefaultWeightedEdge> g;
        public Object src;
        public Object dst;
    }


    /**
     * To hold a graph representing all possible connections between src and dst.
     */
    private static class DelayGraphEntry {
        // edge need to refer to TG
        public Graph<Object, TimingGroupEdge> g;
        public Object src;
        public Object dst;
        public short  fixedDelay;
    }





    /**
     * Constructor from a device.
     * @param device Target device
     * @param ictInfo Interconnect information. TODO: should be selected automatically from device.
     * @param width Width of delay tables.
     * @param height Height of delay tables.
     */
    DelayEstimatorTable(Device device, InterconnectInfo ictInfo, int width, int height) {
        super(device);
        this.width  = width;
        this.height = height;
        this.ictInfo = ictInfo;
        buildTables();
    }

    DelayEstimatorTable(String partName, InterconnectInfo ictInfo, int width, int height) {
        this(Device.getDevice(partName), ictInfo, width, height);
    }




    // TODO: make this private
    /**
     *
     * @param from start timingGroup
     * @param to   end timingGroup
     * @param dir  horizontal or vertical
     * @param dist distance of end timing group from the start
     * @param detourDist extra distance allow to overshoot the target distance
     * @return DelayGraphEntry representing all possible connectios between from and to.
     *
     *
     * Implementation details:
     * All possible connections between the given from and to are represented as a graph.
     * A node has distance reference from the src, which has distance of 0.
     * A timing group is represented as an edge. Because possible TGS driven by a type of TGs can be different,
     * there can be multiple nodes at a particular distance. At the maximum, there are 4 nodes-- one for Single,
     * one for Double, one for Quad and one for Long. For UltraScale+ device, Single and Double can drive the same
     * set of TGs. However, we keep them separate because it is more flexible and simpler to implement, while the
     * runtime and memory overhead is very small. Furthermore, it is applicable to any device families.
     *
     * TODO: prunning the graph because
     * 1) Required. it might not be valid. Need to verify and eliminate from the graph. L,S,Q,L
     * 2) Optional. Dominated paths (paths always with higher delay then others.)
     * 3) Optional. Trim partial route not reachable to dst.
     * TODO: consider different representation with less number of redundancies.
     * There some edge redundencies in the current representation.
     * For example, S,D,Q ending at distance x are on 3 nodes, they can drive double to distance x+2.
     * There will be 3 edges connecting those 3 nodes to one node at distance x+2.
     * TODO: change Object to T
     */
    public DelayGraphEntry listPaths(TimingGroup from, TimingGroup to, Direction dir, int dist, int detourDist,
                                     boolean verbose, boolean plot) {

        class WaveEntry {
            public TimingGroup tg;
            public short       loc;
            public Object      n;
            public boolean     detour;
            WaveEntry(TimingGroup tg, short loc, Object n, boolean detour) {
                this.tg     = tg;
                this.loc    = loc;
                this.n      = n;
                this.detour = detour;
            }
            public String toString() {
                if (detour)
                  return tg.name() + " " + loc + "  detour";
                else
                  return tg.name() + " " + loc;
            }
        }

        int maxDist = dist + detourDist;

        Map<Short, Map<TimingGroup,Object>> distTypeNodemap = new HashMap();


        // BFS, not DFS, because getting all sinks in one call to nextTimingGroups.

        Graph<Object, TimingGroupEdge> g = new SimpleDirectedWeightedGraph<>(TimingGroupEdge.class);
        Object src = new Object();
        Object dst = null;
        g.addVertex(src);
        List<WaveEntry> wave = new ArrayList<>() {{add(new WaveEntry(from, (short) 0, src, false));}};

        int count = 0;

        while (!wave.isEmpty()) {
            List<WaveEntry> nxtWave = new ArrayList<>();
            for (WaveEntry frEntry : wave) {

                if (verbose)
                    System.out.println("wave " + frEntry.toString() + "\n");

                // don't filter with length because need to handle detour
                List<TimingGroup> nxtTgs = ictInfo.nextTimingGroups(frEntry.tg, (TimingGroup e) -> (e.direction() == dir) || (e == to));
                for (TimingGroup toTg : nxtTgs) {

                    if (verbose)
                        System.out.println("  toTg " + toTg.name());

                    List<Short> locs = new ArrayList<>();

                    if (frEntry.detour) {
                        // TODO: consider both going forward and backward
                        short tloc = (short) (frEntry.loc - toTg.length());
                        // allow only one overshoot
                        if (tloc >= dist) {
                            locs.add(tloc);
                            if (verbose)
                                System.out.println("    add to locs " + tloc + " : " + locs.size());
                        }
                    }

                    short tloc = (short) (frEntry.loc + toTg.length());
                    if ((toTg == to) && (frEntry.loc == dist) && (frEntry.tg.direction() != toTg.direction())) {
                        locs.add(frEntry.loc);
                        if (verbose)
                            System.out.println("    add to locs " + frEntry.loc + " : " + locs.size());
                    }
                    // Ignore too large overshoot
                    else if (tloc <= maxDist) {
                        locs.add(tloc);
                        if (verbose)
                            System.out.println("    add to locs " + tloc + " : " + locs.size());
                    }

                    if (verbose)
                        System.out.println("  locs " + locs.toString());

                    for (Short loc : locs) {
                        if (!distTypeNodemap.containsKey(loc)) {
                            Map<TimingGroup, Object> typeNodeMap = new EnumMap<>(TimingGroup.class);
                            distTypeNodemap.put(loc, typeNodeMap);
//                            System.out.println("  new loc " );
                        }

                        if (!distTypeNodemap.get(loc).containsKey(toTg)) {
                            Object newNode = new Object();
                            g.addVertex(newNode);
                            distTypeNodemap.get(loc).put(toTg, newNode);

                            if (verbose)
                                System.out.println("  new Tg " + toTg.name() + " at loc " + loc);


                            if ((toTg == to) && (loc == dist)) {
                                dst = newNode;
                            } else {
                                WaveEntry newEntry = new WaveEntry(toTg, loc, newNode, loc > dist);
                                nxtWave.add(new WaveEntry(toTg, loc, newNode, loc > dist));

                                if (verbose)
                                    System.out.println("  Add node " + newEntry.toString());
                            }
                        }

                        g.addEdge(frEntry.n, distTypeNodemap.get(loc).get(toTg), new TimingGroupEdge(toTg));

                        if (verbose) {
                            System.out.println("  Add edge " + toTg.name());
                            System.out.println();
                        }
                    }
                }
            }

            if (verbose) {
                System.out.println(count + " nxtWave of size " + nxtWave.size());
                System.out.println("****");
                count++;
            }

            wave = nxtWave;
        }

        if (dst == null)
            throw new RuntimeException("Error: Destination timing group is not reachable.");



        GetTermNodes<Graph<Object, TimingGroupEdge>,Object, List<Object>> getTermNodes =
        (ig, in) -> {
            List<Object> termNodes = new ArrayList<>();
            for (Object n : ig.vertexSet()) {
                // node with no fanout
                if ((ig.outDegreeOf(n) <= 0) && (n != in)) {
                    termNodes.add(n);
                }
                // node with an inedge and outedge to another node
                if ((ig.outDegreeOf(n) == 1)&&(ig.inDegreeOf(n)==1)) {
                    Set<Object> nodeSet = new HashSet<Object>();
                    for (TimingGroupEdge e : ig.edgesOf(n)) {
                        nodeSet.add(ig.getEdgeSource(e));
                        nodeSet.add(ig.getEdgeTarget(e));
                    }
                    if (nodeSet.size() == 2) {
                        termNodes.add(n);
                    }
                }
            }
            return termNodes;
        };


        // Trim out dangling vertices
        List<Object> termNodes = getTermNodes.apply(g, dst);
        if (verbose)
            System.out.println("Number of termNodes " + termNodes.size());

        while (!termNodes.isEmpty()) {
            for (Object n : termNodes) {
                g.removeVertex(n);
            }

            termNodes = getTermNodes.apply(g, dst);
            if (verbose)
                System.out.println("Number of termNodes " + termNodes.size());
        }


        if (plot) {
            String dotFileName = "dbg_data/" + from + "_" + to + "_" + dir + "_" + dist + "_" + maxDist;
            PrintStream graphVizPrintStream = null;
            try {
                graphVizPrintStream = new PrintStream(dotFileName);
            } catch (FileNotFoundException e1) {
                e1.printStackTrace();
            }

            Map<Object,String> nodeNames = new HashMap<>();
            for (Map.Entry<Short, Map<TimingGroup,Object>> forLoc : distTypeNodemap.entrySet()) {
                for (Map.Entry<TimingGroup,Object> forTg : forLoc.getValue().entrySet()) {
                    String name = forTg.getKey().name() + "_" + forLoc.getKey();
                    nodeNames.put(forTg.getValue(),name);
                }
            }

            graphVizPrintStream.println("digraph {");
            graphVizPrintStream.println("rankdir=LR;");
            for (TimingGroupEdge e : g.edgeSet()) {
                String srcName = nodeNames.get(g.getEdgeSource(e));
                String dstName = nodeNames.get(g.getEdgeTarget(e));
                graphVizPrintStream.println("  " + srcName + " -> " + dstName + " [ label=\"" + e.toGraphvizDotString() + "\" ];");
            }
            graphVizPrintStream.println("}");
            graphVizPrintStream.close();
        }


        DelayGraphEntry res = new DelayGraphEntry();
        res.g = g;
        res.src = src;
        res.dst = dst;
        return res;
    }
    public DelayGraphEntry listPaths(TimingGroup from, TimingGroup to, Direction dir, int dist, int detourDist) {
        return listPaths(from, to, dir, dist, detourDist, false, false);
    }

    private void buildTables() {
        int maxHorDetourDist = TimingGroup.HORT_LONG.length()-1;
        int maxVerDetourDist = TimingGroup.VERT_LONG.length()-1;

        boolean verbose = false;
        boolean plot    = true;

        // For SitePinToSitePinHorizontally
        // Detour horizontally may cross hard-block column. Allow vertical detour to.
        SitePinToSitePinHorizontally = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            System.out.println("build hor " + i);
            SitePinToSitePinHorizontally.add(
                listPaths(TimingGroup.CLE_OUT, TimingGroup.CLE_IN, Direction.HORIZONTAL,
                        i, Math.min(i,maxHorDetourDist), verbose, plot));
        }

        SitePinToSitePinVertically = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            System.out.println("build ver " + i);
            SitePinToSitePinVertically.add(
                    listPaths(TimingGroup.CLE_OUT, TimingGroup.CLE_IN, Direction.VERTICAL,
                            i, Math.min(i,maxVerDetourDist), verbose, plot));
        }


        sitePinToVerticalTG = new ArrayList<>();
        VerticalTGToSitePin = new ArrayList<>();
        List<TimingGroup> vertTgs = getTimingGroup((TimingGroup e) -> (e.direction() == Direction.VERTICAL));
        // There is no need to turn for dist 0, add a dummy to keep using index as distance
        sitePinToVerticalTG.add(new EnumMap<>(TimingGroup.class));
        VerticalTGToSitePin.add(new EnumMap<>(TimingGroup.class));
        for (int i = 1; i < width; i++) {
            sitePinToVerticalTG.add(new EnumMap<>(TimingGroup.class));
            VerticalTGToSitePin.add(new EnumMap<>(TimingGroup.class));
            for (TimingGroup tg : vertTgs) {
                System.out.println("C i " + i + " " + tg.name());
                int minDetour = i;
                if (tg == TimingGroup.VERT_LONG)
                    // TODO: this should come from interconnectInfo somehow
                    minDetour = Math.max(i, TimingGroup.HORT_QUAD.length()); // LONG must be driven by QUAD, it need at least detour 4
                sitePinToVerticalTG.get(i).put(tg,
                        listPaths(TimingGroup.CLE_OUT, tg, Direction.HORIZONTAL,
                                i, Math.min(minDetour, maxHorDetourDist), verbose, plot));
                System.out.println("D i " + i + " " + tg.name());
                VerticalTGToSitePin.get(i).put(tg,
                        listPaths(tg, TimingGroup.CLE_IN, Direction.HORIZONTAL,
                                i, Math.min(i, maxHorDetourDist), verbose, plot));
            }
        }

        sitePinToHorizontalTG = new ArrayList<>();
        HorizontalTGToSitePin = new ArrayList<>();
        List<TimingGroup> hortTgs = getTimingGroup((TimingGroup e) -> (e.direction() == Direction.HORIZONTAL));
        // There is no need to turn for dist 0, add a dummy to keep using index as distance
        sitePinToHorizontalTG.add(new EnumMap<>(TimingGroup.class));
        HorizontalTGToSitePin .add(new EnumMap<>(TimingGroup.class));
        for (int i = 1; i < height; i++) {
            sitePinToHorizontalTG.add(new EnumMap<>(TimingGroup.class));
            HorizontalTGToSitePin .add(new EnumMap<>(TimingGroup.class));
            for (TimingGroup tg : hortTgs) {
                System.out.println("E i " + i + " " + tg.name());
                int minDetour = i;
                if (tg == TimingGroup.HORT_LONG)
                    // TODO: this should come from interconnectInfo somehow
                    minDetour = Math.max(i, TimingGroup.VERT_QUAD.length()); // LONG must be driven by QUAD, it need at least detour 4
                sitePinToHorizontalTG.get(i).put(tg,
                        listPaths(TimingGroup.CLE_OUT, tg, Direction.VERTICAL,
                                i, Math.min(minDetour, maxVerDetourDist), verbose, plot));
                System.out.println("F i " + i + " " + tg.name());
                HorizontalTGToSitePin .get(i).put(tg,
                        listPaths(tg, TimingGroup.CLE_IN, Direction.VERTICAL,
                                i, Math.min(i, maxVerDetourDist), verbose, plot));
            }
        }
    }

    // Declare using implementation type ArrayList because random indexing is needed.

    // index by distance from sitePin
    private ArrayList<DelayGraphEntry> SitePinToSitePinVertically;
    private ArrayList<DelayGraphEntry> SitePinToSitePinHorizontally;

    // index by distance from sitePin, then VertTG index
    // does the list include the Vertical TG ?

    Map<TimingGroup, Object> typeNodeMap = new EnumMap<>(TimingGroup.class);
    private ArrayList<Map<TimingGroup,DelayGraphEntry>> sitePinToVerticalTG; // go horizontally
    private ArrayList<Map<TimingGroup,DelayGraphEntry>> sitePinToHorizontalTG; // go vertically
    private ArrayList<Map<TimingGroup,DelayGraphEntry>> VerticalTGToSitePin; // go horizontally
    private ArrayList<Map<TimingGroup,DelayGraphEntry>> HorizontalTGToSitePin; // go vertically



    @Override
    public short getMinDelayToSinkPin(com.xilinx.rapidwright.timing.TimingGroup timingGroup, com.xilinx.rapidwright.timing.TimingGroup sinkPin) {
        // 1) look up min paths from table. broken the path up if necessary.
        // 2) For each min path, apply location (from TG, sinkPin) to get d.
        // 3) compute delay of each path
        // 4) return the min among paths
        return 0;
    }



    private static Graph<String, DefaultWeightedEdge> createStringGraph() {
        Graph<String, DefaultWeightedEdge> g = new SimpleDirectedWeightedGraph<>(DefaultWeightedEdge.class);

        String v1 = "v1";
        String v2 = "v2";
        String v3 = "v3";
        String v4 = "v4";

        // add the vertices
        g.addVertex(v1);
        g.addVertex(v2);
        g.addVertex(v3);
        g.addVertex(v4);

        // add edges to create a circuit
        DefaultWeightedEdge e = g.addEdge(v1, v2);
        g.setEdgeWeight(e, 1);
        e = g.addEdge(v2, v3);
        g.setEdgeWeight(e, 1);
        e = g.addEdge(v3, v4);
        g.setEdgeWeight(e, 1);
        e = g.addEdge(v1, v3);
        g.setEdgeWeight(e, 5);
        return g;
    }
    private static SimpleDelayGraphEntry createTestSimpleDelayGraph() {
        Graph<Object, DefaultWeightedEdge> g = new SimpleDirectedWeightedGraph<>(DefaultWeightedEdge.class);

        Object v1 = new Object();
        Object v2 = new Object();
        Object v3 = new Object();
        Object v4 = new Object();

        // add the vertices
        g.addVertex(v1);
        g.addVertex(v2);
        g.addVertex(v3);
        g.addVertex(v4);

        // add edges to create a circuit
        DefaultWeightedEdge e = g.addEdge(v1, v2);
        g.setEdgeWeight(e, 1);
        e = g.addEdge(v2, v3);
        g.setEdgeWeight(e, 1);
        e = g.addEdge(v3, v4);
        g.setEdgeWeight(e, 1);
        e = g.addEdge(v1, v3);
        g.setEdgeWeight(e, 5);

        SimpleDelayGraphEntry res = new SimpleDelayGraphEntry();
        res.g = g;
        res.src = v1;
        res.dst = v4;
        return res;
    }

    private static DelayGraphEntry createTestDelayGraph() {
        Graph<Object, TimingGroupEdge> g = new SimpleDirectedWeightedGraph<>(TimingGroupEdge.class);

        Object v1 = new Object();
        Object v2 = new Object();
        Object v3 = new Object();
        Object v4 = new Object();

        // add the vertices
        g.addVertex(v1);
        g.addVertex(v2);
        g.addVertex(v3);
        g.addVertex(v4);


        //  no need to add edge, it will be updated anyway
        g.addEdge(v1, v2, new TimingGroupEdge(TimingGroup.HORT_DOUBLE));
        g.addEdge(v2, v3, new TimingGroupEdge(TimingGroup.HORT_DOUBLE));
        g.addEdge(v3, v4, new TimingGroupEdge(TimingGroup.HORT_DOUBLE));
        g.addEdge(v2, v4, new TimingGroupEdge(TimingGroup.HORT_QUAD));


        DelayGraphEntry res = new DelayGraphEntry();
        res.g = g;
        res.src = v1;
        res.dst = v4;
        return res;
    }

    public static <GraphPath> void main(String args[]) {
        for (TimingGroup tg : TimingGroup.values()) {
            if (tg.direction() == Direction.VERTICAL) {
                System.out.println(tg.toString());
            }
        }
        Device device = Device.getDevice("xcvu3p-ffvc1517");
        InterconnectInfo ictInfo = new InterconnectInfo();

        int width  = TimingGroup.HORT_LONG.length() + TimingGroup.HORT_QUAD.length() + TimingGroup.HORT_DOUBLE.length() + 1;
        int height = TimingGroup.VERT_LONG.length() + TimingGroup.VERT_QUAD.length() + TimingGroup.VERT_DOUBLE.length() + 1;
        System.out.println("width " + width + " height " + height);
        DelayEstimatorTable est = new DelayEstimatorTable(device,ictInfo,width,height);
        if (false) {
            //ictInfo.dumpInterconnectHier();
//            for (DelayEstimatorBase.TimingGroup i : (DelayEstimatorBase.TimingGroup[]) ictInfo.nextTimingGroups(TimingGroup.CLE_OUT)) {
//                System.out.printf("    ");
//                System.out.println(i.toString());
//            }
//            int result = est.getTimingModel().computeHorizontalDistFromArray(74,76, GroupDelayType.QUAD);
//            System.out.println(result);
        }
         if (false) {
            System.out.println("Vert");
            for (DelayEstimatorBase.TimingGroup i :
                    ictInfo.nextTimingGroups(TimingGroup.CLE_OUT, (TimingGroup e) -> (e.direction() == Direction.VERTICAL) && (e.length() <= 1))) {
                System.out.printf("    ");
                System.out.println(i.toString());
            }
            System.out.println("length 1");
            for (DelayEstimatorBase.TimingGroup i :
                    ictInfo.nextTimingGroups(TimingGroup.CLE_OUT, (TimingGroup e) -> e.length() <= 1)) {
                System.out.printf("    ");
                System.out.println(i.toString());
            }
        }

        // test dijk
        if (false) {
//            Graph<String, DefaultWeightedEdge> tgraph = createStringGraph();
//
//
//            // Find shortest paths
//            String source = "v1";
//            String destination = "v4";
////        List<DefaultEdge> sp = DelayGraph.findPathBetween(tgraph, source, destination);
//            org.jgrapht.GraphPath<String, DefaultWeightedEdge> gp =
//                    DijkstraWithCallbacks.findPathBetween(tgraph, source, destination,
////               (Graph<String, DefaultWeightedEdge> g, DefaultWeightedEdge e, Double v) -> {g.setEdgeWeight(e,1);});
//                            (Graph<String, DefaultWeightedEdge> g, DefaultWeightedEdge e, Double v) -> {
//                            });
//
//
//            // Print results
//            System.out.println(gp.toString().replace(",", ",\n") + "\n");
////        System.out.println("Shortest path from " + source + " to " + destination + ":");
////        for (DefaultEdge e : sp) {
////            System.out.println(tgraph.getEdgeSource(e) + " -> " + tgraph.getEdgeTarget(e));
////        }
//
//            Double minDel = DijkstraWithCallbacks.findMinWeightBetween(tgraph, source, destination,
////                (Graph<String, DefaultWeightedEdge> g, DefaultWeightedEdge e, Double v) -> {g.setEdgeWeight(e,1);});
//                    (Graph<String, DefaultWeightedEdge> g, DefaultWeightedEdge e, Double v) -> {
//                    });
//            System.out.println("minDel " + minDel);
        }

        // test simpledelayGraph
        if (false) {
//            System.out.println("begin test simpledelayGraph");
//            SimpleDelayGraphEntry sentry = createTestSimpleDelayGraph();
//
////            org.jgrapht.GraphPath<Object, DefaultWeightedEdge> gp =
////                    DijkstraWithCallbacks.findPathBetween(sentry.g, sentry.src, sentry.dst,
//            Double minDel = DijkstraWithCallbacks.findMinWeightBetween(sentry.g, sentry.src, sentry.dst,
//                (Graph<Object, DefaultWeightedEdge> g, DefaultWeightedEdge e, Double v) -> {g.setEdgeWeight(e,1);});
////                    (Graph<Object, DefaultWeightedEdge> g, DefaultWeightedEdge e, Double v) -> {});
//            System.out.println("minDel " + minDel);
//            System.out.println("end test simpledelayGraph");
        }
        // test delayGraph
        if (false) {
            System.out.println("begin test delayGraph");
            DelayGraphEntry sentry = createTestDelayGraph();

//            org.jgrapht.GraphPath<Object, DefaultWeightedEdge> gp =
//                    DijkstraWithCallbacks.findPathBetween(sentry.g, sentry.src, sentry.dst,
            Double minDel = DijkstraWithCallbacks.findMinWeightBetween(sentry.g, sentry.src, sentry.dst, 2.0d,
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {g.setEdgeWeight(e,1);},
                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
                            -> {g.setEdgeWeight(e,calcTimingGroupDelay(e,v));},
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {return 0.0;}
                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
//                            -> {System.out.println("*Dis " + e.getTimingGroup().name() + " v " + v + " len " + e.getTimingGroup().length());return v + e.getTimingGroup().length();}
                            -> {return v + e.getTimingGroup().length();}
                    );
            System.out.println("minDel " + minDel);
            System.out.println("end test delayGraph");
        }
        if (false) {
            double dAtSource = 3.0d;
            short  dist      = 0;
            short  detour    = 0;
            DelayGraphEntry sentry = est.listPaths(TimingGroup.CLE_OUT, TimingGroup.CLE_IN, Direction.HORIZONTAL, dist, detour, true, true);
            Double minDel = DijkstraWithCallbacks.findMinWeightBetween(sentry.g, sentry.src, sentry.dst, dAtSource,
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {g.setEdgeWeight(e,1);},
                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
                            -> {g.setEdgeWeight(e,calcTimingGroupDelay(e,v));},
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {return 0.0;}
                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
//                            -> {System.out.println("*Dis " + e.getTimingGroup().name() + " v " + v + " len " + e.getTimingGroup().length());return v + e.getTimingGroup().length();}
                           -> {return v + e.getTimingGroup().length();}
            );
            System.out.println("minDel " + minDel);
            System.out.println();
            org.jgrapht.GraphPath<Object, TimingGroupEdge> minPath = DijkstraWithCallbacks.findPathBetween(sentry.g, sentry.src, sentry.dst, dAtSource,
                    //                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {g.setEdgeWeight(e,1);},
                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
                            -> {g.setEdgeWeight(e,calcTimingGroupDelay(e,v));},
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {return 0.0;}
                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
//                            -> {System.out.println("*Dis " + e.getTimingGroup().name() + " v " + v + " len " + e.getTimingGroup().length());return v + e.getTimingGroup().length();}
                            -> {return v + e.getTimingGroup().length();}
            );
            System.out.println("\nPath with min delay: " + ((int)minPath.getWeight())+ " ps");
            System.out.println("\nPath details:");
            System.out.println(minPath.toString().replace(",", ",\n")+"\n");
        }
        if (true) {
            est.buildTables();
        }
    }

}
