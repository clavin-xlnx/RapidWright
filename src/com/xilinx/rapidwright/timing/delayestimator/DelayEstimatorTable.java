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
import com.xilinx.rapidwright.util.PairUtil;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * Get the min delay between two (com.xilinx.rapidwright.timing) timing groups.
     */
    /**
     * Get the min estimated delay between two (com.xilinx.rapidwright.timing) timing groups.
     * @param timingGroup Timing group at the beginning of the route
     * @param sinkPin Timing group at the end. It must be a sinkPin
     * @return
     */
    @Override
    public short getMinDelayToSinkPin(com.xilinx.rapidwright.timing.TimingGroup timingGroup,
                                      com.xilinx.rapidwright.timing.TimingGroup sinkPin) {


//        Node tgNode = timingGroup.getLastNode();
// INT_X46Y110/IMUX_E17
// INT_X45Y109/EE2_E_BEG6


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


        return getMinDelayToSinkPin(begTg, endTg, begX, begY, endX, endY, begSide, endSide).getFirst();
        // this is taking care off in getMinDelayToSinkPin
//        // If we store both E and W sides, the size of each entry will be double.
//        // We also need 4x more entries. Storing only one side should produce a small difference
//        // because all TG but LONG can switch sides.
//        if ((begSide != TileSide.M) && (begSide != endSide)) {
//            delay += 0;
//        }
//
//        return delay;
    }

    private Pair<Short,String> getMinDelayToSinkPin(T.TimingGroup begTg, T.TimingGroup endTg,
                                      short begX, short begY, short endX, short endY, TileSide begSide, TileSide endSide) {

        assert endTg == T.TimingGroup.CLE_IN : "getMinDelayToSinkPin expects CLE_IN as the target timing group.";

        String route = "";


        List<T.TimingGroup> begTgs = new ArrayList<T.TimingGroup>() {{add(begTg);}};
        List<T.TimingGroup> endTgs = new ArrayList<T.TimingGroup>() {{add(endTg);}};

        short delay = 0;
        if ((begX == endX) && (begY == endY)) {
            // TODO: should be handle by findMinVerticalDelay
            if (begSide != endSide) {
                delay = K0.get(InterconnectInfo.Direction.LOCAL).get(GroupDelayType.PIN_BOUNCE).shortValue();
                if (verbose == -1)
                    route = "i";
            }
        } else if (begX == endX) {
            Map<T.TimingGroup,Map<T.TimingGroup,Pair<Short,String>>> delayY =
                    findMinDelayOneDirection(this::findMinVerticalDelay, begTgs, endTgs, begY, endY, begSide, endSide);
            delay = delayY.get(begTg).get(endTg).getFirst();
            if (verbose == -1)
                route = delayY.get(begTg).get(endTg).getSecond();
        } else if (begY == endY) {
            Map<T.TimingGroup,Map<T.TimingGroup,Pair<Short,String>>> delayY =
                    findMinDelayOneDirection(this::findMinHorizontalDelay, begTgs, endTgs, begX, endX, begSide, endSide);
            delay = delayY.get(begTg).get(endTg).getFirst();
            if (verbose == -1)
                route = delayY.get(begTg).get(endTg).getSecond();
        } else {
            // The key TimingGroups are used to connect between vertical and horizontal sections.
            // The direction of the key TimingGroup is not used.

            List<Pair<Short,String>> pathDelays = new ArrayList<>();
            {
                if (verbose > 0)
                    System.out.println("getMinDelayToSinkPin hor->ver");
                List<T.TimingGroup> keyTgs = ictInfo.getTimingGroup(
                        (T.TimingGroup e) -> (e.direction() == T.Direction.VERTICAL));
                Map<T.TimingGroup,Map<T.TimingGroup,Pair<Short,String>>> delay1 =
                        findMinDelayOneDirection(this::findMinHorizontalDelay, begTgs, keyTgs, begX, endX, begSide, endSide);
                Map<T.TimingGroup,Map<T.TimingGroup,Pair<Short,String>>> delay2 =
                        findMinDelayOneDirection(this::findMinVerticalDelay, keyTgs, endTgs, begY, endY, begSide, endSide);
                for (T.TimingGroup tg : keyTgs) {
                    pathDelays.add(new Pair<>(
                            (short) (delay1.get(begTg).get(tg).getFirst() + delay2.get(tg).get(endTg).getFirst()),
                            delay1.get(begTg).get(tg).getSecond() + delay2.get(tg).get(endTg).getSecond())
                    );
                }
            }

            {
                if (verbose > 0)
                    System.out.println("getMinDelayToSinkPin ver->hor");
                List<T.TimingGroup> keyTgs = ictInfo.getTimingGroup(
                        (T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL));
                Map<T.TimingGroup,Map<T.TimingGroup,Pair<Short,String>>> delay1 =
                        findMinDelayOneDirection(this::findMinVerticalDelay, begTgs, keyTgs, begY, endY, begSide, endSide);
                Map<T.TimingGroup,Map<T.TimingGroup,Pair<Short,String>>> delay2 =
                        findMinDelayOneDirection(this::findMinHorizontalDelay, keyTgs, endTgs, begX, endX, begSide, endSide);

                for (T.TimingGroup tg : keyTgs) {
                    pathDelays.add(new Pair<>(
                            (short) (delay1.get(begTg).get(tg).getFirst() + delay2.get(tg).get(endTg).getFirst()),
                            delay1.get(begTg).get(tg).getSecond() + delay2.get(tg).get(endTg).getSecond())
                    );
                }
            }
            Pair<Short,String> minEntry = Collections.min(pathDelays, new PairUtil.CompareFirst<>());
            delay = minEntry.getFirst();
        }

        // add delay of input sitepin
        return new Pair<>((short) (delay + K0.get(InterconnectInfo.Direction.INPUT).get(GroupDelayType.PINFEED)),route);
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
        // When the target is CLE_IN, there are 4 cases to reach CLE_IN.
        // Each of these nodes has an edge to dst for consistency only.
        public Object dstFarFar;
        public Object dstFarNear;
        public Object dstNearFar;
        public Object dstNearNear;
        public short  fixedDelay;
        public boolean onlyOneDst;
    }

    // Declare using implementation type ArrayList because random indexing is needed.
    // index by distance, from TG and to TG
    private ArrayList<Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>>> TgToTgVertically;
    private ArrayList<Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>>> TgToTgHorizontally;

    // use to populate route info when the distance is out of the range
    // https://stackoverflow.com/questions/1802915/java-create-a-new-string-instance-with-specified-length-and-filled-with-specif
    static private String L30 = "LLLLLLLLLLLLLLLLLLLLLLLLLLLLLL";
    static private String l30 = "llllllllllllllllllllllllllllll";

