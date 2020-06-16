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
import com.xilinx.rapidwright.util.Pair;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Conventions:
// 1) Each graph represent all possible path from between 2 TGs. The delay of the beginning TG is not included.
//    But, that of the ending TG is.



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
public class DelayEstimatorTable<T extends InterconnectInfo> extends DelayEstimatorBase<T> {

    /**
     * Constructor from a device.
     * @param device Target device
     * @param ictInfo Interconnect information. TODO: should be selected automatically from device.
     * @param width Width of delay tables.
     * @param height Height of delay tables.
     */
    DelayEstimatorTable(Device device, T ictInfo, short width, short height, int verbose) {
        super(device, ictInfo, verbose);

        assert width < ictInfo.minTableWidth() :
                "DelayEstimatorTable expects larger custom table width.";
        assert width < ictInfo.minTableHeight() :
                "DelayEstimatorTable expects larger custom table height.";

        this.width   = width;
        this.height  = height;
        buildTables();
    }
    DelayEstimatorTable(Device device, T ictInfo, int verbose) {
        super(device, ictInfo, verbose);
        this.width   = ictInfo.minTableWidth();
        this.height  = ictInfo.minTableHeight();
        buildTables();
    }
    DelayEstimatorTable(String partName, T ictInfo, short width, short height, int verbose) {
        this(Device.getDevice(partName), ictInfo, width, height, verbose);
    }
    DelayEstimatorTable(String partName, T ictInfo, int verbose) {
        this(Device.getDevice(partName), ictInfo, verbose);
    }
    @Override
    public short getMinDelayToSinkPin(com.xilinx.rapidwright.timing.TimingGroup timingGroup,
                                      com.xilinx.rapidwright.timing.TimingGroup sinkPin) {

        boolean dumpPath = true;
        // 1) look up min paths from table. broken the path up if necessary.
        // 2) For each min path, apply location (from TG, sinkPin) to get d.
        // 3) compute delay of each path
        // 4) return the min among paths


        // TODO: need to populate these from TGs
        short begX = 0;
        short begY = 0;
        TileSide begSide = TileSide.E;
        short endX = 10;
        short endY = 15;
        TileSide endSide = TileSide.E;
        // end must always be CLE_IN,
        T.TimingGroup endTg = T.TimingGroup.CLE_IN;
        T.TimingGroup begTg = T.TimingGroup.CLE_OUT;

        return getMinDelayToSinkPin(begTg, endTg, begX, begY, endX, endY);
    }

    private short getMinDelayToSinkPin(T.TimingGroup begTg, T.TimingGroup endTg,
                                      short begX, short begY, short endX, short endY) {

        assert endTg == T.TimingGroup.CLE_IN : "getMinDelayToSinkPin expects CLE_IN as the target timing group.";


        List<T.TimingGroup> begTgs = new ArrayList<T.TimingGroup>() {{add(begTg);}};
        List<T.TimingGroup> endTgs = new ArrayList<T.TimingGroup>() {{add(endTg);}};

        if (begX == endX) {
            Map<T.TimingGroup,Map<T.TimingGroup,Short>> delayY =
                    findMinDelayOneDirection(this::findMinVerticalDelay, begTgs, endTgs, begY, endY);
            return delayY.get(begTg).get(endTg);
        } else if (begY == endY) {
            Map<T.TimingGroup,Map<T.TimingGroup,Short>> delayY =
                    findMinDelayOneDirection(this::findMinHorizontalDelay, begTgs, endTgs, begX, endX);
            return delayY.get(begTg).get(endTg);
        } else {
            // The key TimingGroups are used to connect between vertical and horizontal sections.
            // The direction of the key TimingGroup is not used.
            List<T.TimingGroup> keyTgs = ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.VERTICAL));

            List<Short> pathDelays = new ArrayList<>();
            {
                Map<T.TimingGroup,Map<T.TimingGroup,Short>> delay1 =
                        findMinDelayOneDirection(this::findMinHorizontalDelay, begTgs, keyTgs, begX, endX);
                Map<T.TimingGroup,Map<T.TimingGroup,Short>> delay2 =
                        findMinDelayOneDirection(this::findMinVerticalDelay, keyTgs, endTgs, begY, endY);

                for (T.TimingGroup tg : keyTgs) {
                    pathDelays.add((short) (delay1.get(begTg).get(tg) + delay2.get(tg).get(endTg)));
                }
            }

