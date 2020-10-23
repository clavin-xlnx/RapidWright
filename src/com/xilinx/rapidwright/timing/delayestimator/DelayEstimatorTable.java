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
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.timing.GroupDelayType;
import com.xilinx.rapidwright.timing.ImmutableTimingGroup;
import com.xilinx.rapidwright.timing.NodeWithFaninInfo;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.PairUtil;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
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
public class DelayEstimatorTable<T extends InterconnectInfo> extends DelayEstimatorBase<T> implements java.io.Serializable {


    DelayEstimatorTable(Device device, T ictInfo) {
        this(device, ictInfo, ictInfo.minTableWidth(), ictInfo.minTableHeight(), 0);
        String inFileName = "onex_merge.ser";
        this.rgBuilder.deserializeFrom(inFileName);
    }

    /**
     * Constructor from a device.
     *
     * @param device  Target device
     * @param ictInfo Interconnect information. TODO: should be selected automatically from device.
     * @param width   Width of delay tables.
     * @param height  Height of delay tables.
     */
    DelayEstimatorTable(Device device, T ictInfo, short width, short height, int verbose) {
        super(device, ictInfo, verbose);

//        assert width < ictInfo.minTableWidth() :
//                "DelayEstimatorTable expects larger custom table width.";
//        assert width < ictInfo.minTableHeight() :
//                "DelayEstimatorTable expects larger custom table height.";

        this.width = width;
        this.height = height;
        this.extendedWidth = (short) (width + 2 * horizontalDetourDistance);
        this.extendedHeight = (short) (height + 2 * verticalDetourDistance);
        short left = horizontalDetourDistance;
        short right = (short) (left + width - 1);
        short bot = verticalDetourDistance;
        short top = (short) (bot + height - 1);
        this.botLeft = new Pair<>(left, bot);
        this.botRight = new Pair<>(right, bot);
        this.topLeft = new Pair<>(left, top);
        this.topRight = new Pair<>(right, top);

        build();
    }

    DelayEstimatorTable(Device device, T ictInfo, int verbose) {
        super(device, ictInfo, verbose);
        this.width = ictInfo.minTableWidth();
        this.height = ictInfo.minTableHeight();
        build();
    }

    DelayEstimatorTable(Device device, T ictInfo, int verbose, boolean build) {
        super(device, ictInfo, verbose);
        this.width = ictInfo.minTableWidth();
        this.height = ictInfo.minTableHeight();
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
     * Get the min estimated delay between two timing groups.
     *
     * The beginning timing group can be of type Global or Bounce. However, Bounce is used to jump around within the INT tile of the sink.
     * Thus, it must not be considered until the router expansion is at the sink column.
     * If you call from bounce node to a sink node in different coloumns, the model don't store x coordinate.
     *
     * @param timingGroup Timing group at the beginning of the route
     * @param sinkPin     Timing group at the end. It must be a sinkPin
     * @return
     */
    @Override
    public short getMinDelayToSinkPin(ImmutableTimingGroup timingGroup,
                                      ImmutableTimingGroup sinkPin) {
        // TODO: consider puting this to graph to avoid this if
        if (timingGroup.delayType() == GroupDelayType.PIN_BOUNCE) {
            NodePair np = new NodePair(timingGroup, sinkPin);
            Short dly = delayFrBounceToSink.get(np);
            if (dly == null)
                return Short.MAX_VALUE;
            else
                // add input sitepin delay
                return (short) (dly + K0.get(T.Direction.INPUT).get(GroupDelayType.PINFEED));
        } else if (timingGroup.delayType() == GroupDelayType.PINFEED) {
            return Short.MAX_VALUE;
        } else {
            return getMinDelayToSinkPin(getTermInfo(timingGroup), getTermInfo(sinkPin)).getFirst();
        }

////        Node tgNode = timingGroup.getLastNode();
//// INT_X46Y110/IMUX_E17
//// INT_X45Y109/EE2_E_BEG6
//
//
//        // TODO: need to populate these from TGs
//        short begX = 0;
//        short begY = 0;
//        T.TileSide begSide = T.TileSide.E;
//        short endX = 10;
//        short endY = 15;
//        T.TileSide endSide = T.TileSide.E;
//        // end must always be CLE_IN,
//        T.TimingGroup endTg = T.TimingGroup.CLE_IN;
//        T.TimingGroup begTg = T.TimingGroup.CLE_OUT;
//
////        return begX;
//        return getMinDelayToSinkPin(begTg, endTg, begX, begY, endX, endY, begSide, endSide).getFirst();
//        // this is taking care off in getMinDelayToSinkPin
////        // If we store both E and W sides, the size of each entry will be double.
////        // We also need 4x more entries. Storing only one side should produce a small difference
////        // because all TG but LONG can switch sides.
////        if ((begSide != TileSide.M) && (begSide != endSide)) {
////            delay += 0;
////        }
////
////        return delay;
    }

    // TODO: load and store if it turns out to be slow to build
    @Override
    public boolean load(String filename) {
        return true;
    }

    @Override
    public boolean store(String filename) {
        return true;
    }

    // ------------------------------------   private ----------------------------------------

    class RoutingNode {
        // INT_TILE coordinate
        short x;
        short y;
        // E or W side of INT_TILE
        T.TileSide side;
        // U or D
        T.Orientation orientation;
        T.TimingGroup tg;

        RoutingNode(int x, int y, String side, String direction, String tg) {
            this.x         = (short) x;
            this.y         = (short) y;
            this.side      = T.TileSide.valueOf(side);
            this.orientation = T.Orientation.valueOf(direction);
            this.tg        = T.TimingGroup.valueOf(tg);
        }
        RoutingNode() {
            this.tg  = null;
        }
        void setHorTG(short len) {
            if (len == 1)
                this.tg = T.TimingGroup.valueOf("HORT_SINGLE");
            else if (len == 2)
                this.tg = T.TimingGroup.valueOf("HORT_DOUBLE");
            else if (len == 4)
                this.tg = T.TimingGroup.valueOf("HORT_QUAD");
            else
                this.tg = T.TimingGroup.valueOf("HORT_LONG");
        }
        void setVerTG(short len) {
            if (len == 1)
                this.tg = T.TimingGroup.valueOf("VERT_SINGLE");
            else if (len == 2)
                this.tg = T.TimingGroup.valueOf("VERT_DOUBLE");
            else if (len == 4)
                this.tg = T.TimingGroup.valueOf("VERT_QUAD");
            else
                this.tg = T.TimingGroup.valueOf("VERT_LONG");
        }
        public String toString() {
            return String.format("x:%d y:%d %s %s %s", x,y,side.name(),tg.name(),orientation.name());
        }
    }

    // input node is exitNode of a tg
    // TODO: This method is loop heavy. If TG is prebuilt, this problem will be solved because all info is pre-recorded.
    private T.TileSide findTileSideForInternalSingle(Node node) {
        Pattern EPattern = Pattern.compile("([\\w]+)_(E)_");
        Pattern WPattern = Pattern.compile("([\\w]+)_(W)_");

        for (Node prvNode : node.getAllUphillNodes()) {
            String prvNodeName = prvNode.getAllWiresInNode()[0].getWireName();

            Matcher EMatcher = EPattern.matcher(prvNodeName);
            if (EMatcher.find()) {
                return T.TileSide.E;
            } else {
                Matcher WMatcher = WPattern.matcher(prvNodeName);
                if (WMatcher.find())
                    return T.TileSide.W;
            }

        }
        return T.TileSide.M;
    }

    // TODO: This method is loop heavy. If TG is prebuilt, this problem will be solved because all info is pre-recorded.
    private T.TileSide findTileSideForGlobal(Node node) {
        Pattern EPattern = Pattern.compile("([\\w]+)_(E)_");
        Pattern WPattern = Pattern.compile("([\\w]+)_(W)_");

        for (Node nxtNode : node.getAllDownhillNodes()) {
            for (Node nxtNxtNode : nxtNode.getAllDownhillNodes()) {
                String nxtNxtNodeName = nxtNxtNode.getAllWiresInNode()[0].getWireName();

                Matcher EMatcher = EPattern.matcher(nxtNxtNodeName);
                if (EMatcher.find()) {
                    return T.TileSide.E;
                } else {
                    Matcher WMatcher = WPattern.matcher(nxtNxtNodeName);
                    if (WMatcher.find())
                        return T.TileSide.W;
                }
            }

        }
        return T.TileSide.M;
    }

    // node.toString()     -  node.getAllWiresInNode()[0].getIntentCode()
    // INT_X0Y0/BYPASS_E9  - NODE_PINBOUNCE  :
    // INT_X0Y0/IMUX_E9  - NODE_PINFEED  :
    // INT_X0Y0/EE2_E_BEG3  - NODE_DOUBLE  :
    // INT_X0Y0/NN1_E_BEG3  - NODE_SINGLE  :
    // INT_X0Y0/NN4_E_BEG2  - NODE_VQUAD  :
    // INT_X0Y0/INT_INT_SDQ_33_INT_OUT1  - NODE_SINGLE  :
    private RoutingNode getTermInfo(ImmutableTimingGroup tg) {

        Node node = tg.exitNode();
        IntentCode ic = node.getAllWiresInNode()[0].getIntentCode();
        Pattern tilePattern = Pattern.compile("X([\\d]+)Y([\\d]+)");
        Pattern EE          = Pattern.compile("EE([\\d]+)");
        Pattern WW          = Pattern.compile("WW([\\d]+)");
        Pattern NN          = Pattern.compile("NN([\\d]+)");
        Pattern SS          = Pattern.compile("SS([\\d]+)");

        Pattern skip         = Pattern.compile("WW1_E");

        RoutingNode res = new RoutingNode();


        // INT_X45Y109/EE2_E_BEG6
        // TODO: should I use getTile and wire instead of spliting the name?
        String[] int_node = node.toString().split("/");

        Matcher tileMatcher = tilePattern.matcher(int_node[0]);
        if (tileMatcher.find()) {
            res.x = Short.valueOf(tileMatcher.group(1));
            res.y = Short.valueOf(tileMatcher.group(2));
        } else {
            System.out.println("getTermInfo coordinate matching error for node " + node.toString());
        }

        if (skip.matcher(int_node[1]).find())
            return res; // res.tg is null

        String[] tg_side = int_node[1].split("_");

        // THIS IF MUST BE ABOVE THE IF BELOW (for res.side).
        // Can't use intendCode because there are two kinds of NODE_SINGLE, internal and not
        Matcher EEMatcher = EE.matcher(tg_side[0]);
        if (EEMatcher.find()) {
            res.orientation = T.Orientation.U;
            res.setHorTG(Short.valueOf(EEMatcher.group(1)));
        } else {
            Matcher WWMatcher = WW.matcher(tg_side[0]);
            if (WWMatcher.find()) {
                res.orientation = T.Orientation.D;
                res.setHorTG(Short.valueOf(WWMatcher.group(1)));
            } else {
                Matcher NNMatcher = NN.matcher(tg_side[0]);
                if (NNMatcher.find()) {
                    res.orientation = T.Orientation.U;
                    res.setVerTG(Short.valueOf(NNMatcher.group(1)));
                } else {
                    Matcher SSMatcher = SS.matcher(tg_side[0]);
                    if (SSMatcher.find()) {
                        res.orientation = T.Orientation.D;
                        res.setVerTG(Short.valueOf(SSMatcher.group(1)));
                    } else {
                        res.orientation = T.Orientation.S;
                        // For internal single, it will be set by the below
                        if (ic == IntentCode.NODE_PINBOUNCE)
                            // FF input
                            // TODO: Getting to FF has higher delay. How to handle that?
                            res.tg = T.TimingGroup.valueOf("CLE_IN");
                        else if (ic == IntentCode.NODE_PINFEED)
                            // LUT input
                            res.tg = T.TimingGroup.valueOf("CLE_IN");
                        else if (ic == IntentCode.NODE_LOCAL) {
                            // the only exitNode that can be NODE_LOCAL is GLOBAL node
                            res.tg = T.TimingGroup.valueOf("GLOBAL");
                            // have E_U_GLOBAL and E_D_GLOBAL (because reuse "SAME_SIDE" rule) connect to E_S_CLE_IN
                            // always use U, and waste D node
                            res.orientation = T.Orientation.U;
                        } else // can be CLE_OUT or INTERNAL_SINGLE. the next if will change it if INTERNAL_SINGLE
                            res.tg = T.TimingGroup.valueOf("CLE_OUT");
                    }
                }
            }
        }

        // THIS IF MUST BE BELOW THE IF ABOVE (for res.orientation).
        if (tg_side[1].startsWith("E"))
            res.side = T.TileSide.E;
        else if (tg_side[1].startsWith("W"))
            res.side = T.TileSide.W;
        else
            if (int_node[1].startsWith("INT")) {
                if (ic == IntentCode.NODE_LOCAL) {
                    res.side = findTileSideForGlobal(node);
                } else {
                    // Special for internal single such as INT_X0Y0/INT_INT_SDQ_33_INT_OUT1  - NODE_SINGLE
                    // Check intendcode is an alternative to above if condition.
                    res.side = findTileSideForInternalSingle(tg.entryNode());
                    // let res.orientation above set a wrong value and override it because findTileSideForInternalSingle is slow
                    res.orientation = (res.side == T.TileSide.E) ? T.Orientation.D : T.Orientation.U;
                    res.tg = T.TimingGroup.valueOf("INTERNAL_SINGLE");
                }
            } else {
                res.side = T.TileSide.M;
            }

        return res;
    }


    private short width;
    private short height;
    private short extendedWidth;
    private short extendedHeight;

    // TODO: should come from architecture
    private short horizontalDetourDistance = 6;
    private short verticalDetourDistance = 12;

    // Right and top are inclusive!
    private Pair<Short, Short> topLeft, topRight, botLeft, botRight;
    ResourceGraphBuilder rgBuilder;


    // TODO note the area used by resourceGraph and tables


    // Store tables for each dist and tg to speed up on-line delay computation
    // indexed by distane x, y, source tg and target tg, respectively
    // Don't reuse entities from resourceGraph because those can be cleaned up.
    private ArrayList<ArrayList<Map<T.TimingGroup, Map<T.TimingGroup, DelayGraphEntry>>>> tables;

    // temp to be deleted
//  private Graph<Object, TimingGroupEdge> g;

    // The delay does not include the source bounce.
    // if a bounce has no path to a sink, the sink is not reachble from the bounce.
    // IMPLEMENTATION NOTE:
    // the map is supposed to not tie to specific INT TILE. To accomplish that
    // 1) store node with tile_1. The sink can be in tile_0, tile_1 or tile_2.
    // 2) to look up need to translate to tile_0/1/2.
    class NodePair implements java.io.Serializable {
        // From Vivado, I see almost 1500 wires in an INT tile.
        // these slices should match the slice in the file read in below
//        tile_2 = getIntTileOfSite("SLICE_X37Y142");
//        tile_1 = getIntTileOfSite("SLICE_X37Y141");
//        tile_0 = getIntTileOfSite("SLICE_X30Y140");

        /**
         * Construct NodePair representing non-reachable
         * If hash contain this key, its value must be MAX.
         */
        NodePair() {
            tileOffset = -2;
            srcWireIdx = -1;
            srcWireIdx = -1;
        }
        /**
         * create NodePair from strings during loading
         *
         * @param src source node as in INT_X24Y140/BYPASS_E11
         * @param dst sink node
         */
        NodePair(String src, String dst) {
            this();
            Node srcNode = new Node(src, device);
            Node dstNode = new Node(dst, device);
            construct(srcNode, dstNode);

            // catch problem in the source txt input
            if (tileOffset == -2) {
                throw new IllegalArgumentException("Error in NodePair. " + src + " is too far from " + dst + ".");
            }
        }

        // to convert timing groups for lookup
        NodePair(ImmutableTimingGroup src, ImmutableTimingGroup dst) {
            this();
            Node srcNode = src.exitNode();
            Node dstNode = dst.exitNode();
            construct(srcNode, dstNode);
        }


        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodePair nodePair = (NodePair) o;
            return tileOffset == nodePair.tileOffset &&
                    srcWireIdx == nodePair.srcWireIdx &&
                    dstWireIdx == nodePair.dstWireIdx;
        }

        @Override
        public int hashCode() {
            return Objects.hash(tileOffset, srcWireIdx, dstWireIdx);
        }

        private byte tileOffset; // -1,0,+1
        private short srcWireIdx;
        private short dstWireIdx;

        private void construct(Node srcNode, Node dstNode) {
            Tile srcTile = srcNode.getTile();
            Tile dstTile = dstNode.getTile();
            int srcCoor = srcTile.getTileYCoordinate();
            int dstCoor = dstTile.getTileYCoordinate();

            if(Math.abs(dstCoor -srcCoor)<2) {
                tileOffset = (byte) (dstCoor - srcCoor);
                srcWireIdx = (short) srcNode.getAllWiresInNode()[0].getWireIndex();
                dstWireIdx = (short) dstNode.getAllWiresInNode()[0].getWireIndex();
            }
        }
    }

