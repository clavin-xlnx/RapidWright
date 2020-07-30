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
import java.util.Arrays;
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

//        assert width < ictInfo.minTableWidth() :
//                "DelayEstimatorTable expects larger custom table width.";
//        assert width < ictInfo.minTableHeight() :
//                "DelayEstimatorTable expects larger custom table height.";

        this.width   = width;
        this.height  = height;
        this.extendedWidth  = (short) (width  + 2*horizontalDetourDistance);
        this.extendedHeight = (short) (height + 2*verticalDetourDistance);
        short left  = horizontalDetourDistance;
        short right = (short) (left + width);
        short bot   = verticalDetourDistance;
        short top   = (short) (bot + height);
        this.botLeft  = new Pair<>(left, bot);
        this.botRight = new Pair<>(right, bot);
        this.topLeft  = new Pair<>(left, top);
        this.topRight = new Pair<>(right, top);

        build();
    }
    DelayEstimatorTable(Device device, T ictInfo, int verbose) {
        super(device, ictInfo, verbose);
        this.width   = ictInfo.minTableWidth();
        this.height  = ictInfo.minTableHeight();
        build();
    }
    DelayEstimatorTable(Device device, T ictInfo, int verbose, boolean build) {
        super(device, ictInfo, verbose);
        this.width   = ictInfo.minTableWidth();
        this.height  = ictInfo.minTableHeight();
        if (build) {
            build();
        }
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
        T.TileSide begSide = T.TileSide.E;
        short endX = 10;
        short endY = 15;
        T.TileSide endSide = T.TileSide.E;
        // end must always be CLE_IN,
        T.TimingGroup endTg = T.TimingGroup.CLE_IN;
        T.TimingGroup begTg = T.TimingGroup.CLE_OUT;

return begX;
//        return getMinDelayToSinkPin(begTg, endTg, begX, begY, endX, endY, begSide, endSide).getFirst();
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


    @Override
    public boolean load(String filename) {
        return true;
    }


    @Override
    public boolean store(String filename) {
        return true;
    }

    private short width;
    private short height;
    private short extendedWidth;
    private short extendedHeight;

    private short horizontalDetourDistance = 2;
    private short verticalDetourDistance   = 4;

    private Pair<Short,Short> topLeft,topRight,botLeft,botRight;
    ResourceGraphBuilder rgBuilder;


    // TODO note the area used by resourceGraph and tables


    // Store tables for each dist and tg to speed up on-line delay computation
    // indexed by distane x, y, source tg and target tg, respectively
    // Don't reuse entities from resourceGraph because those can be cleaned up.
    private ArrayList<ArrayList<Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>>>> tables;

    // temp to be deleted
    private Graph<Object, TimingGroupEdge> g;

    void build() {
        rgBuilder = new ResourceGraphBuilder(extendedWidth, extendedHeight, 0);
        rgBuilder.plot("rggraph.dot");
        g = rgBuilder.getGraph();
//        initTables();
//        trimTables();
//        cleanup();
    }


    class ResourceGraphBuilder {
        NodeManager nodeMan;
        int verboseLevel;

        ResourceGraphBuilder(int width, int height, int verboseLevel) {
            this.verboseLevel = verboseLevel;
            // use the beginning of a TG as loc
            nodeMan = new NodeManager();
            buildResourceGraph();
        }

        /**
         * Maintain a set of nodes and their name to
         * 1) encapsulate crate and lookup of nodes during graph building,
         * 2) provide nodes name during graph plotting.
         * Note that some node that do not need the above two operations may not be in this manager.
         * Can't use generic type for node because need new of that type.
         */
        // need to handle name as well
        class NodeManager {

            // An complete resource graph within a given rectangle region
            private Graph<Object, TimingGroupEdge> g;

            // <loc <tg, node>>
            //                      x  , y     , ori         , side
            private Map<Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>>, Map<T.TimingGroup, Object>> distTypeNodemap;

            Object getNode(T.TimingGroup tg, short x, short y, T.Orientation orientation, T.TileSide side) {
                Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> loc = new Pair<>(new Pair<>(x, y), new Pair<>(orientation, side));
                if (!distTypeNodemap.containsKey(loc)) {
                    return null;
                } else {
                    return distTypeNodemap.get(loc).get(tg);
                }
            }

            NodeManager() {
                distTypeNodemap = new HashMap();
                g = new SimpleDirectedWeightedGraph<>(TimingGroupEdge.class);
            }

            void connectNodes(short i, short j, short m, short n, T.Orientation frOrientation, T.TileSide frSide,
                              T.Orientation toOrientation, T.TileSide toSide, T.TimingGroup frTg, T.TimingGroup toTg) {
                Object frNode = getOrCreateNode(i, j, frOrientation, frSide, frTg);
                Object toNode = getOrCreateNode(m, n, toOrientation, toSide, toTg);
                g.addEdge(frNode, toNode, new TimingGroupEdge(frTg, m < i || n < j));
            }

            // return the node. If it is newly created, set the flag to true.
            Object getOrCreateNode(short x, short y, T.Orientation orientation, T.TileSide side, T.TimingGroup tg) {
                Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> loc = new Pair<>(new Pair<>(x, y), new Pair<>(orientation, side));
                if (!distTypeNodemap.containsKey(loc)) {
                    Map<T.TimingGroup, Object> typeNodeMap = new EnumMap<>(T.TimingGroup.class);
                    distTypeNodemap.put(loc, typeNodeMap);
                }
                // create node for the expanding toTg if doesn't exist.
                if (!distTypeNodemap.get(loc).containsKey(tg)) {
                    // I can't use generic type for Object because I need to new it here.
                    Object newObj = new Object();
                    g.addVertex(newObj);
                    distTypeNodemap.get(loc).put(tg, newObj);
                    return newObj;
                } else {
                    return distTypeNodemap.get(loc).get(tg);
                }
            }

            //            Set<Map.Entry<Pair<Pair<Short, Short>, Pair<T.Orientation,T.TileSide>>, Map<T.TimingGroup, Object>>> getEntrySet() {
//                return distTypeNodemap.entrySet();
//            }
            void plot(String fname) {
                String dotFileName = fname;
                PrintStream graphVizPrintStream = null;
                try {
                    graphVizPrintStream = new PrintStream(dotFileName);
                } catch (FileNotFoundException e1) {
                    e1.printStackTrace();
                }

                Map<Object, String> nodeNames = new HashMap<>();
                for (Map.Entry<Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>>, Map<T.TimingGroup, Object>>
                        forLoc : distTypeNodemap.entrySet()) {
                    Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> key = forLoc.getKey();
                    // dot syntax error if start with number
                    String keyString = "x_" + key.getFirst().getFirst() + "_" + key.getFirst().getSecond() + "_" +
                            key.getSecond().getSecond() + "_" + key.getSecond().getFirst();
                    for (Map.Entry<T.TimingGroup, Object> forTg : forLoc.getValue().entrySet()) {
                        String name = keyString + "_" + forTg.getKey().name();
                        nodeNames.put(forTg.getValue(), name);
                    }
                }

                graphVizPrintStream.println("digraph {");
                graphVizPrintStream.println("rankdir=LR;");
                for (TimingGroupEdge e : g.edgeSet()) {
                    String srcName = nodeNames.get(g.getEdgeSource(e));
                    String dstName = nodeNames.get(g.getEdgeTarget(e));
                    String edgeProp = "";
                    if (e.isReverseDirection()) {
                        //edgeProp += ",style=bold,color=red";
                        edgeProp += ",color=red";
                    }
                    graphVizPrintStream.println("  " + srcName + " -> " + dstName + " [ label=\"" + e.toGraphvizDotString() + "\"" + edgeProp + " ];");
                }
                // to make the end node visible in a large graph
//                graphVizPrintStream.println("  " + to + "_" + dist +"[shape=box,style=filled,color=\".7 .3 1.0\"];");
                graphVizPrintStream.println("}");
                graphVizPrintStream.close();
            }

            Graph<Object, TimingGroupEdge> getGraph() {
                return g;
            }

            Object getNode(short x, short y, T.Orientation orientation, T.TileSide side, T.TimingGroup tg) {
                Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> loc = new Pair<>(new Pair<>(x, y), new Pair<>(orientation, side));
                return distTypeNodemap.get(loc).get(tg);
            }
        }

        /**
         * Build a complete resource graph within a given rectangle region (resourceGraph)
         * Also populate nodeManager that map given TG at a location to a node in the resource graph.
         */
        void buildResourceGraph() {

//            // build all nodes
//            for (short i = 0; i < extendedWidth; i++) {
//                for (short j = 0; j < extendedWidth; j++) {
//                    createNodes(i, j, new T.Orientation[]{T.Orientation.U, T.Orientation.D},
//                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL)));
//                    createNodes(i, j, new T.Orientation[]{T.Orientation.U, T.Orientation.D},
//                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.VERTICAL)));
//                    createNodes(i, j, new T.Orientation[]{T.Orientation.S},
//                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.LOCAL)));
//                    createNodes(i, j, new T.Orientation[]{T.Orientation.S},
//                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.INPUT)));
//                    createNodes(i, j, new T.Orientation[]{T.Orientation.S},
//                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.OUTPUT)));
//                }
//            }

            // connect nodes from output forward, thus don't expand from Direction.INPUT
            boolean crossSide = true;
            for (short i = 0; i < extendedWidth; i++) {
                for (short j = 0; j < extendedHeight; j++) {
                    connectNodesFrom(i, j,
                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL) &&
                                    (e != T.TimingGroup.HORT_LONG) && (e != T.TimingGroup.HORT_SINGLE)));

                    connectNodesFrom(i, j,
                            ictInfo.getTimingGroup((T.TimingGroup e) ->
                                    (e.direction() == T.Direction.VERTICAL) && (e != T.TimingGroup.VERT_LONG)));

                    connectNodesFrom(i, j,
                            ictInfo.getTimingGroup((T.TimingGroup e) ->
                                    (e.direction() == T.Direction.VERTICAL) && (e == T.TimingGroup.VERT_LONG)));
                    connectNodesFrom(i, j,
                            ictInfo.getTimingGroup((T.TimingGroup e) ->
                                    (e.direction() == T.Direction.HORIZONTAL) && (e == T.TimingGroup.HORT_LONG)));
                    connectNodesFrom(i, j,
                            ictInfo.getTimingGroup((T.TimingGroup e) ->
                                    (e.direction() == T.Direction.HORIZONTAL) && (e == T.TimingGroup.HORT_SINGLE)));
                    connectNodesFrom(i, j,
                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.LOCAL)));
                    connectNodesFrom(i, j,
                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.OUTPUT)));

                    // correct individually