            {
                Map<T.TimingGroup,Map<T.TimingGroup,Short>> delay1 =
                        findMinDelayOneDirection(this::findMinVerticalDelay, begTgs, keyTgs, begY, endY);
                Map<T.TimingGroup,Map<T.TimingGroup,Short>> delay2 =
                        findMinDelayOneDirection(this::findMinHorizontalDelay, keyTgs, endTgs, begX, endX);

                for (T.TimingGroup tg : keyTgs) {
                    pathDelays.add((short) (delay1.get(begTg).get(tg) + delay2.get(tg).get(endTg)));
                }
            }
            return Collections.min(pathDelays);
        }
    }

    private short width;
    private short height;

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

    // Declare using implementation type ArrayList because random indexing is needed.
    // index by distance, from TG and to TG
    private ArrayList<Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>>> TgToTgVertically;
    private ArrayList<Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>>> TgToTgHorizontally;


    // -----------------------   Methods for builing tabels ------------------------
    @FunctionalInterface
    interface GetTermNodes<G,N,L> {
        L apply(G g, N n);
    }

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
    private DelayGraphEntry listPaths(T.TimingGroup from, T.TimingGroup to, T.Direction dir, int dist, int detourDist,
                                     boolean verbose, boolean plot) {

        class WaveEntry {
            public T.TimingGroup tg;
            public short       loc;
            public Object      n;
            public boolean     detour;
            WaveEntry(T.TimingGroup tg, short loc, Object n, boolean detour) {
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

        Map<Short, Map<T.TimingGroup,Object>> distTypeNodemap = new HashMap();


        // BFS, not DFS, because getting all sinks in one call to nextTimingGroups.

        Graph<Object, TimingGroupEdge> g = new SimpleDirectedWeightedGraph<>(TimingGroupEdge.class);
        Object src = new Object();
        Object dst = null;
        g.addVertex(src);
        List<WaveEntry> wave = new ArrayList<WaveEntry>() {{add(new WaveEntry(from, (short) 0, src, false));}};

        int count = 0;

        while (!wave.isEmpty()) {
            List<WaveEntry> nxtWave = new ArrayList<>();
            for (WaveEntry frEntry : wave) {

                if (verbose)
                    System.out.println("wave " + frEntry.toString() + "\n");

                // don't filter with length because need to handle detour
                List<T.TimingGroup> nxtTgs = ictInfo.nextTimingGroups(frEntry.tg, (T.TimingGroup e) -> (e.direction() == dir) || (e == to));
                for (T.TimingGroup toTg : nxtTgs) {

                    if (verbose)
                        System.out.println("  toTg " + toTg.name());

                    List<Pair<Short,Boolean>> locs = new ArrayList<>();

                    if (frEntry.detour) {
                        // TODO: consider both going forward and backward
                        short tloc = (short) (frEntry.loc - toTg.length());
                        // allow only one overshoot
                        if (tloc >= dist) {
                            locs.add(new Pair<>(tloc, true));
                            if (verbose)
                                System.out.println("    add to locs " + tloc + " : " + locs.size());
                        }
                    }

                    short tloc = (short) (frEntry.loc + toTg.length());
                    if ((toTg == to) && (frEntry.loc == dist) && (frEntry.tg.direction() != toTg.direction())) {
                        locs.add(new Pair<>(frEntry.loc, false));
                        if (verbose)
                            System.out.println("    add to locs " + frEntry.loc + " : " + locs.size());
                    }
                    // Ignore too large overshoot
                    else if (tloc <= maxDist) {
                        locs.add(new Pair<>(tloc,false));
                        if (verbose)
                            System.out.println("    add to locs " + tloc + " : " + locs.size());
                    }

                    if (verbose)
                        System.out.println("  locs " + locs.toString());

                    for (Pair<Short,Boolean> loc_pair : locs) {
                        short loc = loc_pair.getFirst();
                        if (!distTypeNodemap.containsKey(loc)) {
                            Map<T.TimingGroup, Object> typeNodeMap = new EnumMap<>(T.TimingGroup.class);
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

                        g.addEdge(frEntry.n, distTypeNodemap.get(loc).get(toTg), new TimingGroupEdge(toTg, loc_pair.getSecond()));

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
            for (Map.Entry<Short, Map<T.TimingGroup,Object>> forLoc : distTypeNodemap.entrySet()) {
                for (Map.Entry<T.TimingGroup,Object> forTg : forLoc.getValue().entrySet()) {
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
    public DelayGraphEntry listPaths(T.TimingGroup from, T.TimingGroup to, T.Direction dir, int dist, int detourDist) {
        return listPaths(from, to, dir, dist, detourDist, false, false);
    }

    private void iniTables() {
        TgToTgVertically = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>> srcMap = new EnumMap<>(T.TimingGroup.class);
            for (T.TimingGroup tg : T.TimingGroup.values()) {
                srcMap.put(tg,new EnumMap<>(T.TimingGroup.class));
            }
            TgToTgVertically.add(srcMap);
        }

        TgToTgHorizontally = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>> srcMap = new EnumMap<>(T.TimingGroup.class);
            for (T.TimingGroup tg : T.TimingGroup.values()) {
                srcMap.put(tg,new EnumMap<>(T.TimingGroup.class));
            }
            TgToTgHorizontally.add(srcMap);
        }
    }

    private void buildTables() {
        int verbose = 1;
        boolean plot    = true;

        int maxHorDetourDist = T.TimingGroup.HORT_LONG.length()-1;
        int maxVerDetourDist = T.TimingGroup.VERT_LONG.length()-1;

        iniTables();

        {
            // There is no need to turn for dist 0, add a dummy to keep using index as distance
            List<T.TimingGroup> frTgs =
                    ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL));
            List<T.TimingGroup> toTgs =
                    ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.VERTICAL));
            frTgs.add(T.TimingGroup.CLE_OUT);
            toTgs.add(T.TimingGroup.CLE_IN);
            // TODO how about bounce
            for (int i = 1; i < width; i++) {
                for (T.TimingGroup frTg : frTgs) {
                    for (T.TimingGroup toTg : toTgs) {

                        int minDetour = Math.max(i, ictInfo.minDetour(toTg));
                        int detourDist = Math.min(minDetour, maxVerDetourDist);

                        if (verbose > 0)
                            System.out.println("build hor  dist " + i + " det " + detourDist + " " + frTg.name() + " " + toTg.name());

                        TgToTgHorizontally.get(i).get(frTg).put(toTg, listPaths(frTg, toTg,
                                T.Direction.HORIZONTAL, i, detourDist, verbose > 1, plot));
                    }
                }
            }
        }

        {
            List<T.TimingGroup> frTgs =
                    ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.VERTICAL));
            List<T.TimingGroup> toTgs =
                    ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL));
            frTgs.add(T.TimingGroup.CLE_OUT);
            toTgs.add(T.TimingGroup.CLE_IN);
            for (int i = 1; i < height; i++) {
                for (T.TimingGroup frTg : frTgs) {
                    for (T.TimingGroup toTg : toTgs) {

                        int minDetour = Math.max(i, ictInfo.minDetour(toTg));
                        int detourDist = Math.min(minDetour, maxVerDetourDist);

                        if (verbose > 0)
                            System.out.println("build ver  dist " + i + " det " + detourDist + " " + frTg.name() + " " + toTg.name());

                        TgToTgVertically.get(i).get(frTg).put(toTg, listPaths(frTg, toTg,
                                T.Direction.VERTICAL, i, detourDist, verbose > 1, plot));
                    }
                }
            }
        }
    }


    // -----------------------   Methods for computing min delay ------------------------

    /**
     *
     * @param fromTgs
     * @param toTgs
     * @param s
     * @param t
     * @return
     */
    private Map<T.TimingGroup,Map<T.TimingGroup,Short>> findMinDelayOneDirection(findMinDelayInterface computeOnDir,
            List<T.TimingGroup> fromTgs, List<T.TimingGroup> toTgs, short s, short t) {

        short dist = (short) (t - s);

        // separate building this to declutter the compute logic below.
        Map<T.TimingGroup,Map<T.TimingGroup,Short>> res = new HashMap<>();
        for (T.TimingGroup fromTg : fromTgs) {
            Map<T.TimingGroup, Short> resToTgs = new HashMap<>();
            res.put(fromTg, resToTgs);
        }


        List<Short> pathDelays = new ArrayList<>();
        for (T.TimingGroup fromTg : fromTgs) {
            for (T.TimingGroup toTg : toTgs) {
                res.get(fromTg).put(toTg,computeOnDir.execute(fromTg, toTg, s, dist));
            }
        }

        return res;
    }

    @FunctionalInterface
    private interface findMinDelayInterface<T extends InterconnectInfo> {
        public short execute(T.TimingGroup s, T.TimingGroup t, short sY, short distY);
    }

    /**
     * Find min delay of vertical route between the given source and sink.
     * If the distance is larger than the table height, some LONG will be inserted as necessary.
     * To be used with findMinDelayInterface functional interface
     * @param s Source timing group
     * @param t Sink tiiming group
     * @param loc Location of the source
     * @param dist Distance of the route
     * @return Delay of the route in ps
     */
    // The return delay do not include that of the dst
    private short findMinVerticalDelay(T.TimingGroup s, T.TimingGroup t, short loc, short dist) {

        short limit = height;
        T.TimingGroup extendingTg = T.TimingGroup.VERT_LONG;
        ArrayList<Map<T.TimingGroup, Map<T.TimingGroup, DelayGraphEntry>>> table = TgToTgVertically;

        return findMinDelay(table, limit, extendingTg, s, t, loc, dist);
    }
    /**
     * Find min delay of horizontal route between the given source and sink.
     * see findMinVerticalDelay for descriptions of parameters
     */
    private short findMinHorizontalDelay(T.TimingGroup s, T.TimingGroup t, short loc, short dist) {

        short limit = width;
        T.TimingGroup extendingTg = T.TimingGroup.HORT_LONG;
        ArrayList<Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>>> table = TgToTgHorizontally;

        return findMinDelay(table, limit, extendingTg, s, t, loc, dist);
    }

    /**
     * Find the mim delay of a straight route from the given source to sink.
     * If the route is longer than the limit of the given table, it will be divided into 3 sections.
     * Multiple of extendingTg will be used to middle section.
     * The delay of the ends will be looked up from the table.
     * @param table Table for delay lookup
     * @param limit Size of table
     * @param extendingTg TG used to extend the table to cover the route
     * @param s Source timing group
     * @param t Sink timing group
     * @param loc Location of the source
     * @param dist Distance of the route
     * @return Delay of the route in ps
     */
    private short findMinDelay(
            ArrayList<Map<T.TimingGroup, Map<T.TimingGroup, DelayGraphEntry>>> table, short limit, T.TimingGroup extendingTg,
            T.TimingGroup s, T.TimingGroup t, short loc, short dist) {

        if (verbose > 2)
            System.out.println("      findMinDelay from " + s.name() + " to " + t.name() + " loc " + loc + " dist " + dist);

        boolean isBackward = dist < 0;
        dist = (short) Math.abs(dist);

        short delay = -1;
        if (dist < limit) {
            delay = LookupDelay(table.get(dist).get(s).get(t), loc, isBackward);
        } else {
            // table is stored with TG going the other direction, need to get the equivalent of extendingTg

            List<T.TimingGroup> keyTgs = ictInfo.getTimingGroup((T.TimingGroup e) ->
                    (e.direction() != extendingTg.direction()) && (e.type() == extendingTg.type())
            );
            T.TimingGroup equivTg = keyTgs.get(0);

            // width and height must cover distance that use at least one long.
            // break at long
            List<Short> pathDelays = new ArrayList<>();
            int gap = dist - 2 * (limit-1);
            double k = (int) Math.ceil(1.0*gap / extendingTg.length());
            // start : i + k*L = h+g => i = h+g-k*l
            // last  : i = height
            for (int i = (int) (limit-1 + gap - k * extendingTg.length()); i < limit; i++) {
                if (verbose > 2)
                    System.out.println("      lookup begin dist " + i + " at " + loc);
                short delayBeginning = LookupDelay(table.get(i).get(s).get(equivTg),
                        loc, isBackward);

                if (verbose > 3)
                    System.out.println("        lookup for dummy extention at " + i);
                // delay of the ending was included in delayBeginning. subtract it out.
                delayBeginning -= (short) calcTimingGroupDelay(equivTg, (double) i, isBackward);

                short distForEnding = (short) (2*(limit-1) + gap - (i + k*extendingTg.length()));
                short locForEnding  = (short) (i+k*extendingTg.length());
                if (verbose > 2)
                    System.out.println("      lookup end dist " + distForEnding + " at " + locForEnding);
                short delayEnding = LookupDelay(table.get(distForEnding).get(extendingTg).get(t),
                        locForEnding, isBackward);
                // delay of each extending tg is location dependent. Thus, need to recompute.
                short extendingDelay = 0;
                for (int j = 0; j < k; j++) {
                    double beginTg = j * extendingTg.length();
                    if (verbose > 3)
                        System.out.println("        lookup for extention at " + beginTg);
                    extendingDelay += (short) calcTimingGroupDelay(extendingTg, (double) beginTg, isBackward);
                }
                pathDelays.add((short) (delayBeginning + delayEnding + extendingDelay));

                break; // TODO: TBD
            }
            delay = Collections.min(pathDelays);
        }

        return delay;
    }


    /**
     * Find the min delay among all paths within the given graph
     * @param sentry Entry (contain the graph) from a delay table
     * @param dAtSource Coordinate of the source to evaluate the delay
     * @param isBackward Indicate that the direction of source to sink is going left or down
     * @return Delay of the connection in ps
     */
    // The return delay do not include that of the dst
    private short LookupDelay(DelayGraphEntry sentry, double dAtSource, boolean isBackward) {

        Double minDel = DijkstraWithCallbacks.findMinWeightBetween(sentry.g, sentry.src, sentry.dst, dAtSource,
                // ExamineEdge. To update edge weight which depend on the beginning loc, length and direction
                // of the timing group edge.
                (g, u, e, loc) -> {g.setEdgeWeight(e,calcTimingGroupDelay(e,loc, isBackward));},
                // DiscoverVertex. To propagate location at the beginning loc of a timing group edge.
                (g, u, e, loc) -> {return updateLoc(e, loc, isBackward);}
        );

        if (verbose > 5) {
            org.jgrapht.GraphPath<Object, TimingGroupEdge> minPath = DijkstraWithCallbacks.findPathBetween(sentry.g, sentry.src, sentry.dst, dAtSource,
                    (g, u, e, loc) -> {g.setEdgeWeight(e,calcTimingGroupDelay(e,loc, isBackward));},
                    (g, u, e, loc) -> {return updateLoc(e, loc, isBackward);}
//                    (g, u, e, v) -> {  g.setEdgeWeight(e, calcTimingGroupDelay(e, v, isBackward)); },
//                    (g, u, e, v) -> { return v + e.getTimingGroup().length(); }
            );
            System.out.println("\nPath with min delay: " + ((short) minPath.getWeight()) + " ps");
            System.out.println("\nPath details:");
            System.out.println(minPath.toString().replace(",", ",\n") + "\n");
        }
        return minDel.shortValue();
    }


    // -----------------------   Methods to help testing ------------------------

    void testLookup() {
        // This is don't to allow automatic check for forward delay == backward delay
        zeroDistArrays();
//        testLookupHorInTable();
//        testLookupVerInTable();
//        testLookupHorOutTable();
        testLookupVerOutTable();
    }

    void testLookupHorInTable() {
        short y = 0;
        for (int i = 1; i < width; i++) {
            System.out.print("testLookupHorInTable " + i + " " );
            short delay_1 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, (short) 0, y, (short) i, y);
            short delay_2 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, (short) i, y, (short) 0, y);
            System.out.println(delay_1 + " " + delay_2);
            assert delay_1 == delay_2;
        }
    }
    void testLookupVerInTable() {
        short x = 0;
        for (int i = 1; i < height; i++) {
            System.out.print("testLookupVerInTable " + i + " " );
            short delay_1 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, x, (short) 0, x, (short) i);
            short delay_2 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, x, (short) i, x, (short) 0);
            System.out.println(delay_1 + " " + delay_2);
            assert delay_1 == delay_2;
        }
    }

    void testLookupHorOutTable() {
        int numCol = distArrays.get(T.Direction.HORIZONTAL).get(GroupDelayType.SINGLE).size();
        short y = 0;
        for (int i = 2*(width -1); i < numCol; i++) {
//        for (int i = 2; i < 3; i++) {
//            System.out.println("testLookupHorOutTable Forward " + i);
            short delay_1 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, (short) 0, y, (short) i, y);
//            System.out.println("testLookupHorOutTable Backward " + i);
            short delay_2 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, (short) i, y, (short) 0, y);
            System.out.print("compare forward and backward " + i + " " );
            System.out.println(delay_1 + " " + delay_2);