    private Map<NodePair,Short> delayFrBounceToSink;


    private void build() {
        rgBuilder = new ResourceGraphBuilder(extendedWidth, extendedHeight, 0);
        System.out.println("num nodes : " + rgBuilder.nodeMan.getGraph().vertexSet().size());
        System.out.println("num edges : " + rgBuilder.nodeMan.getGraph().edgeSet().size());
        if (this.verbose > 5 || this.verbose == -1)
            rgBuilder.plot("rggraph.dot");
//        g = rgBuilder.getGraph();
//        initTables();
//        trimTables();
//        cleanup();
        loadBounceDelay("bounce_sitepin.txt");
    }



    void loadBounceDelay(String fileName) {

        delayFrBounceToSink = new HashMap<>();

        // add an entry to represent unreachable
        NodePair unreachable = new NodePair();
        delayFrBounceToSink.put(unreachable,Short.MAX_VALUE);

        // bounce is considered a horizontal single with d 0
        float k0 = K0.get(T.Direction.HORIZONTAL).get(GroupDelayType.SINGLE);
        float k1 = K1.get(T.Direction.HORIZONTAL).get(GroupDelayType.SINGLE);
        short l  = L .get(T.Direction.HORIZONTAL).get(GroupDelayType.SINGLE);
        // need abs in case the tg is going to the left.
        short bounceDelay = (short) (k0 + k1 * l);



        InputStream inputStream = null;
        Scanner sc = null;

        try {
            // sink source numBounce
            // INT_X24Y140/BYPASS_E11 INT_X24Y141/BYPASS_E9 4
            inputStream = FileTools.getRapidWrightResourceInputStream(fileName);
            sc = new Scanner(inputStream, "UTF-8");
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();

                String testLine = line.replaceAll("\\s+", "");
                boolean lineIsBlank = testLine.isEmpty();

                if (lineIsBlank || line.trim().matches("^#.*")) { // if not a comment line
//                    System.out.println("skip " + line);
                } else {
                    List<String> items  = Arrays.asList(line.trim().split("\\s+"));
//                    System.out.println("*" + items.get(0) + " * " + items.get(1) + " * " + items.get(2));
                    short numBounces = Short.parseShort(items.get(2));
                    NodePair np = new NodePair(items.get(1), items.get(0));
                    // the source itself is a bounce and the estimate must contain the delay of the source.
                    short delay = (short) ((1+numBounces) * bounceDelay);
                    delayFrBounceToSink.put(np, delay);
                }
            }
            // Note that Scanner suppresses exceptions
            if (sc.ioException() != null) {
                throw sc.ioException();
            }
        } catch (IOException ex) {
            System.out.println (ex.toString());
            System.out.println("IOException during reading file " + fileName);
        }

        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException ex) {
            System.out.println (ex.toString());
            System.out.println("IOException during reading file " + fileName);
        } finally {
            if (sc != null) {
                sc.close();
            }
        }
    }

    // make this generic
    class Rectangle {
        short minX, minY, maxX, maxY;

        Rectangle(short minX, short minY, short maxX, short maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        public boolean contains(short x, short y) {
            if (x >= maxX || y >= maxY || x < minX || y < minY)
                return false;
            else
                return true;
        }
    }

    class ResourceGraphBuilder implements java.io.Serializable {
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
        class NodeManager implements java.io.Serializable {

            // An complete resource graph within a given rectangle region
            private Graph<Object, TimingGroupEdge> g;

            // <loc <tg, node>>
            //                      x  , y     , ori         , side
            private Map<Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>>, Map<T.TimingGroup, Object>> distTypeNodemap;
            private Map<
                        Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>>,
                        Map<T.TimingGroup, Object>
                       > adistTypeNodemap;


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
//                System.out.println(x + " " + y + " " + orientation + " " + side + " " + tg);
                Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> loc = new Pair<>(new Pair<>(x, y), new Pair<>(orientation, side));
                return distTypeNodemap.get(loc).get(tg);
            }

            // class use for merging two nodeMan.
            // TODO: use it for distTypeNodeMap as well.
            class NodeIdentity {
                short x;
                short y;
                T.Orientation ori;
                T.TileSide    side;
                T.TimingGroup tg;

                NodeIdentity(Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> loc, T.TimingGroup tg) {
                    this.x    = loc.getFirst().getFirst();
                    this.y    = loc.getFirst().getSecond();
                    this.ori  = loc.getSecond().getFirst();
                    this.side = loc.getSecond().getSecond();
                    this.tg   = tg;
                }

                NodeIdentity(int x, int y, T.Orientation ori, T.TileSide side, T.TimingGroup tg) {
                    this.x    = (short) x;
                    this.y    = (short) y;
                    this.ori  = ori;
                    this.side = side;
                    this.tg   = tg;
                }

                @Override
                public boolean equals(Object o) {
                    if (this == o) return true;
                    if (o == null || getClass() != o.getClass()) return false;
                    NodeIdentity that = (NodeIdentity) o;
                    return x == that.x &&
                            y == that.y &&
                            ori == that.ori &&
                            side == that.side &&
                            tg == that.tg;
                }

                @Override
                public int hashCode() {
                    return Objects.hash(x, y, ori, side, tg);
                }
            }

            private Pair<Map<Object, NodeIdentity>,Map<NodeIdentity, Object>> getNodeIdentities(
                    Map<Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>>, Map<T.TimingGroup, Object>> in) {
                Map<Object,NodeIdentity> res  = new HashMap<>();
                Map<NodeIdentity,Object> res2 = new HashMap<>();

                for (Map.Entry<Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>>,Map<T.TimingGroup, Object>>
                        entry : in.entrySet()) {
                    for (Map.Entry<T.TimingGroup, Object> tg_obj : entry.getValue().entrySet()) {
                        NodeIdentity nid = new NodeIdentity(entry.getKey(), tg_obj.getKey());
                        res.put(tg_obj.getValue(), nid);
                        res2.put(nid,tg_obj.getValue());
                    }
                }
                return new Pair<>(res,res2);
            }

            void merge(NodeManager other) {

                Pair<Map<Object, NodeIdentity>,Map<NodeIdentity, Object>> temp1 = this.getNodeIdentities(distTypeNodemap);
                Pair<Map<Object, NodeIdentity>,Map<NodeIdentity, Object>> temp2 = other.getNodeIdentities(other.distTypeNodemap);


                Map<Object, NodeIdentity> thisNodeToId  = temp1.getFirst();
                Map<Object, NodeIdentity> otherNodeToId = temp2.getFirst();
                Map<NodeIdentity, Object> thisIdToNode  = temp1.getSecond();
                Map<NodeIdentity, Object> otherIdToNode = temp2.getSecond();

                NodeIdentity testid = new NodeIdentity(42,0,InterconnectInfo.Orientation.S, InterconnectInfo.TileSide.W, InterconnectInfo.TimingGroup.CLE_OUT);
                Object testnode = thisIdToNode.get(testid);

                // for info only
                {
                    int count = 0;
                    for (TimingGroupEdge e : g.edgeSet()) {
                        if (e.isMarked())
                            count++;
                    }
                    System.out.println("Before mergeing: number of marked edges is " + count);
                }

                // collect edges with marker
                {
                    int count = 0;
                    for (TimingGroupEdge oe : other.g.edgeSet()) {
                        if (oe.isMarked()) {
                            Object osrc = other.g.getEdgeSource(oe);
                            Object odst = other.g.getEdgeTarget(oe);

//                            Object src = thisIdToNode.get(otherNodeToId.get(osrc));
//                            Object dst = thisIdToNode.get(otherNodeToId.get(odst));

                            // for debug
                            NodeIdentity osrcid = otherNodeToId.get(osrc);
                            NodeIdentity odstid = otherNodeToId.get(odst);
                            Object src = thisIdToNode.get(osrcid);
                            Object dst = thisIdToNode.get(odstid);

                            NodeIdentity srcid = thisNodeToId.get(src);
                            NodeIdentity dstid = thisNodeToId.get(dst);



                            TimingGroupEdge e = g.getEdge(src, dst);
                            e.setMarker();

                            count++;
                        }
                    }
                    System.out.println("Mergeing: number of marked edges is " + count);
                }

                // for info only
                {
                    int count = 0;
                    for (TimingGroupEdge e : g.edgeSet()) {
                        if (e.isMarked())
                            count++;
                    }
                    System.out.println("After mergeing: number of marked edges is " + count);
                }
            }

            void removeUnmarked() {
                System.out.println("Graph stat before removing");
                System.out.println("num nodes : " + nodeMan.getGraph().vertexSet().size());
                System.out.println("num edges : " + nodeMan.getGraph().edgeSet().size());

                {
                    int count = 0;
                    for (TimingGroupEdge e : g.edgeSet()) {
                        if (e.isMarked())
                            count++;
                    }
                    System.out.println("Before removing: number of marked edges is " + count);
                }

                // remove unmarked edge
                List<TimingGroupEdge> edgesToRemove = new ArrayList<>();
                for (TimingGroupEdge e : g.edgeSet()) {
                    if (!e.isMarked())
                        edgesToRemove.add(e);
                }
                for (TimingGroupEdge e : edgesToRemove)
                    g.removeEdge(e);

                List<Object> verticesToRemove = new ArrayList<>();
                for (Object v : g.vertexSet()) {
                    if (g.degreeOf(v) == 0) {
                        verticesToRemove.add(v);
                    }
                }
//                for (int i = 0; i < 1000; i++)  {
//                    List<Object> verticesToRemove = new ArrayList<>();
//                    for (Object v : g.vertexSet()) {
//                        if (g.outDegreeOf(v) == 0) {
//                            verticesToRemove.add(v);
//                        }
//                    }
//                    if (verticesToRemove.isEmpty()) {
//                        break;
//                    } else {
                        for (Object v : verticesToRemove)
                            g.removeVertex(v);
//                    }
//                }

                {
                    int count = 0;
                    for (TimingGroupEdge e : g.edgeSet()) {
                        if (e.isMarked())
                            count++;
                    }
                    System.out.println("After removing: number of marked edges is " + count);
                }

                System.out.println("Graph stat after removing");
                System.out.println("num nodes : " + nodeMan.getGraph().vertexSet().size());
                System.out.println("num edges : " + nodeMan.getGraph().edgeSet().size());
            }
        }

        /**
         * Build a complete resource graph within a given rectangle region (resourceGraph)
         * Also populate nodeManager that map given TG at a location to a node in the resource graph.
         */
        void buildResourceGraph() {

            // connect nodes from output forward, thus don't expand from Direction.INPUT

            Rectangle tableRect = new Rectangle((short) 0, (short) 0, extendedWidth, extendedHeight);
            for (short i = 0; i < extendedWidth; i++) {
                for (short j = 0; j < extendedHeight; j++) {
                    connectNodesFrom(i, j, tableRect,
                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL) &&
                                    (e != T.TimingGroup.HORT_LONG) && (e != T.TimingGroup.HORT_SINGLE)));

                    connectNodesFrom(i, j, tableRect,
                            ictInfo.getTimingGroup((T.TimingGroup e) ->
                                    (e.direction() == T.Direction.VERTICAL) && (e != T.TimingGroup.VERT_LONG)));

                    connectNodesFrom(i, j, tableRect,
                            ictInfo.getTimingGroup((T.TimingGroup e) ->
                                    (e.direction() == T.Direction.VERTICAL) && (e == T.TimingGroup.VERT_LONG)));
                    connectNodesFrom(i, j, tableRect,
                            ictInfo.getTimingGroup((T.TimingGroup e) ->
                                    (e.direction() == T.Direction.HORIZONTAL) && (e == T.TimingGroup.HORT_LONG)));
                    connectNodesFrom(i, j, tableRect,
                            ictInfo.getTimingGroup((T.TimingGroup e) ->
                                    (e.direction() == T.Direction.HORIZONTAL) && (e == T.TimingGroup.HORT_SINGLE)));
                    connectNodesFrom(i, j, tableRect,
                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.LOCAL)));
                    connectNodesFrom(i, j, tableRect,
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


            // connect from outside the table
            short minX = botLeft.getFirst();
            short minY = botLeft.getSecond();
            short maxX = (short) (topRight.getFirst() +1); // get exclusive high end
            short maxY = (short) (topRight.getSecond() +1);
            Rectangle box = new Rectangle(minX, minY, maxX, maxY);

            for (short j = 0; j < T.maxTgLength(T.Direction.VERTICAL); j++) {
                for (short x = minX; x < maxX; x++) {
                    { // from bottom
                        // start (j=0) one row below the target box
                        short y = (short) (minY - j - 1);
                        connectNodesFrom(x, y, box,
                                ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.VERTICAL)));
                    }
                    { // from top
                        // start (j=0) at maxY. Note maxY is exclusive
                        short y = (short) (maxY + j);
                        connectNodesFrom(x, y, box,
                                ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.VERTICAL)));
                    }
                }
            }


            for (short j = 0; j < T.maxTgLength(T.Direction.HORIZONTAL); j++) {
                for (short y = minY; y < maxY; y++) {
                    { // from left
                        short x = (short) (minX - j - 1);
                        connectNodesFrom(x, y, box,
                                ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL)));
                    }
                    { // from right
                        short x = (short) (maxX + j);
                        connectNodesFrom(x, y, box,
                                ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL)));
                    }
                }
            }

            // for global
            for (short x = minX; x < maxX; x++) {
                for (short y = minY; y < maxY; y++) {
                    connectNodesFrom(x, y, box,
                            ictInfo.getTimingGroup((T.TimingGroup e) -> (e.type() == GroupDelayType.GLOBAL)));
                }
            }

        }


        /**
         * Connect from each tg in the given list to what possible tg from the architecture as long as its end point is in the box.
         * @param i start x coor of a tg
         * @param j start y coor of a tg
         * @param box
         * @param tgs
         */
        void connectNodesFrom(short i, short j, Rectangle box, List<T.TimingGroup> tgs) {
            for (T.TimingGroup frTg : tgs) {
                for (T.TileSide frSide : frTg.getExsistence()) {
                    for (T.Orientation frOrientation : frTg.getOrientation(frSide)) {
                        short deltaX = frTg.direction() == InterconnectInfo.Direction.HORIZONTAL ? frTg.length() : 0;
                        short deltaY = frTg.direction() == InterconnectInfo.Direction.VERTICAL ? frTg.length() : 0;
                        short m = (short) (i + ((frOrientation == T.Orientation.U) ? +deltaX : -deltaX));
                        short n = (short) (j + ((frOrientation == T.Orientation.U) ? +deltaY : -deltaY));

                        if (!box.contains(m, n))
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

                                    if (!box.contains(u, v))
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

        void plot(String fname) {
            // something wrong with formatting. But at least can inspect the text file
            nodeMan.plot(fname);
        }

        Graph<Object, TimingGroupEdge> getGraph() {
            return nodeMan.getGraph();
        }

        Object getNode(short x, short y, T.Orientation orientation, T.TileSide side, T.TimingGroup tg) {
            return nodeMan.getNode(x, y, orientation, side, tg);
        }

        void serializeTo(String fileName) {
            try {
                byte[] data = HessianUtil.serialize(nodeMan);
                HessianUtil.writeByte(data, fileName);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }

        void deserializeFrom(String fileName) {
            nodeMan = null;
            try {
                byte[] data = HessianUtil.readByte(fileName);
                nodeMan = HessianUtil.deserialize(data);
                System.out.println("deserializeFrom " + fileName);
                System.out.println("num nodes : " + nodeMan.getGraph().vertexSet().size());
                System.out.println("num edges : " + nodeMan.getGraph().edgeSet().size());
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }

        void merge(String fileName) {
            System.out.println("ResourceGraphBuilder merge with " + fileName);
            NodeManager otherNodeMan = null;
            try {
                byte[] data = HessianUtil.readByte(fileName);
                otherNodeMan = HessianUtil.deserialize(data);
                System.out.println("deserializeFrom " + fileName);
                System.out.println("num nodes : " + otherNodeMan.getGraph().vertexSet().size());
                System.out.println("num edges : " + otherNodeMan.getGraph().edgeSet().size());
                nodeMan.merge(otherNodeMan);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        void removeUnmarked() {
            nodeMan.removeUnmarked();
        }
    }
    void trimTable() {
        // for testing , will come from member fields later.
        short maxX = 107;
        short minX = 0;
        short yCoor = 60;

        // sweep across the chip
        int itr = 0;
        for (short atX = minX; atX <= maxX; atX++) {
            trimTableAt(atX, yCoor, false);
        }
    }
    void trimHelper(List<T.TimingGroup> frTgs, Rectangle box, T.TimingGroup toTg, Pair<Short,Short> srcCoor,
                    Pair<Short,Short> dstCoor, short srcX, short srcY) {
        boolean isBackward = false; // to be removed.
        for (InterconnectInfo.TimingGroup frTg : frTgs) {
            for (T.TileSide frSide : frTg.getExsistence()) {
                for (T.Orientation frOrientation : frTg.getOrientation(frSide)) {
                    short deltaX = frTg.direction() == InterconnectInfo.Direction.HORIZONTAL ? frTg.length() : 0;
                    short deltaY = frTg.direction() == InterconnectInfo.Direction.VERTICAL ? frTg.length() : 0;
                    short m = (short) (srcCoor.getFirst() + ((frOrientation == T.Orientation.U) ? +deltaX : -deltaX));
                    short n = (short) (srcCoor.getSecond() + ((frOrientation == T.Orientation.U) ? +deltaY : -deltaY));

                    System.out.println("frtg " + frTg + " " + srcCoor + " " + m + " " + n);
                    if (!box.contains(m, n))
                        continue;
                    System.out.println("frtg " + frTg + " " + srcCoor + " " + m + " " + n + " in");

                    // sweep over possible sink tg
                    for (T.TileSide toSide : toTg.getExsistence()) {
                        for (T.Orientation toOrientation : toTg.getOrientation(toSide)) {

//                            System.out.println("fr " + frTg + " " + frOrientation + " " + frSide);
//                            System.out.println("to " + toTg + " " + toOrientation + " " + toSide);

                            // calling getConnectionInfo to get effect of rotating/alignning the graph
                            ConnectionInfo info = getConnectionInfo(srcCoor.getFirst(), srcCoor.getSecond(),
                                    dstCoor.getFirst(), dstCoor.getSecond(),frTg, toTg, frSide, toSide, frOrientation, toOrientation);

                            Object src = info.sourceNode();
                            Object dst = info.sinkNode();
                            Double res = DijkstraWithCallbacks.findMinWeightBetween(
                                    rgBuilder.getGraph(), src, dst, srcX, srcY,
                                    // ExamineEdge. Update edge weight which depend on the beginning loc, length and direction of the TG
                                    (g, u, e, x, y, dly) -> {
                                        rgBuilder.getGraph().setEdgeWeight(e, calcTimingGroupDelayOnEdge(e, u, dst, x, y, dly, isBackward));
                                    },
                                    // DiscoverVertex. Propagate location at the beginning loc of a timing group edge.
                                    (g, u, e, x, y, dly) -> {
                                        return discoverVertex(e, x, y, dly, isBackward);
                                    },
                                    (g, u, e, dly) -> {
                                        return updateVertex(e, dly, isBackward);
                                    },
                                    (e) -> {
                                        e.setMarker();
                                    }
                            );
                        }
                    }
                }
            }
        }
    }
    /**
     * Trim dominated entry from a table
     * Moving it across the devices and record the min delay path.
     * Vertices and edges that never become a part of the min delay path will be removed.
     * <p>
     * Questions: how to specify the range to sweep?
     */
    void trimTableAt(short xCoor, short yCoor, boolean inTable) {
        boolean isBackward = false; // to be removed.

        // dst is always an input site pin.
        InterconnectInfo.TimingGroup toTg = T.TimingGroup.CLE_IN;

        // consider only intable connection, because out-table is approximate and too large to sweep
        if (inTable){
            short minX = 0;
            short minY = 0;
            short maxX = width;
            short maxY = height;
            Rectangle box = new Rectangle(minX, minY, maxX, maxY);
            List<Pair<Short, Short>> endPoints = new ArrayList<Pair<Short, Short>>() {{
                add(new Pair<>(minX,minY));
                add(new Pair<>(minX,maxY));
                add(new Pair<>(maxX,minY));
                add(new Pair<>(maxX,maxY));
            }};

            // sweep over possible route within a table
            for (short srcX = minX; srcX < maxX; srcX++) {
                for (short srcY = minY; srcY < maxY; srcY++) {
                    for (Pair<Short, Short> endPoint : endPoints) {
                        // sweep over possible source tg
                        trimHelper(ictInfo.getTimingGroup((T.TimingGroup e) ->
                                   (e.type() != GroupDelayType.PINFEED) && (e.type() != GroupDelayType.GLOBAL)),
                                    box, toTg, new Pair<>(srcX, srcY), endPoint, xCoor, yCoor);
                    }
                }
            }

            // for global
            // TODO: should be combined with the loop above
            System.out.println("trim global");
            for (short x = minX; x < maxX; x++) {
                for (short y = minY; y < maxY; y++) {
                    for (Pair<Short, Short> endPoint : endPoints) {
                        trimHelper(ictInfo.getTimingGroup((T.TimingGroup e) -> (e.type() == GroupDelayType.GLOBAL)),
                                box, toTg, new Pair<>(x, y), endPoint, xCoor, yCoor);
                    }
                }
            }
        }


        // connect from outside the table
        if (!inTable){
            // the starting point of tg will be out side of the box
//            short minX = (short) (T.maxTgLength(T.Direction.HORIZONTAL) + 1); // to not start below 0
//            short minY = (short) (T.maxTgLength(T.Direction.VERTICAL) + 1);
//            short maxX = (short) (minX + width);
//            short maxY = (short) (minX + height);
//            Rectangle box = new Rectangle(minX, minY, maxX, maxY);
//            List<Pair<Short, Short>> endPoints = new ArrayList<Pair<Short, Short>>() {{
//                add(new Pair<>(minX,minY));
//                add(new Pair<>(minX,maxY));
//                add(new Pair<>(maxX,minY));
//                add(new Pair<>(maxX,maxY));
//            }};
            short minX = botLeft.getFirst();
            short minY = botLeft.getSecond();
            short maxX = (short) (topRight.getFirst() + 1); // get exclusive high end
            short maxY = (short) (topRight.getSecond() + 1);
            Rectangle box = new Rectangle(minX, minY, maxX, maxY);
            List<Pair<Short, Short>> endPoints = new ArrayList<Pair<Short, Short>>() {{
//                add(topLeft);
//                add(topRight);
                add(botLeft);
//                add(botRight);
            }};
//            short minX = (short) (T.maxTgLength(T.Direction.HORIZONTAL) + 1); // to not start below 0
//            short minY = (short) (T.maxTgLength(T.Direction.VERTICAL) + 1);
//            short maxX = (short) (minX + width);
//            short maxY = (short) (minX + height);
//            Rectangle box = new Rectangle(minX, minY, maxX, maxY);
//            List<Pair<Short, Short>> endPoints = new ArrayList<Pair<Short, Short>>() {{
//                add(new Pair<>(minX,minY));
//                add(new Pair<>(minX,maxY));
//                add(new Pair<>(maxX,minY));
//                add(new Pair<>(maxX,maxY));
//            }};

            System.out.println("trim external vertical");
            for (short j = 0; j < T.maxTgLength(T.Direction.VERTICAL); j++) {
                for (short x = 15; x <= 15; x++) {
//                    short x = minX; x < maxX; x++
                    { // from bottom
                        // start (j=0) one row below the target box
                        short y = (short) (minY - j - 1);
                        for (Pair<Short, Short> endPoint : endPoints) {
//                            System.out.println("bot " + j + " " + y + " " + x + " " + endPoint);
                            trimHelper(ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.VERTICAL)),
                                    box, toTg, new Pair<>(x, y), endPoint, xCoor, yCoor);
                        }
                    }
//                    { // from top
//                        // start (j=0) at maxY. Note maxY is exclusive
//                        short y = (short) (maxY + j);
//                        for (Pair<Short, Short> endPoint : endPoints) {
////                            System.out.println("top " + j + " " + y + " " + x + " " + endPoint);
//                            trimHelper(ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.VERTICAL)),
//                                    box, toTg, new Pair<>(x, y), endPoint, xCoor, yCoor);
//                        }
//                    }
                }
            }


            System.out.println("trim external horizontal");
            for (short j = 0; j < T.maxTgLength(T.Direction.HORIZONTAL); j++) {
                for (short y = minY; y < maxY; y++) {
                    { // from left
                        short x = (short) (minX - j - 1);
                        for (Pair<Short, Short> endPoint : endPoints) {
//                            System.out.println("left " + j + " " + y + " " + x + " " + endPoint);
                            trimHelper(ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL)),
                                    box, toTg, new Pair<>(x, y), endPoint, xCoor, yCoor);
                        }
                    }
                    { // from right
                        short x = (short) (maxX + j);
                        for (Pair<Short, Short> endPoint : endPoints) {
//                            System.out.println("right " + j + " " + y + " " + x + " " + endPoint);
                            trimHelper(ictInfo.getTimingGroup((T.TimingGroup e) -> (e.direction() == T.Direction.HORIZONTAL)),
                                    box, toTg, new Pair<>(x, y), endPoint, xCoor, yCoor);
                        }
                    }
                }
            }


        }
        int count = 0;
        for (TimingGroupEdge e : rgBuilder.getGraph().edgeSet()) {
            if (e.isMarked())
                count++;
        }
        System.out.println("number of marked edges is " + count);
    }



//    void trimTable() {
//        for (short xDist = 0; xDist < width; xDist++) {
//            for (short yDist = 0; yDist < width; yDist++) {
//                trimTableAt(xDist, yDist);
//            }
//        }
//    }
//
//    /**
//     * Trim dominated entry from a table
//     * Moving it across the devices and record the min delay path.
//     * Vertices and edges that never become a part of the min delay path will be removed.
//     * <p>
//     * Questions: how to specify the range to sweep?
//     */
//    void trimTableAt(int xDist, int yDist) {
//        boolean isBackward = false; // to be removed.
//        // for testing , will come from member fields later.
//        short maxX = 107;
//        short minX = 0;
//
//        // dst is always an input site pin.
//        InterconnectInfo.TimingGroup toTg = T.TimingGroup.CLE_IN;
//
//        // consider only intable connection, because out-table is approximate and too large to sweep
//
//        // sweep over possible route within a table
//        for (short srcX = 0; srcX < width; srcX++) {
//            for (short srcY = 0; srcY < height; srcY++) {
//                short dstX = (short) (srcX + xDist);
//                short dstY = (short) (srcY + yDist);
//                if ((dstX < width) && (dstY < height)) {
//                    // sweep over possible source tg
//                    for (InterconnectInfo.TimingGroup frTg : ictInfo.getTimingGroup((T.TimingGroup e) ->
//                            (e.type() != GroupDelayType.PINFEED) && (e.type() != GroupDelayType.GLOBAL))) {
//                        for (T.TileSide frSide : frTg.getExsistence()) {
//                            for (T.Orientation frOrientation : frTg.getOrientation(frSide)) {
//
//                                // sweep over possible sink tg
//                                for (T.TileSide toSide : toTg.getExsistence()) {
//                                    for (T.Orientation toOrientation : toTg.getOrientation(toSide)) {
//                                        // calling getConnectionInfo to get effect of rotating/alignning the graph
//                                        ConnectionInfo info = getConnectionInfo(srcX, srcY, dstX, dstY,
//                                                frTg, toTg, frSide, toSide, frOrientation, toOrientation);
//                                        Object src = info.sourceNode();
//                                        Object dst = info.sinkNode();
//
//                                        // sweep across the chip
//                                        for (short atX = minX; atX <= maxX; atX++) {
//                                            Double res = DijkstraWithCallbacks.findMinWeightBetween(rgBuilder.getGraph(), src, dst, srcX, srcY,
//                                                    // ExamineEdge. Update edge weight which depend on the beginning loc, length and direction of the TG
//                                                    (g, u, e, x, y, dly) -> {
//                                                        rgBuilder.getGraph().setEdgeWeight(e, calcTimingGroupDelayOnEdge(e, u, dst, x, y, dly, isBackward));
//                                                    },
//                                                    // DiscoverVertex. Propagate location at the beginning loc of a timing group edge.
//                                                    (g, u, e, x, y, dly) -> {
//                                                        return discoverVertex(e, x, y, dly, isBackward);
//                                                    },
//                                                    (g, u, e, dly) -> {
//                                                        return updateVertex(e, dly, isBackward);
//                                                    },
//                                                    (e) -> {
//                                                        e.setMarker();
//                                                    }
//                                            );
//                                            // just want to test my flow for now
//                                            int count = 0;
//                                            for (TimingGroupEdge e : rgBuilder.getGraph().edgeSet()) {
//                                                if (e.isMarked())
//                                                    count++;
//                                            }
//                                            System.out.println("number of marked edges is " + count);
//                                            return;
//                                        }
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//        }
//
//        int count = 0;
//        for (TimingGroupEdge e : rgBuilder.getGraph().edgeSet()) {
//            if (e.isMarked())
//                count++;
//        }
//        System.out.println("number of marked edges is " + count);
//    }

    // instantiate entry of the 2-D array
    void initTables() {
        tables = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            ArrayList<Map<T.TimingGroup, Map<T.TimingGroup, DelayGraphEntry>>> subTables = new ArrayList<>();
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
        public short fixedDelay;
        public boolean onlyOneDst;
    }


    // -----------------------   Methods for computing min delay ------------------------
    class ConnectionInfo {
        Pair<Short, Short> sourceCoor;
        Pair<Short, Short> sinkCoor;
        T.Orientation srcOrientation;
        T.Orientation dstOrientation;
        Object sourceNode;
        Object sinkNode;

        ConnectionInfo(Pair<Short, Short> sourceCoor, Pair<Short, Short> sinkCoor, T.Orientation srcOrientation, T.Orientation dstOrientation, Object sourceNode, Object sinkNode) {
            this.sourceCoor = sourceCoor;
            this.sinkCoor = sinkCoor;
            this.srcOrientation = srcOrientation;
            this.dstOrientation = dstOrientation;
            this.sourceNode = sourceNode;
            this.sinkNode = sinkNode;
        }

        Object sourceNode() {
            return sourceNode;
        }

        Object sinkNode() {
            return sinkNode;
        }
    }

    class PartialRoute {
        private final short delay;
        private final short loc;
        private final T.Orientation orientaion;
        private final List<T.TimingGroup> tgs;

        PartialRoute(short delay, short loc, T.Orientation orientaion, List<T.TimingGroup> tgs) {
            this.delay = delay;
            this.loc = loc;
            this.orientaion = orientaion;
            this.tgs = tgs;
        }

        short delay() {
            return delay;
        }

        short loc() {
            return loc;
        }

        T.Orientation orientaion() {
            return orientaion;
        }

        ;

        T.TimingGroup lastTg() {
            if (tgs.isEmpty())
                return null;
            else
                return tgs.get(tgs.size() - 1);
        }

        boolean isEmpty() {
            return tgs.isEmpty();
        }

        String route() {
            String route = "";
            for (T.TimingGroup tg : tgs)
                route += tg.abbr();
            return route;
        }
    }

    //    /**
//     * Finding info for table lookup. The sink will be aligned with one of the corner.
//     * Aligning sink so that it can be used for outtable where the end use the table.
//     * @param begX
//     * @param begY
//     * @param endX
//     * @param endY
//     * @param begTg
//     * @param endTg
//     * @param begSide
//     * @param endSide
//     * @return
//     */
    private ConnectionInfo getConnectionInfo(short begX, short begY, short endX, short endY,
                                             T.TimingGroup begTg, T.TimingGroup endTg, T.TileSide begSide, T.TileSide endSide,
                                             T.Orientation srcOrientation, T.Orientation dstOrientation) {
        Pair<Short, Short> sinkCoor = projectSinkCoor(begX, begY, endX, endY);

        short distX = (short) (endX - begX);
        short distY = (short) (endY - begY);

        Pair<Short, Short> sourceCoor = new Pair<>((short) (sinkCoor.getFirst() - distX), (short) (sinkCoor.getSecond() - distY));

        Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> sourceLoc = new Pair<>(sourceCoor, new Pair<>(srcOrientation, begSide));
        Pair<Pair<Short, Short>, Pair<T.Orientation, T.TileSide>> sinkLoc   = new Pair<>(sinkCoor, new Pair<>(dstOrientation, endSide));

        Object sourceNode = rgBuilder.getNode(sourceCoor.getFirst(), sourceCoor.getSecond(), srcOrientation, begSide, begTg);
        Object sinkNode   = rgBuilder.getNode(sinkCoor.getFirst(), sinkCoor.getSecond(), dstOrientation, endSide, endTg);

        return new ConnectionInfo(sourceCoor, sinkCoor, srcOrientation, dstOrientation, sourceNode, sinkNode);
    }

    private Pair<Short, Short> projectSinkCoor(short begX, short begY, short endX, short endY) {
        if (endX >= begX && endY >= begY) {
            return topRight;
        } else if (endX < begX && endY >= begY) {
            return topLeft;
        } else if (endX >= begX && endY < begY) {
            return botRight;
        } else {
            return botLeft;
        }
    }

    /**
     * Get offset to map actual coor to coor based on the table.
     * To translate real coor to table coor do "point + offset".
     * To translate table coor to real coor do "point - offset".
     *
     * @param begX
     * @param begY
     * @param endX
     * @param endY
     * @return
     */
    private Pair<Short, Short> getOffsetToTable(short begX, short begY, short endX, short endY) {
        Pair<Short, Short> sinkCoor = projectSinkCoor(begX, begY, endX, endY);
        return new Pair<Short, Short>((short) (sinkCoor.getFirst() - endX), (short) (sinkCoor.getSecond() - endY));
    }

    private Pair<Short, String> getMinDelayToSinkPin(RoutingNode beg, RoutingNode end) {
        if (beg.tg == null)
            return new Pair<Short,String>(Short.MAX_VALUE,null);
        return getMinDelayToSinkPin( beg.tg, end.tg, beg.x, beg.y, end.x, end.y, beg.side, end.side, beg.orientation, end.orientation);
    }

    private Pair<Short, String> getMinDelayToSinkPin(T.TimingGroup begTg, T.TimingGroup endTg,
                                                     short begX, short begY, short endX, short endY, T.TileSide begSide, T.TileSide endSide,
                                                     T.Orientation begOrientation, T.Orientation endOrientation) {

        assert endTg == T.TimingGroup.CLE_IN : "getMinDelayToSinkPin expects CLE_IN as the target timing group.";

        int distX = Math.abs(begX - endX);
        int distY = Math.abs(begY - endY);

        Pair<Short, String> result = null;
        if (distX < width && distY < height) {
            // setup graph
            // 1) graph cover the extended width/height. However, the source/sink must be in the target width/height.
            // 2) align the source coordinate to the nearest conor of the target width/height.
            //    TODO: consider transpose the route so that the source is on bottom-left and sink on top-right.

            ConnectionInfo info = getConnectionInfo(begX, begY, endX, endY, begTg, endTg, begSide, endSide, begOrientation, endOrientation);


//            result = lookupDelay(g, info.sourceNode(), info.sinkNode(), info.sourceX(), info.sourceY());
            result = lookupDelay(rgBuilder.getGraph(), info.sourceNode(), info.sinkNode(), begX, begY);
        } else {

            boolean oneway = true;

            // TODO: consider shuffleing segments within one or both directions.

            InfoHorThenVer info = new InfoHorThenVer(begX, begY, endX, endY);

            if (oneway) { // one way version

                // The start will be quad/long on the direction that is farther aware from the box.
                // table ranges have exclusive end points.
                int gapX = (distX > width) ? Math.abs(distX - width + 1) : 0;
                int gapY = (distY > height) ? Math.abs(distY - height + 1) : 0;

                if (gapX < gapY) {
                    info.swap();
                }

                PartialRoute partialFirst = extend(begTg, info.firstBeg(), info.firstEnd(), info.firstDir(), info.firstLim());
                PartialRoute partialSecond = extend(partialFirst.lastTg(), info.secondBeg(), info.secondEnd(), info.secondDir(), info.secondLim());

                String route = "";
                if (verbose > 5 || verbose == -1) {
                    route += partialFirst.route();
                    route += partialSecond.route();
                }

                result = getTotalDelay(partialFirst, partialSecond, info.firstDir(), begSide,
                        endTg, endSide, endOrientation, endX, endY, route);

            } else { // two ways version

                List<Pair<Short,String>> results = new ArrayList<>();

                for (int i = 0; i < 2; i++) {
                    PartialRoute partialFirst = extend(begTg, info.firstBeg(), info.firstEnd(), info.firstDir(), info.firstLim());
                    T.TimingGroup switchingTG = partialFirst.isEmpty() ? begTg : partialFirst.lastTg();
                    PartialRoute partialSecond = extend(switchingTG, info.secondBeg(), info.secondEnd(), info.secondDir(), info.secondLim());

                    String route = "";
                    if (verbose > 5 || verbose == -1) {
                        route += partialFirst.route();
                        route += partialSecond.route();
                    }

                    Pair<Short,String> aResult = getTotalDelay(partialFirst, partialSecond, info.firstDir(), begSide,
                            endTg, endSide, endOrientation, endX, endY, route);
                    results.add(aResult);

                    info.swap();
                }

                result = Collections.min(results, new PairUtil.CompareFirst<>());
            }
        }

        // add delay of input sitepin
        return new Pair<>((short) (result.getFirst() + K0.get(T.Direction.INPUT).get(GroupDelayType.PINFEED)), result.getSecond());
    }

    class InfoHorThenVer {
        final short num = 2;
        short beg[];
        short end[];
        short lim[];
        T.Direction dir[];

        // index for first and second
        short first;
        short second;

        InfoHorThenVer(short begX, short begY, short endX, short endY) {
            beg = new short[num];
            end = new short[num];
            lim = new short[num];
            dir = new T.Direction[num];

            first = 0;
            second = 1;

            beg[0] = begX;
            end[0] = endX;
            lim[0] = width;
            dir[0] = T.Direction.HORIZONTAL;

            beg[1] = begY;
            end[1] = endY;
            lim[1] = height;
            dir[1] = T.Direction.VERTICAL;
        }

        void swap() {
            first = (short) ((first + 1) % num);
            second = (short) ((second + 1) % num);
        }
        short firstBeg() {return beg[first];}
        short firstEnd() {return end[first];}
        short firstLim() {return lim[first];}
        T.Direction firstDir() {return dir[first];}
        short secondBeg() {return beg[second];}
        short secondEnd() {return end[second];}
        short secondLim() {return lim[second];}
        T.Direction secondDir() {return dir[second];}
    }


    Pair<Short,Pair<Short,Short>> rollBackLastTg(short extendingDelay, InterconnectInfo.TimingGroup lastTg, T.Orientation lastOrientation, short newBegX, short newBegY) {
        short endLoc = lastTg.direction() == T.Direction.HORIZONTAL ? newBegX : newBegY;
        short deltaX = (lastTg.direction() == T.Direction.HORIZONTAL ? lastTg.length() : 0);
        short deltaY = (lastTg.direction() == T.Direction.VERTICAL ? lastTg.length() : 0);
        newBegX = (short) (newBegX + (lastOrientation == T.Orientation.U ? -deltaX : deltaX));
        newBegY = (short) (newBegY + (lastOrientation == T.Orientation.U ? -deltaY : deltaY));
        short begLoc = lastTg.direction() == T.Direction.HORIZONTAL ? newBegX : newBegY;
        extendingDelay -= calcTimingGroupDelay(lastTg, begLoc, endLoc, 0.0);
        return new Pair<>(extendingDelay,new Pair<>(newBegX, newBegY));
    }

    Pair<Short,String> getTotalDelay(PartialRoute partialFirst, PartialRoute partialSecond, T.Direction firstDir,
                                     T.TileSide begSide,
                                     T.TimingGroup endTg, T.TileSide endSide, T.Orientation endOrientation,
                                     short endX, short endY, String route) {
        T.TimingGroup lastTg = null;
        T.Orientation lastOrientation = null;
        if (!partialSecond.isEmpty()) {
            lastTg = partialSecond.lastTg();
            lastOrientation = partialSecond.orientaion();
        } else {
            lastTg = partialFirst.lastTg();
            lastOrientation = partialFirst.orientaion();
        }


        short extendingDelay = (short) (partialFirst.delay() + partialSecond.delay());
        short newBegX = (firstDir == T.Direction.HORIZONTAL) ?  partialFirst.loc() : partialSecond.loc();
        short newBegY = (firstDir == T.Direction.VERTICAL)   ?  partialFirst.loc() : partialSecond.loc();

        // TODO: this is very conservative and expensive. Need to pick just a few possibilities
        // TODO: take it from interconnectInfo
        T.TileSide lastSide = ((lastTg == T.TimingGroup.VERT_LONG) || (lastTg == T.TimingGroup.HORT_LONG))
                ? T.TileSide.M : begSide;
//            Pair<Short,Short> offset = getOffsetToTable(begX, begY, endX, endY);
//            Rectangle tableRect = new Rectangle((short)(0-offset.getFirst()),(short)(0-offset.getSecond()),
//                    (short) (extendedWidth-offset.getFirst()), (short) (extendedHeight-offset.getSecond()));
//
//            List<Pair<Short,String>> inTableDelays = new ArrayList<>();
//            for (T.TimingGroup frTg : ictInfo.nextTimingGroups(lastTg)) {
//                for (T.TileSide frSide : lastTg.toSide(lastSide)) {
//                    for (T.Orientation frOrientation : frTg.getOrientation(frSide)) {
//                        short deltaX = frTg.direction() == T.Direction.HORIZONTAL ? frTg.length() : 0;
//                        short deltaY = frTg.direction() == T.Direction.VERTICAL ? frTg.length() : 0;
//                        short u = (short) (newBegX + ((frOrientation == T.Orientation.U) ? +deltaX : -deltaX));
//                        short v = (short) (newBegY + ((frOrientation == T.Orientation.U) ? +deltaY : -deltaY));
//
//                        if (!tableRect.contains(u,v))
//                            continue;
//
//                        // not go back to where it is from with the same tg
//                        if (!((frTg == lastTg) && (frOrientation != lastOrientation))) {
//                            ConnectionInfo info = getConnectionInfo(newBegX, newBegY, endX, endY, frTg, endTg, frSide, endSide, frOrientation, endOrientation);
//                            result = lookupDelay(g, info.sourceNode(), info.sinkNode(), newBegX, newBegY);
//                            inTableDelays.add(result);
//                        }
//                    }
//                }
//            }
//            result = Collections.min(inTableDelays, new PairUtil.CompareFirst<>());

        Pair<Short,Pair<Short,Short>> rollBackInfo = rollBackLastTg(extendingDelay, lastTg, lastOrientation, newBegX, newBegY);
        extendingDelay = rollBackInfo.getFirst();
        newBegX = rollBackInfo.getSecond().getFirst();
        newBegY = rollBackInfo.getSecond().getSecond();

        if (verbose > 6 || verbose == -1) {
            route = (route == null || route.length() == 0) ? null : (route.substring(0, route.length() - 1));
        }

        ConnectionInfo info = getConnectionInfo(newBegX, newBegY, endX, endY, lastTg, endTg, lastSide, endSide, lastOrientation, endOrientation);
        Pair<Short,String> result = lookupDelay(rgBuilder.getGraph(), info.sourceNode(), info.sinkNode(), newBegX, newBegY);


        short  inTableDelay = result.getFirst();
        String inTableRoute = result.getSecond();
        result.setFirst((short) (inTableDelay + extendingDelay));
        result.setSecond(route + inTableRoute);
        return result;
    }



    /**
     *
     * @param begTg
     * @param beg
     * @param end
     * @param dir
     * @param tableRange
     * @return Pair<Pair<delay,end coor>,list of Tgs>
     */
    PartialRoute extend (T.TimingGroup begTg, short beg, short end, T.Direction dir, short tableRange) {

        int dist = Math.abs(beg - end);
        // table range is exclusive end
        int gap  = (dist > tableRange) ? Math.abs(dist - tableRange + 1) : 0;
        T.Orientation orientation = (end > beg) ? T.Orientation.valueOf("U") : T.Orientation.valueOf("D");
        short begLoc = beg;
        short extendingDelay = 0;
        List<T.TimingGroup> outTgs = new ArrayList<>();
        T.TimingGroup lastTg = begTg;

        while (gap > 0) {
            final int dist_constant = dist;
            List<T.TimingGroup> nxtTgs = ictInfo.nextTimingGroups(lastTg, (T.TimingGroup e) ->
                    (e.direction() == dir) && (e.length() <= dist_constant));
            T.TimingGroup tg = pickTheLongestTg(nxtTgs);
            lastTg = tg;
            outTgs.add(tg);

            short endLoc = (short) (begLoc + ((orientation == T.Orientation.U) ? +tg.length() : -tg.length()));
            Double incDly = calcTimingGroupDelay(tg, begLoc, endLoc, 0.0);
            extendingDelay += incDly.shortValue();

            begLoc = endLoc;
            gap    = gap - tg.length();
            dist   = dist - tg.length();
        }

//        return new Pair<Pair<Short,Short>, List<T.TimingGroup>>(new Pair<>(extendingDelay,begLoc), outTgs);
        return new PartialRoute(extendingDelay, begLoc, orientation, outTgs);
    }

    T.TimingGroup pickTheLongestTg( List<T.TimingGroup> tgs) {
        short maxLength = 0;
        T.TimingGroup longestTg = null;

        for (T.TimingGroup tg : tgs ) {
            if (maxLength < tg.length())  {
                maxLength = tg.length();
                longestTg = tg;
            }
        }
        return longestTg;
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
        Double res = DijkstraWithCallbacks.findMinWeightBetween(ig, src, dst, srcX,srcY,
                // ExamineEdge. Update edge weight which depend on the beginning loc, length and direction of the TG
                (g, u, e, x, y, dly) -> {g.setEdgeWeight(e,calcTimingGroupDelayOnEdge(e, u, dst, x, y, dly, isBackward));},
                // DiscoverVertex. Propagate location at the beginning loc of a timing group edge.
                (g, u, e, x, y, dly) -> {return discoverVertex(e, x, y, dly, isBackward);},
                (g, u, e, dly) -> {return updateVertex(e, dly, isBackward);},
                (e) -> {e.setMarker();}
        );
//        Double res = DijkstraWithCallbacks.findMinWeightBetween(ig, src, dst, srcX,srcY,
//                // ExamineEdge. Update edge weight which depend on the beginning loc, length and direction of the TG
//                (g, u, e, x, y, dly) -> {g.setEdgeWeight(e,calcTimingGroupDelayOnEdge(e, u, dst, x, y, dly, isBackward));},
//                // DiscoverVertex. Propagate location at the beginning loc of a timing group edge.
//                (g, u, e, x, y, dly) -> {return discoverVertex(e, x, y, dly, isBackward);},
//                (g, u, e, dly) -> {return updateVertex(e, dly, isBackward);}
//        );



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
        return new Pair<Short,String>(res.shortValue(),route);
    }


    // -----------------------   Methods to help testing ------------------------
    void testBounceToSink() {
        {  // exact tile as in the data
            ImmutableTimingGroup d1 = createTG("INT_X24Y140/IMUX_W29", device);
            ImmutableTimingGroup s1 = createTG("INT_X24Y141/BYPASS_W10", device);
            short dly1 = getMinDelayToSinkPin(s1, d1);
            System.out.println("1 " + dly1);
            ImmutableTimingGroup d2 = createTG("INT_X24Y140/IMUX_W10", device);
            ImmutableTimingGroup s2 = createTG("INT_X24Y141/BYPASS_W1", device);
            short dly2 = getMinDelayToSinkPin(s2, d2);
            System.out.println("2 " + dly2);
            ImmutableTimingGroup d3 = createTG("INT_X24Y140/BOUNCE_E_0_FT1", device);
            ImmutableTimingGroup s3 = createTG("INT_X24Y141/BYPASS_E14", device);
            short dly3 = getMinDelayToSinkPin(s3, d3);
            System.out.println("3 " + dly3);
            ImmutableTimingGroup d4 = createTG("INT_X24Y140/BOUNCE_E_0_FT1", device);
            ImmutableTimingGroup s4 = createTG("INT_X24Y141/BYPASS_W9", device);
            short dly4 = getMinDelayToSinkPin(s4, d4);
            System.out.println("4 " + dly4);
        }
        {  // diff tile from the data
            ImmutableTimingGroup d1 = createTG("INT_X24Y10/IMUX_W29", device);
            ImmutableTimingGroup s1 = createTG("INT_X24Y11/BYPASS_W10", device);
            short dly1 = getMinDelayToSinkPin(s1, d1);
            System.out.println("1 " + dly1);
            ImmutableTimingGroup d2 = createTG("INT_X24Y10/IMUX_W10", device);
            ImmutableTimingGroup s2 = createTG("INT_X24Y11/BYPASS_W1", device);
            short dly2 = getMinDelayToSinkPin(s2, d2);
            System.out.println("2 " + dly2);
            ImmutableTimingGroup d3 = createTG("INT_X24Y10/BOUNCE_E_0_FT1", device);
            ImmutableTimingGroup s3 = createTG("INT_X24Y11/BYPASS_E14", device);
            short dly3 = getMinDelayToSinkPin(s3, d3);
            System.out.println("3 " + dly3);
            ImmutableTimingGroup d4 = createTG("INT_X24Y10/BOUNCE_E_0_FT1", device);
            ImmutableTimingGroup s4 = createTG("INT_X24Y11/BYPASS_W9", device);
            short dly4 = getMinDelayToSinkPin(s4, d4);
            System.out.println("4 " + dly4);
        }
//        INT_X24Y142/INT_NODE_IMUX_47_INT_OUT0  INT_X24Y142/IMUX_W39
//        INT_X24Y141/INT_NODE_IMUX_44_INT_OUT0  INT_X24Y141/BYPASS_W10  4
//        INT_X24Y142/INT_NODE_IMUX_46_INT_OUT0  INT_X24Y142/IMUX_W10
//        INT_X24Y141/INT_NODE_IMUX_33_INT_OUT1  INT_X24Y141/BYPASS_W1  0
//        INT_X24Y141/INODE_E_5_FT1              INT_X24Y140/BOUNCE_E_0_FT1
//        INT_X24Y141/INT_NODE_IMUX_23_INT_OUT0  INT_X24Y141/BYPASS_E14  5
//        INT_X24Y141/INODE_E_5_FT1              INT_X24Y140/BOUNCE_E_0_FT1
//        INT_X24Y141/INT_NODE_IMUX_45_INT_OUT0  INT_X24Y141/BYPASS_W9 -1
    }

    void testTGmap() {
//        // i 197 j 148  INT_X21Y109
//        Device device = Device.getDevice("xcvu3p-ffvc1517");
//        System.out.println("device  " + device.getName());
//        for (int i = 50; i < 200; i = i+7) {
//            for (int j = 50; j < 200; j = j+7){
//                Tile t = device.getTile(i, j);
//                System.out.println(i + " " + j + " " + t.getName());
//            }
//        }


//        Device device = Device.getDevice("xcvu3p-ffvc1517");
//        Tile t = device.getTile(197, 148);
//        Site s = t.getSites()[0];
//        for (int i = 0; i < s.getSitePinCount(); i++) {
//            if (s.isOutputPin(i)) {
//
//            }
//        }
//        getConnectedNode(int pinIndex)
    }


    /**
     *
     * @return
     */
    private Pair<Short,String> verifyPath() {
        // if the path is broken, get nullPointerException
        Pair<Short,Short> srcCoor = new Pair<>((short)37,(short)74);
        List<RoutingNode> route = new ArrayList<RoutingNode>(){{
            add(new RoutingNode(12,31,"M","D","VERT_LONG"));
            add(new RoutingNode(12,19,"M","D","VERT_QUAD"));
            add(new RoutingNode(12,15,"M","D","VERT_LONG"));
            add(new RoutingNode(12,3,"E","U","VERT_DOUBLE"));
            add(new RoutingNode(12,5,"E","S","CLE_IN"));
        }};
//        Pair<Short,Short> srcCoor = new Pair<>((short)37,(short)74);
//        List<RoutingNode> route = new ArrayList<RoutingNode>(){{
//            add(new RoutingNode(12,31,"M","D","VERT_LONG"));
//            add(new RoutingNode(12,19,"M","D","VERT_LONG"));
//            add(new RoutingNode(12,7,"E","D","VERT_DOUBLE"));
//            add(new RoutingNode(12,5,"E","S","CLE_IN"));
//        }};
//        Pair<Short,Short> srcCoor = new Pair<>((short)37,(short)76);
//        List<RoutingNode> route = new ArrayList<RoutingNode>(){{
//            add(new RoutingNode(12,-3,"M","U","VERT_LONG"));
//            add(new RoutingNode(12,9,"M","U","VERT_LONG"));
//            add(new RoutingNode(12,21,"E","U","VERT_DOUBLE"));
//            add(new RoutingNode(12,23,"E","S","CLE_IN"));
//        }};
// 44->49, 421 -qld because HD detoure go over URAM
// 49->44, 273 -qld
//        Pair<Short,Short> srcCoor = new Pair<>((short)49,(short)123);
//        List<RoutingNode> route = new ArrayList<RoutingNode>(){{
//            add(new RoutingNode(13,5,"W","S","CLE_OUT"));
//            add(new RoutingNode(13,5,"W","U","HORT_QUAD"));
//            add(new RoutingNode(15,5,"M","D","HORT_LONG"));
//            add(new RoutingNode(9,5,"E","D","HORT_DOUBLE"));
//            add(new RoutingNode(8,5,"E","S","CLE_IN"));
//        }};
//        Pair<Short,Short> srcCoor = new Pair<>((short)44,(short)123);
//        List<RoutingNode> route = new ArrayList<RoutingNode>(){{
//            add(new RoutingNode(3,5,"W","S","CLE_OUT"));
//            add(new RoutingNode(3,5,"W","D","HORT_QUAD"));
//            add(new RoutingNode(1,5,"M","U","HORT_LONG"));
//            add(new RoutingNode(7,5,"E","U","HORT_DOUBLE"));
//            add(new RoutingNode(8,5,"E","S","CLE_IN"));
//        }};

//        Pair<Short,Short> srcCoor = new Pair<>((short)44,(short)123);
//        List<RoutingNode> route = new ArrayList<RoutingNode>(){{
//            add(new RoutingNode(8,17,"W","S","CLE_OUT"));
//            add(new RoutingNode(8,17,"W","U","VERT_QUAD"));
//            add(new RoutingNode(8,21,"M","U","HORT_LONG"));
//            add(new RoutingNode(14,21,"E","D","HORT_QUAD"));
//            add(new RoutingNode(12,21,"E","U","VERT_DOUBLE"));
//            add(new RoutingNode(12,23,"E","S","CLE_IN"));
//        }};
//        Pair<Short,Short> srcCoor = new Pair<>((short)44,(short)123);
//        List<RoutingNode> route = new ArrayList<RoutingNode>(){{
//            add(new RoutingNode(2,4,"W","S","CLE_OUT"));
//            add(new RoutingNode(2,4,"W","U","VERT_QUAD"));
//            add(new RoutingNode(2,8,"M","U","HORT_LONG"));
//            add(new RoutingNode(8,8,"E","D","HORT_QUAD"));
//            add(new RoutingNode(6,8,"E","U","VERT_DOUBLE"));
//            add(new RoutingNode(6,10,"E","S","CLE_IN"));
//        }};
        verbose = 6;

        Double delay = 0.0;
        String abbr = "";


        RoutingNode srcInfo = route.get(0);
        short offsetX = (short) (srcCoor.getFirst() - srcInfo.x);
        short offsetY = (short) (srcCoor.getSecond() - srcInfo.y);
        Object sourceNode = rgBuilder.getNode(route.get(0).x, route.get(0).y, route.get(0).orientation, route.get(0).side, route.get(0).tg);
        for (int i = 1; i < route.size(); i++) {
            RoutingNode sinkInfo = route.get(i);
            Object sinkNode = rgBuilder.getNode(sinkInfo.x, sinkInfo.y, sinkInfo.orientation, sinkInfo.side, sinkInfo.tg);

            TimingGroupEdge e = rgBuilder.getGraph().getEdge(sourceNode, sinkNode);

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
                (short) sx, (short) sy, (short) tx, (short) ty, T.TileSide.valueOf(sSide), T.TileSide.valueOf(tSide), T.Orientation.S, T.Orientation.S);
        System.out.println();
        System.out.println(sx + " " + tx + " " + sy + " " + ty + " " + res.getFirst() + " path " + res.getSecond());
    }

    int testCases(String fname) {

        boolean profiling = true;

//        zeroDistArrays();
        if (profiling)
            verbose = 1; // 1 for profiling
        else
            verbose = -1; // -1 for testing

        class ErrorComputer {

            private int cnt;
            private int minErr;
            private int maxErr;
            private int minLine;
            private int maxLine;
            private int sumErr;
            private double minPct;
            private double maxPct;

            ErrorComputer() {
                cnt = 0;
                minErr = 0;
                cnt = 0;
                minErr = Integer.MAX_VALUE;
                maxErr = 0;
                sumErr = 0;
                minPct = Double.MAX_VALUE;
                maxPct = 0;
            }


            void insert(int err, double ept, int linNo) {
                minPct = Math.min(minPct, ept);
                maxPct = Math.max(maxPct, ept);
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
                return prefix + " min " + minErr + " @" + minLine + " max " + maxErr + " @" + maxLine + " avg " + avgErr
                        + " cnt " + cnt + " maxPctErr " +  maxPct + "% minPctErr " + minPct + "%";
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
        int numProcessed = 0;

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

                            if (!profiling)
                                System.out.println(String.format("attempt %3d %3d %3d %3d   %4d %4d", sx, tx, sy, ty, tgDelay, rtDelay));
                            Pair<Short,String> res = getMinDelayToSinkPin(T.TimingGroup.CLE_OUT, T.TimingGroup.CLE_IN, sx, sy, tx, ty,
                                    T.TileSide.valueOf(sSide), T.TileSide.valueOf(tSide), T.Orientation.S, T.Orientation.S);
                            numProcessed++;
                            short est = res.getFirst();


                            if (!profiling) {
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
                                    errToTgExc.insert(err1, ept1, resLineNo);
                                    errToRtExc.insert(err2, ept2, resLineNo);
                                }
                                errToTg.insert(err1, ept1, resLineNo);
                                errToRt.insert(err2, ept2, resLineNo);
                            }
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

                if (!profiling) {
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
                }

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

        return numProcessed;
    }

    ImmutableTimingGroup createTG(String exitNodeName, String entryNodeName, Device device) {
        Node exitNode  = new Node(exitNodeName, device);
        Node entryNode = new Node(entryNodeName, device);
        // check if they are connected. Can't check if it indeed a valid TG.
        boolean connected = false;
        for (Node n : entryNode.getAllDownhillNodes()) {
            if (n.equals(exitNode)) {
                connected = true;
                break;
            }
        }

        if (!connected)
            System.out.println(entryNodeName + " and " + exitNodeName + " are not connected.");

        IntentCode exitIC  = exitNode.getAllWiresInNode()[0].getIntentCode();
        IntentCode entryIC = exitNode.getAllWiresInNode()[0].getIntentCode();
        return new ImmutableTimingGroup(NodeWithFaninInfo.create(exitNode), NodeWithFaninInfo.create(entryNode), exitIC, entryIC);
    }

    // for TG with only one node
    ImmutableTimingGroup createTG(String exitNodeName, Device device) {
        Node exitNode  = new Node(exitNodeName, device);
        IntentCode exitIC  = exitNode.getAllWiresInNode()[0].getIntentCode();
        return new ImmutableTimingGroup(NodeWithFaninInfo.create(exitNode), exitIC);
    }


    void testSpecialCase(Device device) {
        verbose = 6;

        ImmutableTimingGroup src = createTG("INT_X11Y98/IMUX_W36" ,"INT_X11Y98/INT_NODE_IMUX_48_INT_OUT0" ,  device);
        ImmutableTimingGroup dst = createTG("INT_X11Y116/IMUX_W33","INT_X11Y116/INT_NODE_IMUX_40_INT_OUT0",  device );

        short dly = getMinDelayToSinkPin(src, dst);
        System.out.println("delay " + dly);
        return;
    }

    public static void main(String args[]) {
        Device device = Device.getDevice("xcvu3p-ffvc1517");
        InterconnectInfo ictInfo = new InterconnectInfo();

        long startTime = System.nanoTime();

//        DelayEstimatorTable est = new DelayEstimatorTable(device,ictInfo, (short) 10, (short) 19, 0);
//        DelayEstimatorTable est = new DelayEstimatorTable(device,ictInfo, (short) 2, (short) 2, 6);
        DelayEstimatorTable est = new DelayEstimatorTable(device,ictInfo, (short) 10, (short) 19, 0);
//        DelayEstimatorTable est = new DelayEstimatorTable(device,ictInfo);

        est.trimTableAt((short)50,(short)60,false);
        est.rgBuilder.removeUnmarked();

        short yCoor = 60;


        if ((args.length > 0) && (!args[0].startsWith("#")))  {

            if (args[0].equalsIgnoreCase("WriteGraph")) {
                est.rgBuilder.serializeTo(args[1] + ".ser");
                return;
            }
            else if (args[0].equalsIgnoreCase("TrimGraphIn")) {
                System.out.println("DelayEstimatorTable TrimGraphIn " + args[1] + " at x " + args[2]);
                est.rgBuilder.deserializeFrom(args[1] + ".ser");
                short xCoor = Short.parseShort(args[2]);
                est.trimTableAt(xCoor, yCoor, true);
                String outFileName = args[1] + "_in_" + xCoor + ".ser";
                est.rgBuilder.serializeTo(outFileName);
                return;
            }
            else if (args[0].equalsIgnoreCase("TrimGraphOut")) {
                System.out.println("DelayEstimatorTable TrimGraphOut " + args[1] + " at x " + args[2]);
                est.rgBuilder.deserializeFrom(args[1] + ".ser");
                short xCoor = Short.parseShort(args[2]);
                est.trimTableAt(xCoor, yCoor, false);
                String outFileName = args[1] + "_out_" + xCoor + ".ser";
                est.rgBuilder.serializeTo(outFileName);
                return;
            }
            else if (args[0].equalsIgnoreCase("TrimGraph")) {
                System.out.println("DelayEstimatorTable TrimGraph " + args[1] + " at x " + args[2]);
                est.rgBuilder.deserializeFrom(args[1] + ".ser");
                short xCoor = Short.parseShort(args[2]);
                est.trimTableAt(xCoor, yCoor, true);
                est.trimTableAt(xCoor, yCoor, false);
                String outFileName = args[1] + "_" + xCoor + ".ser";
                est.rgBuilder.serializeTo(outFileName);
                return;
            }
            else if (args[0].equalsIgnoreCase("MergeGraph")) {
                File file = new File(System.getProperty("user.dir"));
                String[] fileList = file.list();
                for(String name:fileList){
                    if (name.contains(args[1]+"_")) {
                        est.rgBuilder.merge(name);
                    }
                }
                est.rgBuilder.removeUnmarked();
                String outFileName = args[1] + "_merge" + ".ser";
                est.rgBuilder.serializeTo(outFileName);
                return;
            }
            else if (args[0].equalsIgnoreCase("LoadGraph")) {
                String inFileName = args[1] + "_merge" + ".ser";
                est.rgBuilder.deserializeFrom(inFileName);
            }
            else if (args[0].equalsIgnoreCase("Test")) {
                System.out.println("DelayEstimatorTable Write Trim Merge and Load Graph " + args[1]);
                est.rgBuilder.serializeTo(args[1] + ".ser");

                est.rgBuilder.deserializeFrom(args[1] + ".ser");
                short xCoor = 1;
                est.trimTableAt(xCoor, yCoor, true);
                est.trimTableAt(xCoor, yCoor, false);
                String outFileName = args[1] + "_" + xCoor + ".ser";
                est.rgBuilder.serializeTo(outFileName);

                File file = new File(System.getProperty("user.dir"));
                String[] fileList = file.list();
                for(String name:fileList){
                    if (name.contains(args[1]+"_")) {
                        est.rgBuilder.merge(name);
                    }
                }
                est.rgBuilder.removeUnmarked();
                outFileName = args[1] + "_merge" + ".ser";
                est.rgBuilder.serializeTo(outFileName);

                String inFileName = args[1] + "_merge" + ".ser";
                est.rgBuilder.deserializeFrom(inFileName);
            }
        }

//        est.rgBuilder.deserializeFrom("test_merge.ser");

//        est.rgBuilder.serializeTo(args[1] + ".ser");
//        est.rgBuilder.deserializeFrom(args[1] + ".ser");

//        est.testBounceToSink();
//        est.testSpecialCase(device);


//        long endBuildTime = System.nanoTime();
//        long elapsedBuildTime = endBuildTime - startTime;
//        System.out.print("Table build time is " + elapsedBuildTime / 1000000 + " ms.");
//
//
//        int count = 0;
//
//        long startLookupTime = System.nanoTime();
//        // diagonal in table
//        count += est.testCases("est_dly_ref_44_53_121_139_E_E.txt");
//        count += est.testCases("est_dly_ref_44_53_121_139_E_W.txt");
//        count += est.testCases("est_dly_ref_44_53_121_139_W_E.txt");
//        count += est.testCases("est_dly_ref_44_53_121_139_W_W.txt");
//
//        //  out of table
//        count += est.testCases("est_dly_ref_37_71_60_239_E_E_temp.txt");
//        count += est.testCases("est_dly_ref_37_71_60_239_E_W.txt");
//        count += est.testCases("est_dly_ref_37_71_60_239_W_E.txt");
//        count += est.testCases("est_dly_ref_37_71_60_239_W_W.txt");
//
//        long endLookupTime = System.nanoTime();
//        long elapsedLookupTime = endLookupTime - startLookupTime;
//
//
//        System.out.println();
//        System.out.println("Table build time is " + elapsedBuildTime / 1000000 + " ms.");
//        System.out.print("Execution time of " + count + " lookups is " + elapsedLookupTime / 1000000 + " ms.");
//        System.out.println(" (" +  1.0*elapsedLookupTime / (count * 1000) + " us. per lookup.)");

        est.testOne(37, 37, 60, 120, "E", "E");
////        est.testOne(37, 53, 60, 90, "E", "E");
////        est.testOne(37, 37, 90, 60, "E", "E");
////        est.testOne(37, 37, 60, 90, "E", "E");
//       est.testOne(44, 49, 123, 123, "W", "E");
////        est.testOne(44, 49, 123, 138, "E", "E");
////        est.testOne(44, 45, 121, 137, "E", "E");
////        est.testOne(44, 45, 124, 124, "E", "W");
//
//
////        est.verifyPath();

    }
}

// previous (onex) num nodes 8525, num edges 24874   0.490ms for in table
//                                                   0.57ms  for out table
//                                                   0.47ms  combine
// atx50   nodes 7435 edges 22829                    0.6ms in table
//                                                   crash out table
// sweepx  nodes 8399 edges 40615                    2.2ms in table
//                                                   crash out table
// need to try trim only for in table, both sweep and x50