//    // Store list of resource use by one call to getMinDelayToSinkPin.
//    // This is used for testing only. Thus, it is not returned through the normal calling stack.
//    // each resource is denoted by one letter. A capital letter is for vertical.
//    private String route;


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
     * To handle back-to-back tile, each graph have 4 sinks: far-far, far-near, near-far and near-near.
     * The definition of near/far depends on which side the connection start/end and the direction of the connection.
     * If a connection is to the E and starts from E, it is said to be near-.
     * If the connection end at E side, it is said to be -far.  Overall, it is near-far.
     *
     * Assumption: "to" is either CLE_IN or TG in the other direction.
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
                    return String.format("%-12s %3d  detour", tg.name(),loc);
//                  return tg.name() + " " + loc + "  detour";
                else
                    return String.format("%-12s %3d", tg.name(),loc);
//                  return tg.name() + " " + loc;
            }
        }

        int maxDist = dist + detourDist;


        class NodeManager {

            // use this instead of pair to have names
            class Entry {
                Object node;
                Boolean isJustCreated;
                Entry(Object n, Boolean flag) {
                    node = n;
                    isJustCreated = flag;
                }
            }

            private
            Map<Short, Map<T.TimingGroup, Object>> distTypeNodemap;

            NodeManager() {
                distTypeNodemap = new HashMap();
            }

            // return the node. If it is newly created, set the flag to true.
            Entry getOrCreateNode (short loc, T.TimingGroup tg) {
                if (!distTypeNodemap.containsKey(loc)) {
                    Map<T.TimingGroup, Object> typeNodeMap = new EnumMap<>(T.TimingGroup.class);
                    distTypeNodemap.put(loc, typeNodeMap);
                }
                // create node for the expanding toTg if doesn't exist.
                if (!distTypeNodemap.get(loc).containsKey(tg)) {
                    // I can't use generic type for Object because I need to new it here.
                    Object newObj = new Object();
                    distTypeNodemap.get(loc).put(tg, newObj);
                    return new Entry(newObj,true);
                } else {
                    return new Entry(distTypeNodemap.get(loc).get(tg),false);
                }
            }

            // Don't check. If the entry exists, this call will overwrite that.
            // To avoid overwriting, guard this call with isNodeExists.
            void insertNode(short loc, T.TimingGroup tg, Object obj) {
                if (!distTypeNodemap.containsKey(loc)) {
                    Map<T.TimingGroup, Object> typeNodeMap = new EnumMap<>(T.TimingGroup.class);
                    distTypeNodemap.put(loc, typeNodeMap);
                }
                distTypeNodemap.get((short) loc).put(tg, obj);
            }

            boolean isNodeExists(short loc, T.TimingGroup tg) {
                if (!distTypeNodemap.containsKey(loc) || !distTypeNodemap.get(loc).containsKey(tg))
                    return false;
                else
                    return true;
            }

            Set<Map.Entry<Short,Map<T.TimingGroup,Object>>> getEntrySet() {
                return distTypeNodemap.entrySet();
            }
        }


        // BFS, not DFS, because getting all sinks in one call to nextTimingGroups.

        Graph<Object, TimingGroupEdge> g = new SimpleDirectedWeightedGraph<>(TimingGroupEdge.class);
        Object src = new Object();
        Object dst = new Object();
        // src doesn't need to be in NodeManager because it will not be searched for.
        g.addVertex(src);
        g.addVertex(dst);

        // to allow searching for a node during path building
        NodeManager man = new NodeManager();
        man.insertNode((short) dist, to, dst);

        // nodeNames are used in plotting the graph
        Map<Object,String> nodeNames = new HashMap<>();
        nodeNames.put(src, from.name());



        // These nodes are not in NodeManager because there are for tg which was represented with dst already.
        // There is no way to distinguished them. Also, there is no need because they are not searched for.
        boolean onlyOneDst = true;
        Object dstFarFar   = null;
        Object dstFarNear  = null;
        Object dstNearFar  = null;
        Object dstNearNear = null;
        if (to == T.TimingGroup.CLE_IN && dir == T.Direction.HORIZONTAL) {
            dstFarFar   = new Object();
            dstFarNear  = new Object();
            dstNearFar  = new Object();
            dstNearNear = new Object();
            nodeNames.put(dstFarFar, to.name() + "_FF");
            nodeNames.put(dstFarNear, to.name() + "_FN");
            nodeNames.put(dstNearFar, to.name() + "_NF");
            nodeNames.put(dstNearNear, to.name() + "_NN");

            g.addVertex(dstFarFar);
            g.addVertex(dstFarNear);
            g.addVertex(dstNearFar);
            g.addVertex(dstNearNear);
            g.addEdge(dstFarFar,   dst, new TimingGroupEdge(null, false));
            g.addEdge(dstFarNear,  dst, new TimingGroupEdge(null, false));
            g.addEdge(dstNearFar,  dst, new TimingGroupEdge(null, false));
            g.addEdge(dstNearNear, dst, new TimingGroupEdge(null, false));
        }

        boolean dbg = false;
        if (from == InterconnectInfo.TimingGroup.HORT_SINGLE && to == InterconnectInfo.TimingGroup.CLE_IN && dist == 2)
            dbg = true;

        List<WaveEntry> wave = new ArrayList<WaveEntry>() {{add(new WaveEntry(from, (short) 0, src, false));}};

        int count = 0;
        boolean reachable = false;

        while (!wave.isEmpty()) {
            List<WaveEntry> nxtWave = new ArrayList<>();
            for (WaveEntry frEntry : wave) {

                if (verbose)
                    System.out.println("wave         " + frEntry.toString());


                // Handling Back-to-back tile.
                // Hor: 1) When L arrive at the target INT tile, it can use Bounce to get to E or W right away.
                //      2) When Tg get to distance 1 from the target, it just needs
                //         - to dst FF, DI.
                //         - to dst NN, S.
                //         - to other , D
                //         An exception is if the Tg is L, it just need D for all dsts.
                // Ver: use route on the source side and consider switching to the correct side at the sink.
                //      direction of the ver route doesn't matter.
                //      if src is on different side as sink, add bounce.
                //      this should be done in lookupDelayBackToBackTile

                // generate next possible TGs
                if ((Math.abs(frEntry.loc - dist) == 1) && (dir == T.Direction.HORIZONTAL)
                    && (to == T.TimingGroup.CLE_IN)) {

                        onlyOneDst = false;

                        // D,I to dstFarFar
                        if (frEntry.tg == T.TimingGroup.HORT_LONG) {
                            String affix = "";
                            if (frEntry.detour)
                                affix += "detour_";
                            // long in the middle, thus only d
                            T.TimingGroup toD = T.TimingGroup.HORT_DOUBLE;
                            // don't share this double node with other normal cases
                            Object LDCleNode = new Object();
                            nodeNames.put(LDCleNode, "LDCleNode_" + affix + frEntry.loc);
                            g.addVertex(LDCleNode);
                            g.addEdge(frEntry.n, LDCleNode, new TimingGroupEdge(toD, frEntry.detour));

                            T.TimingGroup toS = T.TimingGroup.HORT_SINGLE;
                            // don't share this double node with other normal cases
                            Object LSCleNode = new Object();
                            nodeNames.put(LSCleNode, "LSCleNode_" + affix + frEntry.loc);
                            g.addVertex(LSCleNode);
                            g.addEdge(frEntry.n, LSCleNode, new TimingGroupEdge(toS, frEntry.detour));

                            g.addEdge(LDCleNode, dstFarFar, new TimingGroupEdge(to, false));
                            g.addEdge(LDCleNode, dstNearFar, new TimingGroupEdge(to, false));
                            g.addEdge(LDCleNode, dstFarNear, new TimingGroupEdge(to, false));
                            g.addEdge(LDCleNode, dstNearNear, new TimingGroupEdge(to, false));
                            if (frEntry.detour) {
                                g.addEdge(LSCleNode, dstFarFar, new TimingGroupEdge(to, false));
                                // some how I don't see this in practice.
                                //g.addEdge(LSCleNode, dstNearFar, new TimingGroupEdge(to, false));
                            } else {
                                // some how I don't see this in practice.
                                //g.addEdge(LSCleNode, dstFarNear, new TimingGroupEdge(to, false));
                                g.addEdge(LSCleNode, dstNearNear, new TimingGroupEdge(to, false));
                            }
                        } else {
                            {

                                T.TimingGroup toTg = T.TimingGroup.HORT_DOUBLE;
                                short loc = (short) (frEntry.loc + (frEntry.detour ? -toTg.length() : toTg.length()));
                                NodeManager.Entry manEntry = man.getOrCreateNode(loc, toTg);
                                if (manEntry.isJustCreated)
                                    g.addVertex(manEntry.node);
                                g.addEdge(frEntry.n, manEntry.node, new TimingGroupEdge(toTg, false));


                                T.TimingGroup toTg1 = T.TimingGroup.BOUNCE;
                                NodeManager.Entry manEntry1 = man.getOrCreateNode((short) (loc + toTg.length()), toTg1);
                                if (manEntry1.isJustCreated)
                                    g.addVertex(manEntry1.node);
                                g.addEdge(manEntry.node, manEntry1.node, new TimingGroupEdge(toTg1, false));
                                g.addEdge(manEntry1.node, dstFarFar, new TimingGroupEdge(to, false));
                            }
                            // S to dstNearNear
                            {
                                T.TimingGroup toTg = T.TimingGroup.HORT_SINGLE;
                                NodeManager.Entry manEntry = man.getOrCreateNode(
                                        (short) (frEntry.loc + (frEntry.detour ? -toTg.length() : toTg.length())), toTg);
                                if (manEntry.isJustCreated)
                                    g.addVertex(manEntry.node);
                                g.addEdge(frEntry.n, manEntry.node, new TimingGroupEdge(toTg, false));
                                g.addEdge(manEntry.node, dstNearNear, new TimingGroupEdge(to, false));
                            }

                            // D to dstFarNear and dstNearFar
                            {
                                T.TimingGroup toTg = T.TimingGroup.HORT_DOUBLE;
                                NodeManager.Entry manEntry = man.getOrCreateNode(
                                        (short) (frEntry.loc + (frEntry.detour ? -toTg.length() : toTg.length())), toTg);
                                if (manEntry.isJustCreated)
                                    g.addVertex(manEntry.node);
                                g.addEdge(frEntry.n, manEntry.node, new TimingGroupEdge(toTg, false));
                                g.addEdge(manEntry.node, dstFarNear, new TimingGroupEdge(to, false));
                                g.addEdge(manEntry.node, dstNearFar, new TimingGroupEdge(to, false));
                            }
                        }

                        reachable = true;

                } else if ((frEntry.loc == dist) && ((frEntry.tg == T.TimingGroup.HORT_LONG) || (frEntry.tg == T.TimingGroup.HORT_QUAD)) &&
                        (dir == InterconnectInfo.Direction.HORIZONTAL) && (to == T.TimingGroup.CLE_IN)) {

                    onlyOneDst = false;

                    // l is in the center and can connect to either side
                    T.TimingGroup toTg = T.TimingGroup.BOUNCE;
                    NodeManager.Entry manEntry = man.getOrCreateNode((short) (frEntry.loc+toTg.length()), toTg);
                    if (manEntry.isJustCreated)
                        g.addVertex(manEntry.node);
                    g.addEdge(frEntry.n, manEntry.node, new TimingGroupEdge(toTg, false));
                    g.addEdge(manEntry.node, dstFarFar, new TimingGroupEdge(to, false));
                    g.addEdge(manEntry.node, dstFarNear, new TimingGroupEdge(to, false));
                    g.addEdge(manEntry.node, dstNearFar, new TimingGroupEdge(to, false));
                    g.addEdge(manEntry.node, dstNearNear, new TimingGroupEdge(to, false));

                    reachable = true;

                } else {
                    // don't filter with length because need to handle detour
                    List<T.TimingGroup> nxtTgs;
                    if (frEntry.loc == dist) {
                        nxtTgs = ictInfo.nextTimingGroups(frEntry.tg, (T.TimingGroup e) ->
                                (e.direction() == dir) || (e == to) || (e.direction() == InterconnectInfo.Direction.LOCAL));
                    } else {
                        nxtTgs = ictInfo.nextTimingGroups(frEntry.tg, (T.TimingGroup e) -> (e.direction() == dir));
                    }

                    // Add TG to graph if it is not out of the detour limit. Need to handle when frEntry is a detour.
                    for (T.TimingGroup toTg : nxtTgs) {

                        if (verbose)
                            System.out.print(String.format("  toTg       %-12s", toTg.name()));


                        // List possible branching for the toTg. When extending a detour branch, toTg can continue
                        // on the detour direction of revesing back.
                        List<Pair<Short, Boolean>> locs = new ArrayList<>();

                        // add detour direction to locs
                        if (frEntry.detour) {
                            // TODO: consider both going forward and backward
                            short tloc = (short) (frEntry.loc - toTg.length());
                            // allow only one overshoot
                            if (tloc >= dist) {
                                locs.add(new Pair<>(tloc, true));
//                            if (verbose)
//                                System.out.print("    add to locs " + tloc + " : " + locs.size());
                            }
                        }

                        // add non-detour direction to locs
                        {
                            short tloc = (short) (frEntry.loc + toTg.length());
                            if ((toTg == to) && (frEntry.loc == dist) && (frEntry.tg.direction() != toTg.direction())) {
                                locs.add(new Pair<>(frEntry.loc, false));
//                            if (verbose)
//                                System.out.print("    add to locs " + frEntry.loc + " : " + locs.size());
                            }
                            // Ignore too large overshoot
                            else if (tloc <= maxDist) {
                                locs.add(new Pair<>(tloc, false));
//                            if (verbose)
//                                System.out.print("    add to locs " + tloc + " : " + locs.size());
                            }
                        }


                        if (verbose)
                            System.out.println("  locs " + locs.toString());

                        // add TG in locs to graph. set dst node if reachable during this expansion.
                        for (Pair<Short, Boolean> loc_pair : locs) {
                            short loc = loc_pair.getFirst();


                            // create node for the expanding toTg if doesn't exist.
                            NodeManager.Entry manEntry = man.getOrCreateNode(loc, toTg);
                            if (manEntry.isJustCreated) {
                                g.addVertex(manEntry.node);

                                WaveEntry newEntry = new WaveEntry(toTg, loc, manEntry.node, loc > dist);
                                nxtWave.add(new WaveEntry(toTg, loc, manEntry.node, loc > dist));

                                if (verbose) {
//                                System.out.println("  new Tg " + toTg.name() + " at loc " + loc);
                                    System.out.println("    Add node " + newEntry.toString());
                                }
                            }

                            if ((toTg == to) && (loc == dist)) {
                                reachable = true;
                            }

                            g.addEdge(frEntry.n, manEntry.node, new TimingGroupEdge(toTg, loc_pair.getSecond()));

                            if (verbose) {
//                            System.out.println("  Add edge " + toTg.name());
//                            System.out.println();
                            }
                        }
                    }
                }
            }

            if (verbose) {
                System.out.println(count + " nxtWave of size " + nxtWave.size());
                System.out.println("\n");
                count++;
            }

            wave = nxtWave;
        }

        if (!reachable)
            throw new RuntimeException("Error: Destination timing group is not reachable.");



        GetTermNodes<Graph<Object, TimingGroupEdge>,Object, List<Object>> getTermNodes =
        (ig, dstNode) -> {
            List<Object> termNodes = new ArrayList<>();
            for (Object n : ig.vertexSet()) {
                // node with no fanout
                if ((ig.outDegreeOf(n) <= 0) && (n != dstNode)) {
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


        // Trim out dangling vertices that is not the destination
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

            for (Map.Entry<Short, Map<T.TimingGroup,Object>> forLoc : man.getEntrySet()) {
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
            // to make the end node visible in a large graph
            graphVizPrintStream.println("  " + to + "_" + dist +"[shape=box,style=filled,color=\".7 .3 1.0\"];");
            graphVizPrintStream.println("}");
            graphVizPrintStream.close();
        }


        DelayGraphEntry res = new DelayGraphEntry();
        res.g = g;
        res.src = src;
        res.dst = dst;
        res.dstFarFar = dstFarFar;
        res.dstFarNear = dstFarNear;
        res.dstNearFar = dstNearFar;
        res.dstNearNear = dstNearNear;
        res.onlyOneDst = onlyOneDst;
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


    // ----------------------------------------------------------------------------------
    // -----------------------   Methods for computing min delay ------------------------

    /**
     *
     * @param fromTgs
     * @param toTgs
     * @param s
     * @param t
     * @return
     */
    private Map<T.TimingGroup,Map<T.TimingGroup,Pair<Short,String>>> findMinDelayOneDirection(findMinDelayInterface computeOnDir,
            List<T.TimingGroup> fromTgs, List<T.TimingGroup> toTgs, short s, short t, TileSide begSide, TileSide endSide) {

        short dist = (short) (t - s);

        // separate building this to declutter the compute logic below.
        Map<T.TimingGroup,Map<T.TimingGroup,Pair<Short,String>>> res = new HashMap<>();
        for (T.TimingGroup fromTg : fromTgs) {
            Map<T.TimingGroup, Pair<Short,String>> resToTgs = new HashMap<>();
            res.put(fromTg, resToTgs);
        }


        List<Short> pathDelays = new ArrayList<>();
        for (T.TimingGroup fromTg : fromTgs) {
            for (T.TimingGroup toTg : toTgs) {
                res.get(fromTg).put(toTg,computeOnDir.execute(fromTg, toTg, s, dist, begSide, endSide));
            }
        }

        return res;
    }

    @FunctionalInterface
    private interface findMinDelayInterface<T extends InterconnectInfo> {
        public Pair<Short,String> execute(T.TimingGroup s, T.TimingGroup t, short sY, short distY, TileSide begSide, TileSide endSide);
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
    private Pair<Short,String> findMinVerticalDelay(T.TimingGroup s, T.TimingGroup t, short loc, short dist, TileSide begSide, TileSide endSide) {

        short limit = height;
        T.TimingGroup extendingTg = T.TimingGroup.VERT_LONG;
        ArrayList<Map<T.TimingGroup, Map<T.TimingGroup, DelayGraphEntry>>> table = TgToTgVertically;

        Pair<Short,Pair<Boolean,String>> res = findMinDelay(table, limit, extendingTg, s, t, loc, dist, begSide, endSide,
                InterconnectInfo.Direction.VERTICAL);
        if ((begSide != endSide) && !res.getSecond().getFirst()) {
            short bounceDelay = (short) calcTimingGroupDelay(T.TimingGroup.BOUNCE, (short) 0, (short) 0, 0d);
            String route = null;
            if (verbose == -1) {
                route = res.getSecond().getSecond();
                route = route.replace('-', T.TimingGroup.BOUNCE.abbr());
                route = route + "-";
            }
            return new Pair<>((short) (res.getFirst() + bounceDelay),route);
        } else {
            return new Pair<>(res.getFirst(),res.getSecond().getSecond());
        }
    }
    /**
     * Find min delay of horizontal route between the given source and sink.
     * see findMinVerticalDelay for descriptions of parameters
     */
    private Pair<Short,String> findMinHorizontalDelay(T.TimingGroup s, T.TimingGroup t, short loc, short dist, TileSide begSide, TileSide endSide) {

        short limit = width;
        T.TimingGroup extendingTg = T.TimingGroup.HORT_LONG;
        ArrayList<Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>>> table = TgToTgHorizontally;

        Pair<Short,Pair<Boolean,String>> res = findMinDelay(table, limit, extendingTg, s, t, loc, dist, begSide, endSide,
                InterconnectInfo.Direction.HORIZONTAL);
        return new Pair<>(res.getFirst(), res.getSecond().getSecond());
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
    private Pair<Short,Pair<Boolean,String>> findMinDelay(
            ArrayList<Map<T.TimingGroup, Map<T.TimingGroup, DelayGraphEntry>>> table, short limit,
            T.TimingGroup extendingTg, T.TimingGroup s, T.TimingGroup t, short loc, short dist,
            TileSide begSide, TileSide endSide, InterconnectInfo.Direction dir) {

        if (verbose > 2)
            System.out.println("      findMinDelay from " + s.name() + " to " + t.name() + " loc " + loc + " dist " + dist);

        boolean isBackward = dist < 0;
        dist = (short) Math.abs(dist);



        if (dist < limit) {
            Pair<Double,Pair<Boolean,String>> res = lookupDelayBackToBackTile(table, s, t, dist, loc, begSide, endSide, isBackward, dir);
            return new Pair<Short,Pair<Boolean,String>>(res.getFirst().shortValue(),res.getSecond());
        } else {
            // table is stored with TG going the other direction, need to get the equivalent of extendingTg

            List<T.TimingGroup> keyTgs = ictInfo.getTimingGroup((T.TimingGroup e) ->
                    (e.direction() != extendingTg.direction()) && (e.type() == extendingTg.type()));
            T.TimingGroup equivTg = keyTgs.get(0);

            // width and height must cover distance that use at least one long.
            List<Pair<Short,String>> pathDelays = new ArrayList<>();
            int gap = dist - 2 * (limit-1);
            Double k = Math.ceil(1.0*gap / extendingTg.length());
            // start : i + k*L = h+g => i = h+g-k*l
            // last  : i = height
            for (int i = (int) (limit-1 + gap - k * extendingTg.length()); i < limit; i++) {

                if (verbose > 2)
                    System.out.println("      consider extension at " + i);

                Pair<Double,Pair<Boolean,String>> beginning = lookupDelayBackToBackTile(table, s, equivTg, (short) i, loc, begSide, endSide, isBackward, dir);
                short delayBeginning = beginning.getFirst().shortValue();

                if (verbose > 3)
                    System.out.println("      lookup begin dist " + i + " at " + loc + " del " + delayBeginning);

                short distForEnding = (short) (2*(limit-1) + gap - (i + k*extendingTg.length()));
                short locForEnding  = (short) (i+k*extendingTg.length());
                Pair<Double,Pair<Boolean,String>> ending = lookupDelayBackToBackTile(table, extendingTg, t, distForEnding,
                        locForEnding, begSide, endSide, isBackward, dir);
                short delayEnding = ending.getFirst().shortValue();

                if (verbose > 3)
                    System.out.println("      lookup end dist " + distForEnding + " at " + locForEnding + " del " + delayEnding);

                // delay of each extending tg is location dependent. Thus, need to recompute.
                short extendingDelayTotal = 0;
                for (int j = 0; j < k; j++) {
                    short begLoc = (short) (i + j * extendingTg.length());
                    short endLoc = (short) (begLoc + extendingTg.length());
                    if (verbose > 4)
                        System.out.print("        lookup for extention from " + begLoc + " to " + endLoc);

                    short extendingDelay = (short) calcTimingGroupDelay(extendingTg, begLoc, endLoc, 0d);
                    extendingDelayTotal += extendingDelay;

                    if (verbose > 4 && verbose < 6)
                        System.out.println("  del " + extendingDelay);
                }
                String route = null;
                if (verbose == -1) {
                    String middle = null;
                    if (dir == T.Direction.VERTICAL)
                        middle = L30.substring(0,k.intValue());
                    else
                        middle = l30.substring(0,k.intValue());
                    route = beginning.getSecond().getSecond() + middle + ending.getSecond().getSecond();
                }
                short dlyTotal = (short) (delayBeginning + delayEnding + extendingDelayTotal);
                pathDelays.add(new Pair<>(dlyTotal,route));
                if (verbose > 3) {
                    System.out.println("        lookup for extention del " + extendingDelayTotal);
                    System.out.println("        lookup total del " + dlyTotal);
                }
            }

            Pair<Short,String> minEntry = Collections.min(pathDelays, new PairUtil.CompareFirst<>());
            // extend by LONG, thus the flag is always true
            return new Pair<>(minEntry.getFirst(),new Pair<>(true,minEntry.getSecond()));
        }
    }

    /**
     * Call lookupDelay with correct dst node based on which side of the back-to-back tiles of src and dst.
     * @param table
     * @param s
     * @param t
     * @param dist
     * @param dAtSource
     * @param begSide
     * @param endSide
     * @param isBackward
     * @return
     */
    private Pair<Double,Pair<Boolean,String>> lookupDelayBackToBackTile(ArrayList<Map<T.TimingGroup, Map<T.TimingGroup, DelayGraphEntry>>> table,
            T.TimingGroup s, T.TimingGroup t,short dist, double dAtSource,
            TileSide begSide, TileSide endSide, boolean isBackward, InterconnectInfo.Direction dir) {

        DelayGraphEntry  sentry = table.get(dist).get(s).get(t);
        Object dst = null;
        if (t == InterconnectInfo.TimingGroup.CLE_IN && !sentry.onlyOneDst) {
            if (begSide == TileSide.E && endSide == TileSide.E)
                if (isBackward)
                    dst = sentry.dstFarNear;
                else
                    dst = sentry.dstNearFar;
            else if (begSide == TileSide.E && endSide == TileSide.W)
                if (isBackward)
                    dst = sentry.dstFarFar;
                else
                    dst = sentry.dstNearNear;
            else if (begSide == TileSide.W && endSide == TileSide.E)
                if (isBackward)
                    dst = sentry.dstNearNear;
                else
                    dst = sentry.dstFarFar;
            else if (begSide == TileSide.W && endSide == TileSide.W)
                if (isBackward)
                    dst = sentry.dstNearFar;
                else
                    dst = sentry.dstFarNear;
            else
                dst = sentry.dst;
        } else {
            dst = sentry.dst;
        }

        return lookupDelay(sentry.g, sentry.src, dst, dAtSource, isBackward, dir);
    }


    /**
     * Find the min delay among all paths within the given graph
     * @param ig Graph representing all paths between src and dst
     * @param src Source for the path
     * @param dst Destination for th epath
     * @param dAtSource Coordinate of the source to evaluate the delay
     * @param isBackward Indicate that the direction of source to sink is going left or down
     * @return a pair of the delay of the connection in ps and a flag whether the path contain Long
     */
    private Pair<Double,Pair<Boolean,String>> lookupDelay(Graph<Object, TimingGroupEdge> ig, Object src, Object dst,
                              double dAtSource, boolean isBackward, InterconnectInfo.Direction dir) {

        Pair<Double,Boolean> res = DijkstraWithCallbacks.findMinWeightBetween(ig, src, dst, dAtSource,
                // ExamineEdge. Update edge weight which depend on the beginning loc, length and direction of the TG
                (g, u, e, loc, dly) -> {g.setEdgeWeight(e,calcTimingGroupDelayOnEdge(e, u, dst, loc, dly, isBackward, dir));},
                // DiscoverVertex. Propagate location at the beginning loc of a timing group edge.
                (g, u, e, loc, dly) -> {return discoverVertex(e, loc, dly, isBackward);},
                (g, u, e, dly) -> {return updateVertex(e, dly, isBackward);},
                (e) -> {return isSwitchingSide(e);}
        );

        String route = "";

        if (verbose > 5 || verbose == -1) {
            if (verbose > 5)
                System.out.println("\n\nTracing for the path:");
            org.jgrapht.GraphPath<Object, TimingGroupEdge> minPath =
                    DijkstraWithCallbacks.findPathBetween(ig, src, dst, dAtSource,
                    (g, u, e, loc, dly) -> {g.setEdgeWeight(e,calcTimingGroupDelayOnEdge(e,u, dst, loc, dly, isBackward, dir));},
                    (g, u, e, loc, dly) -> {return discoverVertex(e, loc, dly, isBackward);},
                    (g, u, e, dly) -> {return updateVertex(e, dly, isBackward);}
            );

            if (verbose == -1) {
                for (TimingGroupEdge e : minPath.getEdgeList()) {
                    route += e.getTimingGroup().abbr();
                }
            } else {
                System.out.println("\nPath with min delay: " + ((short) minPath.getWeight()) + " ps");
                System.out.println("\nPath details:");
                System.out.println(minPath.toString().replace(",", ",\n") + "\n");
            }
        }
        Pair<Boolean,String> info = new Pair<>(res.getSecond(),route);
        return new Pair<>(res.getFirst(),info);
    }




    // -----------------------   Methods to help testing ------------------------

    void testCases() {
//        short delay_1 = getMinDelayToSinkPin(T.TimingGroup.CLE_OUT, T.TimingGroup.CLE_IN, (short) 10, (short) 10, (short) 10, (short) 20);
//        System.out.println("delay " + delay_1);
    }

    // use sx,tx,sy,ty order to match testcase
    void testOne( int sx, int tx, int sy, int ty, String sSide, String tSide) {
        verbose = 6;
        Pair<Short,String> res = getMinDelayToSinkPin(T.TimingGroup.CLE_OUT, T.TimingGroup.CLE_IN,
                (short) sx, (short) sy, (short) tx, (short) ty, TileSide.valueOf(sSide), TileSide.valueOf(tSide));
        System.out.println(sx + " " + tx + " " + sy + " " + ty + " " + res.getFirst());

        System.out.println("path " + res.getSecond());
    }

    void testCases(String fname) {

//        zeroDistArrays();
        verbose = -1; // -1 for testing

        class ErrorComputer {

            private int cnt;
            private int minErr;
            private int maxErr;
            private int sumErr;

            ErrorComputer() {
                cnt = 0;
                minErr = 0;
                cnt = 0;
                minErr = Integer.MAX_VALUE;
                maxErr = 0;
                sumErr = 0;
            }


            void insert(int err) {
                minErr = Math.min(minErr, err);
                maxErr = Math.max(maxErr, err);
                sumErr += err;
                cnt++;
            }

            String report(String prefix) {
                float avgErr = sumErr/cnt;
                return prefix + " min " + minErr + " max " + maxErr + " avg " + avgErr + " cnt " + cnt;
            }
        }

        Pattern filename = Pattern.compile("^(\\w+)");
        Matcher matchfn = filename.matcher(fname);
        String oname = null;
        String ename = null;
        if (matchfn.find()) {
            oname = matchfn.group(1) + ".out";
            ename = matchfn.group(1) + ".exc";
        }
        System.out.println("Running test cases from " + fname);
        System.out.println("Write output to " + oname);


        Set<Pair<String,String>> exceptionSet = new HashSet<>();
        try {
            Pattern pattern = Pattern.compile("^([\\w-]+)\\s+([\\w-]+)");
            BufferedReader reader = new BufferedReader(new FileReader(ename));
            String line = reader.readLine();
            while (line != null) {
                if ((line.length() > 0) && (line.indexOf("#") != 0)) {
                    Matcher matcher = pattern.matcher(line);

                    if (matcher.find()) {
                        String estRoute = matcher.group(1);
                        String refRoute = matcher.group(2);
                        exceptionSet.add(new Pair<>(estRoute,refRoute));
                    }
                }
                line = reader.readLine();
            }
        } catch (IOException e) {
            System.out.println("Can't open file " + ename + " for read.");
            e.printStackTrace();
        }


        ErrorComputer errToTg     = new ErrorComputer();
        ErrorComputer errToTgExc  = new ErrorComputer();
        ErrorComputer errToRt     = new ErrorComputer();
        ErrorComputer errToRtExc  = new ErrorComputer();

        try {
            FileWriter outfile = new FileWriter(oname);

            Pattern pattern = Pattern.compile("^(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+\\w+\\s+(\\w+)\\s+\\w+\\s+(\\w+)\\s+[-0-9.%]+\\s+[-0-9.]+\\s+([0-9.]+)\\s+([0-9.]+)\\s+(\\w+)");
//            Pattern pattern = Pattern.compile("^(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+\\w+\\s+\\w+\\s+[0-9.%]+\\s+[0-9.]+\\s+([0-9.]+)\\s+([0-9.]+)");
            try {
                BufferedReader reader = new BufferedReader(new FileReader(fname));
                String line = reader.readLine();
                int cnt = 1;
                while (line != null) {
                    if ((line.length() > 0) && (line.indexOf("#") != 0)) {
                        Matcher matcher = pattern.matcher(line);
                        short sx, sy, tx, ty;
                        short tgDelay, rtDelay;
                        String sSide, tSide, refRoute;
                        if (matcher.find()) {
                            sx = Short.parseShort(matcher.group(1));
                            tx = Short.parseShort(matcher.group(2));
                            sy = Short.parseShort(matcher.group(3));
                            ty = Short.parseShort(matcher.group(4));

                            sSide = matcher.group(5);
                            tSide = matcher.group(6);

                            tgDelay = (short) Float.parseFloat(matcher.group(7));
                            rtDelay = (short) Float.parseFloat(matcher.group(8));
                            refRoute = matcher.group(9);

                            // TBD
                            System.out.println(String.format("%3d %3d %3d %3d   %4d %4d", sx, tx, sy, ty, tgDelay, rtDelay));

                            Pair<Short,String> res = getMinDelayToSinkPin(T.TimingGroup.CLE_OUT, T.TimingGroup.CLE_IN, sx, sy, tx, ty,
                                    TileSide.valueOf(sSide), TileSide.valueOf(tSide));
                            short est = res.getFirst();


                            boolean exceptionCase = false;
                            if (exceptionSet.contains(new Pair<>(res.getSecond(),refRoute))) {
                                exceptionCase = true;
                            }

                            String note = " ";
                            if (exceptionCase)
                                note = "*";

                            String delayResult = String.format("%s %3d %3d %3d %3d   %4d %4d %4d", note, sx, tx, sy, ty, est, tgDelay, rtDelay);
                            System.out.println(delayResult);
                            outfile.write(delayResult);

                            // error compare to tgDelay
                            int err1 = est - tgDelay;
                            float ept1 = 100 * err1 / tgDelay;
                            String errTgDelay = String.format("%5d %5.1f%s", err1, ept1, "%");
                            outfile.write(errTgDelay);
                            // error compare to rtDelay
                            int err2 = est - rtDelay;
                            float ept2 = 100 * err2 / rtDelay;
                            String errRtDelay = String.format("%5d %5.1f%s", err2, ept2, "%");
                            outfile.write(errRtDelay);
                            outfile.write("  " + res.getSecond() + "                   : " + line);
                            outfile.write(System.lineSeparator());


                            if (!exceptionCase) {
                                errToTgExc.insert(err1);
                                errToRtExc.insert(err2);
                            }
                            errToTg.insert(err1);
                            errToRt.insert(err2);
                        }
                    } else {
                        System.out.println("Find comment at line " + cnt);
                    }
                    cnt++;
                    line = reader.readLine();
                }

                // print summary
                outfile.write(System.lineSeparator());
                outfile.write(System.lineSeparator());

                // compare to tgDelay
                System.out.println(errToTg.report("Compare to tgDelay"));
                outfile.write(errToTg.report("Compare to tgDelay"));
                outfile.write(System.lineSeparator());
                // compare to rtDelay
                System.out.println(errToRt.report("Compare to rtDelay"));
                outfile.write(errToRt.report("Compare to rtDelay"));
                outfile.write(System.lineSeparator());


                // exclude exception cases
                // compare to tgDelay
                System.out.println(errToTgExc.report("Compare to tgDelay exc "));
                outfile.write(errToTgExc.report("Compare to tgDelay exc "));
                outfile.write(System.lineSeparator());
                // compare to rtDelay
                System.out.println(errToRtExc.report("Compare to rtDelay exc "));
                outfile.write(errToRtExc.report("Compare to rtDelay exc "));
                outfile.write(System.lineSeparator());

                System.out.println("number test cases processed " + cnt);
            } catch (IOException e) {
                System.out.println("Can't open file " + fname + " for read.");
                e.printStackTrace();
            }

            outfile.close();
        } catch (IOException e) {
            System.out.println("Can't open file " + oname + " for write.");
            e.printStackTrace();
        }





    }

    void testLookup() {
        // This is don't to allow automatic check for forward delay == backward delay
        zeroDistArrays();
        int cnt = 0;

        // lookup hor in table
        cnt = testLookup(1,width,0,1, cnt);
        // lookup hor out table
        cnt = testLookup(2*(width -1),numCol,0,1, cnt);
        // lookup ver in table
        cnt = testLookup(0,1,1,height, cnt);
        // lookup ver out table
        cnt = testLookup(0,1,2*(height-1), numRow, cnt);
        // lookup angle in table
        cnt = testLookup(1,width,1,height,cnt);
        // lookup angle out table
        cnt = testLookup(2*(width-1), numCol,2*(height-1), numRow,cnt);

        System.out.println("\n---------------------------------\n");
        System.out.println("The total number of tests are " + cnt);
    }

    int  testLookup (int begWidth, int endWidth, int begHeight, int endHeight, int cnt) {
        System.out.println("\n**************************************************\n");
        System.out.println("testLookupAngleInTable " + begWidth + " " + endWidth + " " + begHeight + " " + endHeight);

        short y = 0;
        for (int i = begWidth; i < endWidth; i++) {
            for (int j = begHeight; j < endHeight; j++, cnt += 2) {
                if (verbose > 0) System.out.println("forward");
                Pair<Short,String> res_1 = getMinDelayToSinkPin(T.TimingGroup.CLE_OUT, T.TimingGroup.CLE_IN,
                        (short) 0, (short) 0, (short) i, (short) j, TileSide.valueOf("E"), TileSide.valueOf("E"));
                short delay_1 = res_1.getFirst();
                if (verbose > 0) System.out.println("backward");
                Pair<Short,String> res_2 = getMinDelayToSinkPin(T.TimingGroup.CLE_OUT, T.TimingGroup.CLE_IN,
                        (short) i, (short) j, (short) 0, (short) 0, TileSide.valueOf("E"), TileSide.valueOf("E"));
                short delay_2 = res_2.getFirst();
                System.out.print("compare forward and backward at dist "+i+" "+j+" : "+delay_1+" "+delay_2);
//            assert delay_1 == delay_2 : "Forward and backward delays differ";
                if (delay_1 == delay_2)
                    System.out.println();
                else
                    System.out.println(" ****** differ *******");
            }
        }
        return cnt;
    }



    public static <GraphPath> void main(String args[]) {

        Device device = Device.getDevice("xcvu3p-ffvc1517");
        InterconnectInfo ictInfo = new InterconnectInfo();
        DelayEstimatorTable est = new DelayEstimatorTable(device,ictInfo, 0);

        //est.testLookup();
        // vert in table
//        est.testCases("est_dly_ref_81_81_121_139_E_E.txt");
//        est.testCases("est_dly_ref_81_81_121_139_E_W.txt");
        // hor in table
//        est.testCases("est_dly_ref_44_53_80_80_E_E.txt");
//        est.testCases("est_dly_ref_44_53_80_80_E_W.txt");
//        est.testCases("est_dly_ref_44_53_80_80_W_E.txt");
        est.testCases("est_dly_ref_44_53_80_80_W_W.txt");
        // diag in table
//        est.testCases("est_dly_ref_44_53_121_139_E_E.txt");

//        est.testOne(53,43,138,138,"E","E");
//        est.testOne(44,45,121,125,"E","E");

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
            double dAtSource = 3.0d;
            short  dist      = 7;
            short  detour    = 7;
            DelayGraphEntry sentry = est.listPaths(InterconnectInfo.TimingGroup.CLE_OUT, InterconnectInfo.TimingGroup.CLE_IN, InterconnectInfo.Direction.HORIZONTAL, dist, detour, true, true);
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

    }

}

//TODO: 1) E/W,  2) test form non CLE_out.