//                    connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) ->
//                                    (e.direction() == T.Direction.HORIZONTAL) && (e == T.TimingGroup.HORT_QUAD)));
//                    connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) ->
//                                    (e.direction() == T.Direction.HORIZONTAL) && (e == T.TimingGroup.HORT_DOUBLE)));
//                    connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) ->
//                                    (e.direction() == T.Direction.VERTICAL) && (e == T.TimingGroup.VERT_QUAD)));
//                                        connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) ->
//                                    (e.direction() == T.Direction.VERTICAL) && (e == T.TimingGroup.VERT_SINGLE)));
//                    connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) ->
//                                    (e.direction() == T.Direction.VERTICAL) && (e == T.TimingGroup.VERT_DOUBLE)));

//                    connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) ->
//                                    (e.direction() == T.Direction.VERTICAL) && (e == T.TimingGroup.VERT_LONG)));
//                    connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) ->
//                                    (e.direction() == T.Direction.HORIZONTAL) && (e == T.TimingGroup.HORT_LONG)));
//                    connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) ->
//                                    (e.direction() == T.Direction.HORIZONTAL) && (e == T.TimingGroup.HORT_SINGLE)));
//                    connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.LOCAL)));
//                    connectNodesFrom(i, j,
//                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.OUTPUT)));
                }
            }
        }


        void connectNodesFrom(short i, short j, List<T.TimingGroup> tgs) {
            for (T.TimingGroup frTg : tgs) {
                for (T.TileSide frSide : frTg.getExsistence()) {
                    for (T.Orientation frOrientation : frTg.getOrientation(frSide)) {
                        short deltaX = frTg.direction() == InterconnectInfo.Direction.HORIZONTAL ? frTg.length() : 0;
                        short deltaY = frTg.direction() == InterconnectInfo.Direction.VERTICAL ? frTg.length() : 0;
                        short m = (short) (i + ((frOrientation == T.Orientation.U) ? +deltaX : -deltaX));
                        short n = (short) (j + ((frOrientation == T.Orientation.U) ? +deltaY : -deltaY));

                        if (m >= extendedWidth || n >= extendedHeight || m < 0 || n < 0)
                            continue;
                        if (verboseLevel > 0)
                            System.out.println("fromTG " + i + " " + j + " " + frTg + " " + frSide + " " + frOrientation);

                        List<T.TimingGroup> nxtTgs = ictInfo.nextTimingGroups(frTg);
                        for (T.TimingGroup toTg : nxtTgs) {
                            if (verboseLevel > 0)
                                System.out.println("     toTG " + toTg);
                            for (T.TileSide toSide : frTg.toSide(frSide)) {
                                if (verboseLevel > 0)
                                    System.out.println("          toSide " + toSide);
                                for (T.Orientation toOrientation : toTg.getOrientation(toSide)) {
                                    deltaX = toTg.direction() == T.Direction.HORIZONTAL ? toTg.length() : 0;
                                    deltaY = toTg.direction() == T.Direction.VERTICAL ? toTg.length() : 0;
                                    short u = (short) (m + ((toOrientation == T.Orientation.U) ? +deltaX : -deltaX));
                                    short v = (short) (n + ((toOrientation == T.Orientation.U) ? +deltaY : -deltaY));

                                    if (u >= extendedWidth || v >= extendedHeight || u < 0 || v < 0)
                                        continue;
                                    if (verboseLevel > 0)
                                        System.out.println("               toOri " + toOrientation);

                                    // not go back to where it is from with the same tg
                                    if (!((toTg == frTg) && (toOrientation != frOrientation))) {
                                        if (verboseLevel > 0)
                                            System.out.println("                    add connection");
//                                        System.out.println(i + " " + j + " " + frTg + " " + frSide + " " + toTg + " " + toOrientation);
                                        nodeMan.connectNodes(i, j, m, n, frOrientation, frSide, toOrientation, toSide, frTg, toTg);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }


//        void createNodes (short i, short j, T.Orientation[] orientations, List<T.TimingGroup> tgs) {
//            for (T.Orientation orientation : orientations) {
//                for (T.TileSide side : new T.TileSide[]{T.TileSide.W, T.TileSide.E}) {
//                    for (InterconnectInfo.TimingGroup tg : tgs) {
//                        nodeMan.getOrCreateNode(i, j, orientation, side, tg);
//                    }
//                }
//            }
//        }

        void plot(String fname) {
            nodeMan.plot(fname);
        }


        Graph<Object, TimingGroupEdge> getGraph() {
            return nodeMan.getGraph();
        }


        Object getNode(short x, short y, T.Orientation orientation, T.TileSide side, T.TimingGroup tg) {
            return nodeMan.getNode(x, y, orientation, side, tg);
        }
    }

        /**
         * Trim dominated entry from a table
         * Moving it across the devices and record the min delay path.
         * Vertices and edges that never become a part of the min delay path will be removed.
         *
         * Questions: how to specify the range to sweep?
         */
    void trimTables() {

    }

    // instantiate entry of the 2-D array
    void initTables() {
        tables = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            ArrayList<Map<T.TimingGroup,Map<T.TimingGroup,DelayGraphEntry>>> subTables = new ArrayList<>();
            for (int j = 0; j < height; j++) {
                Map<T.TimingGroup, Map<T.TimingGroup, DelayGraphEntry>> srcMap = new EnumMap<>(T.TimingGroup.class);
                for (T.TimingGroup tg : T.TimingGroup.values()) {
                    srcMap.put(tg, new EnumMap<>(T.TimingGroup.class));
                }
                subTables.add(srcMap);
            }
            tables.add(subTables);
        }
    }

    /**
     * Clean up temporary data used in building tables.
     */
    void cleanup() {
//       nodeMan       = null;
//       resourceGraph = null;
    }

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



    // -----------------------   Methods for computing min delay ------------------------

    class ConnectionInfo {
        Pair<Short,Short>  sourceCoor;
        T.Orientation      orientation;
        Object             sourceNode;
        Object             sinkNode;

        ConnectionInfo(Pair<Short,Short> sourceCoor, InterconnectInfo.Orientation orientation, Object sourceNode, Object sinkNode) {
            this.sourceCoor  = sourceCoor;
            this.orientation = orientation;
            this.sourceNode  = sourceNode;
            this.sinkNode    = sinkNode;
        }

        short sourceX() {
            return sourceCoor.getFirst();
        }
        short sourceY() {
            return sourceCoor.getSecond();
        }
        T.Orientation orientation() {
            return orientation;
        }
        Object sourceNode() {
            return sourceNode;
        }
        Object sinkNode() {
            return sinkNode;
        }
    }

    private ConnectionInfo  getConnectionInfo (short begX, short begY, short endX, short endY,
                                    T.TimingGroup begTg, T.TimingGroup endTg, T.TileSide begSide,  T.TileSide endSide) {
        Pair<Short,Short> sourceCoor;
        T.Orientation orientation; // orientation of X or Y?

        if (endX >= begX && endY >= begY) {
            sourceCoor = botLeft;
            orientation = T.Orientation.S;
        } else if (endX < begX && endY >= begY) {
            sourceCoor = botRight;
            orientation = T.Orientation.S;
        } else if (endX >= begX && endY < begY) {
            sourceCoor = topLeft;
            orientation = T.Orientation.S;
        } else {
            sourceCoor = topRight;
            orientation = T.Orientation.S;
        }

        short distX = (short) (endX - begX);
        short distY = (short) (endY - begY);

        Pair<Short,Short> sinkCoor = new Pair<>((short) (sourceCoor.getFirst()+distX),(short) (sourceCoor.getSecond()+distY));

        Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> sourceLoc = new Pair<>(sourceCoor, new Pair<>(orientation,begSide));
        Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> sinkLoc   = new Pair<>(sinkCoor,   new Pair<>(orientation,endSide));

        Object sourceNode = rgBuilder.getNode(sourceCoor.getFirst(), sourceCoor.getSecond(), orientation, begSide, begTg);
        short sinkX = (short) (sourceCoor.getFirst()+distX);
        short sinkY = (short) (sourceCoor.getSecond()+distY);
        Object sinkNode   = rgBuilder.getNode(sinkX, sinkY, orientation, endSide, endTg);

        return new  ConnectionInfo(sourceCoor, orientation, sourceNode, sinkNode);
    }

    private Pair<Short,String> getMinDelayToSinkPin(T.TimingGroup begTg, T.TimingGroup endTg,
                 short begX, short begY, short endX, short endY, T.TileSide begSide, T.TileSide endSide) {

        assert endTg == T.TimingGroup.CLE_IN : "getMinDelayToSinkPin expects CLE_IN as the target timing group.";

        int distX = Math.abs(begX - endX);
        int distY = Math.abs(begY - endY);

        Pair<Short,String> result = null;
        if (distX < width && distY < height) {
            // setup graph
            // 1) graph cover the extended width/height. However, the source/sink must be in the target width/height.
            // 2) align the source coordinate to the nearest conor of the target width/height.
            //    TODO: consider transpose the route so that the source is on bottom-left and sink on top-right.

            ConnectionInfo info = getConnectionInfo(begX, begY, endX, endY, begTg, endTg, begSide, endSide);


//            result = lookupDelay(g, info.sourceNode(), info.sinkNode(), info.sourceX(), info.sourceY());
            result = lookupDelay(g, info.sourceNode(), info.sinkNode(), begX, begY);
        } else {
           // see findMinDelay of oldTable.java
        }

        // add delay of input sitepin
        return new Pair<>((short) (result.getFirst() + K0.get(T.Direction.INPUT).get(GroupDelayType.PINFEED)),result.getSecond());
    }

    /**
     * Find the min delay among all paths within the given graph
     * @param ig   The graph describing possible connections between the src and dst.
     * @param src  The node in the graph corresponding to the begin of the route.
     * @param dst  The node in the graph corresponding to the end of the route.
     * @param srcX The x coordinate of src in INT tile coordinate
     * @param srcY The y coordinate of src in INT tile coordinate
     * @return     The minimum delay of a valid path between src and dst. It also returns the path using resource abbreviation, in debug mode.
     */
    private Pair<Short,String> lookupDelay(Graph<Object, TimingGroupEdge> ig, Object src, Object dst, short srcX, short srcY) {
        boolean isBackward = false; // to be removed.
        T.Direction dir = T.Direction.VERTICAL; // to be removed


        // TODO: remove returned boolean and unused callback.

        Pair<Double,Boolean> res = DijkstraWithCallbacks.findMinWeightBetween(ig, src, dst, srcX,srcY,
                // ExamineEdge. Update edge weight which depend on the beginning loc, length and direction of the TG
                (g, u, e, x, y, dly) -> {g.setEdgeWeight(e,calcTimingGroupDelayOnEdge(e, u, dst, x, y, dly, isBackward));},
                // DiscoverVertex. Propagate location at the beginning loc of a timing group edge.
                (g, u, e, x, y, dly) -> {return discoverVertex(e, x, y, dly, isBackward);},
                (g, u, e, dly) -> {return updateVertex(e, dly, isBackward);},
                (e) -> {return isSwitchingSide(e);}
        );

        String route = "";

        if (verbose > 5 || verbose == -1) {
            int tempVerbose = verbose;
            verbose = 0; // to disable print from findPathBetween which is the same as what printed by findMinWeightBetween above.
            org.jgrapht.GraphPath<Object, TimingGroupEdge> minPath =
                    DijkstraWithCallbacks.findPathBetween(ig, src, dst, srcX, srcY,
                            (g, u, e, x, y, dly) -> {g.setEdgeWeight(e,calcTimingGroupDelayOnEdge(e,u, dst, x, y, dly, isBackward));},
                            (g, u, e, x, y, dly) -> {return discoverVertex(e, x, y, dly, isBackward);},
                            (g, u, e, dly) -> {return updateVertex(e, dly, isBackward);}
                    );
            verbose = tempVerbose;

            for (TimingGroupEdge e : minPath.getEdgeList()) {
                route += e.getTimingGroup().abbr();
            }
            if (verbose > 5) {
                System.out.println("\nPath with min delay: " + ((short) minPath.getWeight()) + " ps");
                System.out.println("\nPath details:");
                System.out.println(minPath.toString().replace(",", ",\n") + "\n");
            }
        }
        return new Pair<Short,String>(res.getFirst().shortValue(),route);
    }


    // -----------------------   Methods to help testing ------------------------
    class RoutingNode {
        short x;
        short y;
        T.TileSide side;
        T.Orientation direction;
        T.TimingGroup tg;

        RoutingNode(int x, int y, String side, String direction, String tg) {
            this.x         = (short) x;
            this.y         = (short) y;
            this.side      = T.TileSide.valueOf(side);
            this.direction = T.Orientation.valueOf(direction);
            this.tg        = T.TimingGroup.valueOf(tg);
        }
    }

    private Pair<Short,String> verifyPath() {
        // if the path is broken, get nullPointerException
        List<RoutingNode> route = new ArrayList<RoutingNode>(){{
            add(new RoutingNode(2,4,"E","S","CLE_OUT"));
            add(new RoutingNode(2,4,"E","U","VERT_QUAD"));
            add(new RoutingNode(2,8,"M","U","HORT_LONG"));
            add(new RoutingNode(8,8,"M","U","VERT_LONG"));
            add(new RoutingNode(8,20,"W","D","VERT_SINGLE"));
            add(new RoutingNode(8,19,"W","D","HORT_SINGLE"));
            add(new RoutingNode(7,19,"E","S","CLE_IN"));
        }};
        verbose = 6;

        Double delay = 0.0;
        String abbr = "";
        short offsetX = 44 - 2;
        short offsetY = 123 - 4;

        RoutingNode srcInfo = route.get(0);
        Object sourceNode = rgBuilder.getNode(route.get(0).x, route.get(0).y, route.get(0).direction, route.get(0).side, route.get(0).tg);
        for (int i = 1; i < route.size(); i++) {
            RoutingNode sinkInfo = route.get(i);
            Object sinkNode = rgBuilder.getNode(sinkInfo.x, sinkInfo.y, sinkInfo.direction, sinkInfo.side, sinkInfo.tg);

            TimingGroupEdge e = g.getEdge(sourceNode, sinkNode);

            double tdly = calcTimingGroupDelayOnEdge(e, null, null, (short) (srcInfo.x + offsetX), (short) (srcInfo.y + offsetY), 0.0, false);
            delay += tdly;
            abbr += e.getTimingGroup().abbr();

            sourceNode = sinkNode;
            srcInfo    = sinkInfo;
        }

        // add delay of CLE_IN
        delay += K0.get(T.Direction.INPUT).get(GroupDelayType.PINFEED);

        System.out.println("Route delay : " + delay.shortValue() + " : " + abbr);
        return new Pair<Short,String>(delay.shortValue(), abbr);
    }

    // use sx,tx,sy,ty order to match testcase
    void testOne( int sx, int tx, int sy, int ty, String sSide, String tSide) {
        verbose = 6;
        Pair<Short,String> res = getMinDelayToSinkPin(T.TimingGroup.CLE_OUT, T.TimingGroup.CLE_IN,
                (short) sx, (short) sy, (short) tx, (short) ty, T.TileSide.valueOf(sSide), T.TileSide.valueOf(tSide));
        System.out.println();
        System.out.println(sx + " " + tx + " " + sy + " " + ty + " " + res.getFirst() + " path " + res.getSecond());
    }

    void testCases(String fname) {

//        zeroDistArrays();
        verbose = -1; // -1 for testing

        class ErrorComputer {

            private int cnt;
            private int minErr;
            private int maxErr;
            private int minLine;
            private int maxLine;
            private int sumErr;

            ErrorComputer() {
                cnt = 0;
                minErr = 0;
                cnt = 0;
                minErr = Integer.MAX_VALUE;
                maxErr = 0;
                sumErr = 0;
            }


            void insert(int err, int linNo) {
//                minErr = Math.min(minErr, err);
//                maxErr = Math.max(maxErr, err);
                if (minErr >= err) {
                    minErr = err;
                    minLine = linNo;
                }
                // include = max error is the same as default
                if (maxErr <= err) {
                    maxErr = err;
                    maxLine = linNo;
                }
                sumErr += err;
                cnt++;
            }

            String report(String prefix) {
                float avgErr = sumErr/cnt;
                return prefix + " min " + minErr + " @" + minLine + " max " + maxErr + " @" + maxLine + " avg " + avgErr + " cnt " + cnt;
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
                int resLineNo = 1;
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
//                            System.out.println(String.format("%3d %3d %3d %3d   %4d %4d", sx, tx, sy, ty, tgDelay, rtDelay));

                            Pair<Short,String> res = getMinDelayToSinkPin(T.TimingGroup.CLE_OUT, T.TimingGroup.CLE_IN, sx, sy, tx, ty,
                                    T.TileSide.valueOf(sSide), T.TileSide.valueOf(tSide));
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
                                errToTgExc.insert(err1,resLineNo);
                                errToRtExc.insert(err2,resLineNo);
                            }
                            errToTg.insert(err1,resLineNo);
                            errToRt.insert(err2,resLineNo);
                        }
                    } else {
                        System.out.println("Find comment at line " + cnt);
                        outfile.write(line);
                        outfile.write(System.lineSeparator());
                    }
                    resLineNo++;
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

    public static void main(String args[]) {
        Device device = Device.getDevice("xcvu3p-ffvc1517");
        InterconnectInfo ictInfo = new InterconnectInfo();
        DelayEstimatorTable est = new DelayEstimatorTable(device,ictInfo, (short) 10, (short) 19, 0);

//        est.testCases("est_dly_ref_44_53_121_139_E_E.txt");
//        est.testCases("est_dly_ref_44_53_121_139_E_W.txt");
//        est.testCases("est_dly_ref_44_53_121_139_W_E.txt");
//        est.testCases("est_dly_ref_44_53_121_139_W_W.txt");

//        est.testOne(44, 49, 123, 138, "E", "E");
//        est.testOne(44, 45, 121, 137, "E", "E");
//        est.testOne(44, 45, 124, 124, "E", "W");

//        est.verifyPath();

    }

}