//            assert delay_1 == delay_2 : "Forward and backward delays differ";
        }
    }
    void testLookupVerOutTable() {
        int numRow = distArrays.get(T.Direction.VERTICAL).get(GroupDelayType.SINGLE).size();
        short x = 0;
//        for (int i = 2*(height-1); i < numRow; i++) {
//            System.out.print("testLookupVerOutTable " + i + " " );
//            short delay_1 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, x, (short) 0, x, (short) i);
//            short delay_2 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, x, (short) i, x, (short) 0);
//            System.out.println(delay_1 + " " + delay_2);
//            assert delay_1 == delay_2;
        for (int i = 112; i < numRow; i++) {
//            System.out.println("testLookupHorOutTable Forward " + i);
            short delay_1 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, x, (short) 0, x, (short) i);
//            System.out.println("testLookupHorOutTable Backward " + i);
            short delay_2 = getMinDelayToSinkPin(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, x, (short) i, x, (short) 0);
            System.out.print("compare forward and backward " + i + " " );
            System.out.println(delay_1 + " " + delay_2);
//            assert delay_1 == delay_2 : "Forward and backward delays differ";
        }
    }

    public static <GraphPath> void main(String args[]) {

        Device device = Device.getDevice("xcvu3p-ffvc1517");
        InterconnectInfo ictInfo = new InterconnectInfo();
        DelayEstimatorTable est = new DelayEstimatorTable(device,ictInfo, 6);

        est.testLookup();

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
            for (InterconnectInfo.TimingGroup i :
                    ictInfo.nextTimingGroups(InterconnectInfo.TimingGroup.CLE_OUT, (InterconnectInfo.TimingGroup e) -> (e.direction() == InterconnectInfo.Direction.VERTICAL) && (e.length() <= 1))) {
                System.out.printf("    ");
                System.out.println(i.toString());
            }
            System.out.println("length 1");
            for (InterconnectInfo.TimingGroup i :
                    ictInfo.nextTimingGroups(InterconnectInfo.TimingGroup.CLE_OUT, (InterconnectInfo.TimingGroup e) -> e.length() <= 1)) {
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
//            System.out.println("begin test delayGraph");
//            DelayGraphEntry sentry = createTestDelayGraph();
//
////            org.jgrapht.GraphPath<Object, DefaultWeightedEdge> gp =
////                    DijkstraWithCallbacks.findPathBetween(sentry.g, sentry.src, sentry.dst,
//            Double minDel = DijkstraWithCallbacks.findMinWeightBetween(sentry.g, sentry.src, sentry.dst, 2.0d,
////                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {g.setEdgeWeight(e,1);},
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
//                            -> {g.setEdgeWeight(e,calcTimingGroupDelay(e,v,Orientation.FORWARD));},
////                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {return 0.0;}
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
////                            -> {System.out.println("*Dis " + e.getTimingGroup().name() + " v " + v + " len " + e.getTimingGroup().length());return v + e.getTimingGroup().length();}
//                            -> {return v + e.getTimingGroup().length();}
//                    );
//            System.out.println("minDel " + minDel);
//            System.out.println("end test delayGraph");
        }
        if (false) {
//            double dAtSource = 3.0d;
//            short  dist      = 0;
//            short  detour    = 0;
//            DelayGraphEntry sentry = est.listPaths(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, InterconnectInfo.Direction.HORIZONTAL, dist, detour, false, false);
//            Double minDel = DijkstraWithCallbacks.findMinWeightBetween(sentry.g, sentry.src, sentry.dst, dAtSource,
////                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {g.setEdgeWeight(e,1);},
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
//                            -> {g.setEdgeWeight(e,calcTimingGroupDelay(e,v,Orientation.FORWARD));},
////                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {return 0.0;}
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
////                            -> {System.out.println("*Dis " + e.getTimingGroup().name() + " v " + v + " len " + e.getTimingGroup().length());return v + e.getTimingGroup().length();}
//                           -> {return v + e.getTimingGroup().length();}
//            );
//            System.out.println("minDel " + minDel);
//            System.out.println();
//            org.jgrapht.GraphPath<Object, TimingGroupEdge> minPath = DijkstraWithCallbacks.findPathBetween(sentry.g, sentry.src, sentry.dst, dAtSource,
//                    //                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {g.setEdgeWeight(e,1);},
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
//                            -> {g.setEdgeWeight(e,calcTimingGroupDelay(e,v,Orientation.FORWARD));},
////                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v) -> {return 0.0;}
//                    (Graph<Object, TimingGroupEdge> g, Object u, TimingGroupEdge e, Double v)
////                            -> {System.out.println("*Dis " + e.getTimingGroup().name() + " v " + v + " len " + e.getTimingGroup().length());return v + e.getTimingGroup().length();}
//                            -> {return v + e.getTimingGroup().length();}
//            );
//            System.out.println("\nPath with min delay: " + ((int)minPath.getWeight())+ " ps");
//            System.out.println("\nPath details:");
//            System.out.println(minPath.toString().replace(",", ",\n")+"\n");
        }
        if (false) {
            est.buildTables();
        }
    }

}
