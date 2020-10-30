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
import com.xilinx.rapidwright.timing.GroupDelayType;
import com.xilinx.rapidwright.timing.ImmutableTimingGroup;
import com.xilinx.rapidwright.timing.TimingModel;
import com.xilinx.rapidwright.util.Pair;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The base class to implement a delay estimator.
 * Provide basic methods to build a customized estimator.
 */
public abstract class DelayEstimatorBase<T extends InterconnectInfo>  implements DelayEstimator {

    // Consider moving DelayEstimator as a member of TimingManager.
    // Until then, keep timingModel here for testing.

//        int result = est.getTimingModel().computeHorizontalDistFromArray(74,76, GroupDelayType.QUAD);
    // Concise
    // TODO: single and double have their own arrays. their values are the same.
    // Not all type has dist arrays. The arrays will never be addressed by those types.
    // distArrays are cumulative. It is also inclusive, ie.,
    // for segment spaning x-y,  d[y] is  included d of the segment.
    protected Map<T.Direction,Map<GroupDelayType, List<Short>>> distArrays;
    protected int numCol;
    protected int numRow;

    // TODO: Intend to move these to TimingModel
    protected Map<T.Direction,Map<GroupDelayType, Float>> K0;
    protected Map<T.Direction,Map<GroupDelayType, Float>> K1;
    protected Map<T.Direction,Map<GroupDelayType, Float>> K2;
    protected Map<T.Direction,Map<GroupDelayType, Short>> L;

    protected T     ictInfo;

    protected int   verbose;
    // this is only need DelayEstimatorTable.loadBounceDelay
    protected transient Device device;



        /**
         * Constructor from a device.
         * Package scope to disable creating DelayEstimatorBase by a user.
     * Create one using DelayEstimatorBuilder instead.
     * @param device target device.
     */
    DelayEstimatorBase(Device device, T ictInfo, int verbose) {
        this.device  = device;
        this.verbose = verbose;
        this.ictInfo = ictInfo;
        // Is it ok to build timingModel again in here?
        TimingModel timingModel = new TimingModel(device);
        timingModel.build();
        buildDistanceArrays(timingModel);
    }

    DelayEstimatorBase(Device device, T ictInfo) {
        this(device, ictInfo, 0);

    }


    public short getDelayOf(ImmutableTimingGroup tg) {
        RoutingNode node = getTermInfo(tg);
        Double delay = calcTimingGroupDelay(node.tg, node.begin(), node.end(), 0d);
        return delay.shortValue();
    }

    protected class RoutingNode {
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

        public short begin() {
            return tg.direction() == T.Direction.HORIZONTAL ? x : y;
        }

        public short end() {
            short delta = tg.length();
            short start = tg.direction() == T.Direction.HORIZONTAL ? x : y;
            return  (short) (start + (orientation == T.Orientation.U ? delta : -delta));
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
    protected RoutingNode getTermInfo(ImmutableTimingGroup tg) {

        Node node = tg.exitNode();
        IntentCode ic = node.getAllWiresInNode()[0].getIntentCode();
        Pattern tilePattern = Pattern.compile("X([\\d]+)Y([\\d]+)");
        Pattern EE          = Pattern.compile("EE([\\d]+)");
        Pattern WW          = Pattern.compile("WW([\\d]+)");
        Pattern NN          = Pattern.compile("NN([\\d]+)");
        Pattern SS          = Pattern.compile("SS([\\d]+)");

        // WW1_E is an exception. It is actually going north!
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

        String nodeType = null;
        if (skip.matcher(int_node[1]).find())
            nodeType = "NN1_E";
        else
            nodeType = int_node[1];

        String[] tg_side = nodeType.split("_");

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
        if (nodeType.startsWith("INT")) {
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


    @FunctionalInterface
    interface BuildAccumulativeList<T> {
        List<T> apply(List<T> l);
    }

    /**
     * Constructor from a part name.
     * @param partName string specifying a valid part, ie., "xcvu3p-ffvc1517"
     * Package scope to disable creating DelayEstimatorBase by a user.
     * Create one using DelayEstimatorBuilder instead.
     */
//    DelayEstimatorBase(String partName) {
//        this(Device.getDevice(partName));
//    }

    /**
     * DistanceArray in TimingModel is using INT tile coordinate.
     * Convert the arrays to INT tile coordinate.
     */
    private void buildDistanceArrays(TimingModel tm) {
        boolean usePMTable = false;

        // TODO: somehow I cannot use Function<T,R>. I get "target method is generic" error.
        BuildAccumulativeList<Short> buildAccumulativeList = (list) ->
        {
            Short acc = 0;
            List<Short> res = new ArrayList<>();
            for (Short val : list) {
                res.add((short) (acc + val));
            }
            return res;
        };

        distArrays = new EnumMap<> (InterconnectInfo.Direction.class);
        for (T.Direction d : T.Direction.values()) {
            distArrays.put(d, new EnumMap<GroupDelayType,List<Short>> (GroupDelayType.class));
            // intentionally populated only these type so that accicentally access other types will cause runtime error.
            distArrays.get(d).put(GroupDelayType.SINGLE, new ArrayList<Short>());
            distArrays.get(d).put(GroupDelayType.DOUBLE, new ArrayList<Short>());
            distArrays.get(d).put(GroupDelayType.QUAD,   new ArrayList<Short>());
            distArrays.get(d).put(GroupDelayType.LONG,   new ArrayList<Short>());
        }

        if (usePMTable) {
            String verFileName = "vertical_distance_array.txt";
            String horFileName = "horizontal_distance_array.txt";

            List<GroupDelayType> orderInFile = new ArrayList<GroupDelayType>() {{
                add(GroupDelayType.SINGLE);
                add(GroupDelayType.DOUBLE);
                add(GroupDelayType.QUAD);
                add(GroupDelayType.LONG);
            }};
            List<Pair<String,T.Direction>> ops = new ArrayList<Pair<String,T.Direction>>() {{
                add(new Pair<>(verFileName,T.Direction.VERTICAL));
                add(new Pair<>(horFileName,T.Direction.HORIZONTAL));
            }};

            for (int j = 0; j < 2; j++) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(ops.get(j).getFirst()));
                    String line = reader.readLine();
                    int i = 0;
                    while (line != null) {
                        List<String> listOfString = Arrays.asList(line.split("\\s+"));
                        List<Short> listOfShort = listOfString.stream()
                                .map(s -> Short.parseShort(s))
                                .collect(Collectors.toList());

                        distArrays.get(ops.get(j).getSecond()).put(orderInFile.get(i), listOfShort);
                        i++;
                        line = reader.readLine();

//                        System.out.println(listOfShort);
                    }
                } catch (IOException e) {
                    System.out.println("Can't open file " + verFileName + " for read.");
                    e.printStackTrace();
                }
            }
        } else {

            Map<GroupDelayType, List<Short>> verDistArray = tm.getVerDistArrayInIntTileGrid();
            Map<GroupDelayType, List<Short>> horDistArray = tm.getHorDistArrayInIntTileGrid();

            for (GroupDelayType t : GroupDelayType.values()) {
                distArrays.get(T.Direction.VERTICAL).put(t, buildAccumulativeList.apply(verDistArray.get(t)));
            }
            for (GroupDelayType t : GroupDelayType.values()) {
                distArrays.get(T.Direction.HORIZONTAL).put(t, buildAccumulativeList.apply(horDistArray.get(t)));
            }
        }

        // TODO: Do I need pinfeed ?

        numCol = distArrays.get(T.Direction.HORIZONTAL).get(GroupDelayType.SINGLE).size();
        numRow = distArrays.get(T.Direction.VERTICAL).get(GroupDelayType.SINGLE).size();
        distArrays.get(T.Direction.INPUT).put(GroupDelayType.PINFEED, new ArrayList<Short>(Collections.nCopies(Math.max(numRow,numCol), (short) 0)));
        distArrays.get(T.Direction.LOCAL).put(GroupDelayType.PIN_BOUNCE, new ArrayList<Short>(Collections.nCopies(Math.max(numRow,numCol), (short) 0)));
        distArrays.get(T.Direction.OUTPUT).put(GroupDelayType.OTHER, new ArrayList<Short>(Collections.nCopies(Math.max(numRow,numCol), (short) 0)));
        distArrays.get(T.Direction.HORIZONTAL).put(GroupDelayType.GLOBAL, new ArrayList<Short>(Collections.nCopies(Math.max(numRow,numCol), (short) 0)));


        if (false) {
//        distArrays = new EnumMap<> (InterconnectInfo.Direction.class);
//        Map<Orientation,Map<GroupDelayType, List<Short>>> orientationMap = new EnumMap<>(Orientation.class);
//
//        {
//            Map<GroupDelayType, List<Short>> thor = new EnumMap<GroupDelayType, List<Short>>(GroupDelayType.class);
//
//            thor.put(GroupDelayType.SINGLE, new ArrayList<Short>());
//            thor.get(GroupDelayType.SINGLE).add((short) 0);
//            thor.get(GroupDelayType.SINGLE).add((short) 0);
//            thor.get(GroupDelayType.SINGLE).add((short) 0);
//            for (int i = 0; i < 20; i++)
//                thor.get(GroupDelayType.SINGLE).add((short) 3);
//
//            thor.put(GroupDelayType.DOUBLE, new ArrayList<Short>());
//            thor.get(GroupDelayType.DOUBLE).add((short) 0);
//            thor.get(GroupDelayType.DOUBLE).add((short) 0);
//            thor.get(GroupDelayType.DOUBLE).add((short) 0);
//            for (int i = 0; i < 20; i++)
//                thor.get(GroupDelayType.DOUBLE).add((short) 3);
//
//            thor.put(GroupDelayType.QUAD, new ArrayList<Short>());
//            thor.get(GroupDelayType.QUAD).add((short) 0);
//            thor.get(GroupDelayType.QUAD).add((short) 0);
//            thor.get(GroupDelayType.QUAD).add((short) 0);
//            for (int i = 0; i < 20; i++)
//                thor.get(GroupDelayType.QUAD).add((short) 90);
//
//            thor.put(GroupDelayType.LONG, new ArrayList<Short>());
//            thor.get(GroupDelayType.LONG).add((short) 0);
//            thor.get(GroupDelayType.LONG).add((short) 0);
//            thor.get(GroupDelayType.LONG).add((short) 0);
//            for (int i = 0; i < 20; i++)
//                thor.get(GroupDelayType.LONG).add((short) 3);
//
//            orientationMap.put(Orientation.FORWARD, thor);
//            distArrays.put(InterconnectInfo.Direction.HORIZONTAL, orientationMap);
//        }
//        {
//            Map<GroupDelayType, List<Short>> thor = new EnumMap<GroupDelayType, List<Short>>(GroupDelayType.class);
//            thor.put(GroupDelayType.PINFEED, new ArrayList<Short>());
//            thor.get(GroupDelayType.PINFEED).add((short) 0);
//            thor.get(GroupDelayType.PINFEED).add((short) 0);
//            thor.get(GroupDelayType.PINFEED).add((short) 0);
//            for (int i = 0; i < 20; i++)
//                thor.get(GroupDelayType.PINFEED).add((short) 0);
//            orientationMap.put(Orientation.FORWARD, thor);
//            distArrays.put(InterconnectInfo.Direction.INPUT, orientationMap);
//        }
        }

        // TODO: move it to interconectInfo

        K0 = new EnumMap<T.Direction,Map<GroupDelayType, Float>>(T.Direction.class);
        K1 = new EnumMap<T.Direction,Map<GroupDelayType, Float>>(T.Direction.class);
        K2 = new EnumMap<T.Direction,Map<GroupDelayType, Float>>(T.Direction.class);
        L  = new EnumMap<T.Direction,Map<GroupDelayType, Short>>(T.Direction.class);

        // TODO: convert from TimingModel
        // I could read it from files, but want to not diverge over time.
        // TG delay = k0 + k1 * L + k2 * d;
        {
            Map<GroupDelayType, Float> tk0 = new EnumMap<>(GroupDelayType.class);
            tk0.put(GroupDelayType.SINGLE, 43f);
            tk0.put(GroupDelayType.DOUBLE, 43f);
            tk0.put(GroupDelayType.QUAD, 43f);
            tk0.put(GroupDelayType.LONG, 43f);
            tk0.put(GroupDelayType.GLOBAL, 43f);
            K0.put(T.Direction.HORIZONTAL, tk0);

            Map<GroupDelayType, Float> tk1 = new EnumMap<>(GroupDelayType.class);
            tk1.put(GroupDelayType.SINGLE, 3.5f);
            tk1.put(GroupDelayType.DOUBLE, 3.5f);
            tk1.put(GroupDelayType.QUAD, 3.5f);
            tk1.put(GroupDelayType.LONG, 3.5f);
            tk1.put(GroupDelayType.GLOBAL, 3.5f);
            K1.put(T.Direction.HORIZONTAL, tk1);

            Map<GroupDelayType, Float> tk2 = new EnumMap<>(GroupDelayType.class);
            tk2.put(GroupDelayType.SINGLE, 2.3f);
            tk2.put(GroupDelayType.DOUBLE, 2.3f);
            tk2.put(GroupDelayType.QUAD, 2.4f);
            tk2.put(GroupDelayType.LONG, 1.3f);
            tk2.put(GroupDelayType.GLOBAL, 1.3f);
            K2.put(T.Direction.HORIZONTAL, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<>(GroupDelayType.class);
            tl.put(GroupDelayType.SINGLE, (short) 1);
            tl.put(GroupDelayType.DOUBLE, (short) 5);
            tl.put(GroupDelayType.QUAD, (short) 10);
            tl.put(GroupDelayType.LONG, (short) 14);
            tl.put(GroupDelayType.GLOBAL, (short) 13);
            L.put(T.Direction.HORIZONTAL, tl);
        }

        {
            Map<GroupDelayType, Float> tk0 = new EnumMap<>(GroupDelayType.class);
            tk0.put(GroupDelayType.SINGLE, 43f);
            tk0.put(GroupDelayType.DOUBLE, 43f);
            tk0.put(GroupDelayType.QUAD, 43f);
            tk0.put(GroupDelayType.LONG, 43f);
            K0.put(T.Direction.VERTICAL, tk0);

            Map<GroupDelayType, Float> tk1 = new EnumMap<>(GroupDelayType.class);
            tk1.put(GroupDelayType.SINGLE, 3.6f);
            tk1.put(GroupDelayType.DOUBLE, 3.6f);
            tk1.put(GroupDelayType.QUAD, 3.6f);
            tk1.put(GroupDelayType.LONG, 3.6f);
            K1.put(T.Direction.VERTICAL, tk1);

            Map<GroupDelayType, Float> tk2 = new EnumMap<>(GroupDelayType.class);
            tk2.put(GroupDelayType.SINGLE, 13.5f);
            tk2.put(GroupDelayType.DOUBLE, 5.5f);
            tk2.put(GroupDelayType.QUAD, 9.5f);
            tk2.put(GroupDelayType.LONG, 3.5f);
            K2.put(T.Direction.VERTICAL, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tl.put(GroupDelayType.SINGLE, (short) 1);
            tl.put(GroupDelayType.DOUBLE, (short) 3);
            tl.put(GroupDelayType.QUAD, (short) 5);
            tl.put(GroupDelayType.LONG, (short) 12);
            L.put(T.Direction.VERTICAL, tl);
        }
        {
            Map<GroupDelayType, Float> tk0 = new EnumMap<>(GroupDelayType.class);
            tk0.put(GroupDelayType.PINFEED, 43f);
            K0.put(T.Direction.INPUT, tk0);

            Map<GroupDelayType, Float> tk1 = new EnumMap<>(GroupDelayType.class);
            tk1.put(GroupDelayType.PINFEED, 0f);
            K1.put(T.Direction.INPUT, tk1);

            Map<GroupDelayType, Float> tk2 = new EnumMap<>(GroupDelayType.class);
            tk2.put(GroupDelayType.PINFEED, 0f);
            K2.put(T.Direction.INPUT, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<>(GroupDelayType.class);
            tl.put(GroupDelayType.PINFEED, (short) 0);
            L.put(T.Direction.INPUT, tl);
        }
        {
            Map<GroupDelayType, Float> tk0 = new EnumMap<>(GroupDelayType.class);
            tk0.put(GroupDelayType.PIN_BOUNCE, 43f);
            K0.put(T.Direction.LOCAL, tk0);

            Map<GroupDelayType, Float> tk1 = new EnumMap<>(GroupDelayType.class);
            tk1.put(GroupDelayType.PIN_BOUNCE, 0f);
            K1.put(T.Direction.LOCAL, tk1);

            Map<GroupDelayType, Float> tk2 = new EnumMap<>(GroupDelayType.class);
            tk2.put(GroupDelayType.PIN_BOUNCE, 0f);
            K2.put(T.Direction.LOCAL, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<>(GroupDelayType.class);
            tl.put(GroupDelayType.PIN_BOUNCE, (short) 0);
            L.put(T.Direction.LOCAL, tl);
        }
        {
            Map<GroupDelayType, Float> tk0 = new EnumMap<>(GroupDelayType.class);
            tk0.put(GroupDelayType.OTHER, 0f);
            K0.put(T.Direction.OUTPUT, tk0);

            Map<GroupDelayType, Float> tk1 = new EnumMap<>(GroupDelayType.class);
            tk1.put(GroupDelayType.OTHER, 0f);
            K1.put(T.Direction.OUTPUT, tk1);

            Map<GroupDelayType, Float> tk2 = new EnumMap<>(GroupDelayType.class);
            tk2.put(GroupDelayType.OTHER, 0f);
            K2.put(T.Direction.OUTPUT, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<>(GroupDelayType.class);
            tl.put(GroupDelayType.OTHER, (short) 0);
            L.put(T.Direction.OUTPUT, tl);
        }
    }

    protected boolean isSwitchingSide(TimingGroupEdge e) {
        T.TimingGroup tg = e.getTimingGroup();
        if (tg != null &&
            (tg == T.TimingGroup.HORT_LONG || tg == T.TimingGroup.VERT_LONG || tg.type() == GroupDelayType.PIN_BOUNCE))
            return true;
        else
            return false;
    }

    /**
     * Print info when the delay at a node decreases
     * @param e Edge that cause the decrement.
     * @param dly The updated delay
     * @param isBackward Direction of the edge
     * @return
     */
    protected double updateVertex(TimingGroupEdge e, Double dly, boolean isBackward) {

        boolean isReverseDirection =  e.isReverseDirection() ^ isBackward;
        if (verbose > 4) {
            T.TimingGroup tg = e.getTimingGroup();

            System.out.printf("          **updateVtx  %11s   rev %5s  bwd %5s  len %2d dly %3d\n",
                    tg.name(), e.isReverseDirection(), isBackward, tg.length(), dly.shortValue());
        }
        return 0f;
    }

    /**
     * Compute the location of the target node of the edge. This is called once when the target node is first seen.
     * @param e The edge
     * @param x x coordinate of the source of the edge leading to this node
     * @param x y coordinate of the source of the edge leading to this node
     * @param isBackward Direction of the edge
     * @return Location at the targetof the edge
     */
    protected short[] discoverVertex(TimingGroupEdge e, short x, short y, Double dly, boolean isBackward) {
//        // TRY
//        if (e.getTimingGroup() == null)
//            return loc;

        short[] newXY = new short[2];
        boolean isReverseDirection =  e.isReverseDirection() ^ isBackward;
        if (e.getTimingGroup().direction() == T.Direction.VERTICAL) {
            newXY[0] = x;
            newXY[1] = (short) (y + (isReverseDirection ? -e.getTimingGroup().length() : e.getTimingGroup().length()));
        } else {
            newXY[0] = (short) (x + (isReverseDirection ? -e.getTimingGroup().length() : e.getTimingGroup().length()));
            newXY[1] = y;
        }
        if (verbose > 4) {
            T.TimingGroup tg = e.getTimingGroup();

            System.out.printf("          findVtx fr e %11s   rev %5s  bwd %5s" +
                    "                                len %2d  begLoc %3d,%3d  endLoc %3d,%3d   dly %4d\n",
                    tg.name(),  e.isReverseDirection(), isBackward,
                    tg.length(), x,y,newXY[0],newXY[0], dly.shortValue());
        }
        return newXY;
    }
    /**
     * Compute delay of an edge
     * @param e The current timing group.
     *
     * @param loc The INT tile index at the beginning of the timing group.
     * @return  The delay of this timing group.
     */

    /**
     * Compute delay of an edge
     * @param e The edge
     * @param u The target of this edge
     * @param dst The final destination
     * @param x x coordinate of the source of the edge
     * @param x y coordinate of the source of the edge
     * @param dly delay from the start to the source of the edge
     * @param isBackward
     * @return
     */
    protected double calcTimingGroupDelayOnEdge(TimingGroupEdge e, Object u, Object dst, short x, short y, Double dly,
                                                boolean isBackward) {
        // For testing, to not explore known high delay
//        if (dly > 444) {
//            return Short.MAX_VALUE / 2;
//        }


        // return calcTimingGroupDelay(e.getTimingGroup(), loc.shortValue(), e.isReverseDirection() ^ isBackward);
        T.TimingGroup tg = e.getTimingGroup();
        if (tg == null)
            return 0;
//        short begLoc = loc.shortValue();
        // although TileSide inversion was applied to backward net inverting, isBackward is need here to get the right d
        boolean isReverseDirection = e.isReverseDirection() ^ isBackward;
//

//        int limit = 0;
//        if (tg.direction() == InterconnectInfo.Direction.VERTICAL) {
//            limit = numRow;
//        } else if (tg.direction() == InterconnectInfo.Direction.HORIZONTAL) {
//            limit = numCol;
//        } else {
//            limit = Math.max(numRow,numCol);
//        }
//
        if (verbose > 4) {
            System.out.printf("          examineEdge  %11s   rev %5s  bwd %5s        ",
                    tg.name(), e.isReverseDirection(), isBackward);
        }
//
//        short endLoc = (short) (begLoc + (isReverseDirection ? -tg.length() : tg.length()));
//        if ((endLoc >= limit) || (endLoc < 0)) {
//            // Can't do MAX_VALUE as adding that to other value will become negative.
//            // TODO: consider using INT as intemediate computation
//            if (verbose > 4)
//                System.out.println("endLoc " + endLoc + " is out of range (0," + limit);
//            return Short.MAX_VALUE/2;
//        }

        // TODO remove this and call directly
        short begLoc = (tg.direction() == T.Direction.VERTICAL) ? y : x;
        short endLoc = (short) (begLoc + (isReverseDirection ? -tg.length() : tg.length()));

        return calcTimingGroupDelay(tg, begLoc, endLoc, dly);
    }

    /**
     *
     * @param tg
     * @param begLoc
     * @param endLoc
     * @return delay of the tg. return negative (-1.0) to indicate the tg is out of device bound and should be ignored.
     */
    protected double calcTimingGroupDelay(T.TimingGroup tg, short begLoc, short endLoc, Double dly) {
//        if (tg == T.TimingGroup.CLE_OUT) {
//            if (verbose > 5)
//                System.out.println();
//            return (short) 0;
//        }
        int size = (tg.direction() == T.Direction.VERTICAL) ? numRow : numCol;
        if (endLoc < 0 || endLoc >= size || begLoc < 0 || begLoc >= size)
            return -1.0;

        float k0 = K0.get(tg.direction()).get(tg.type());
        float k1 = K1.get(tg.direction()).get(tg.type());
        float k2 = K2.get(tg.direction()).get(tg.type());
        short l  = L .get(tg.direction()).get(tg.type());

        short st  = distArrays.get(tg.direction()).get(tg.type()).get(begLoc);
        short sp  = distArrays.get(tg.direction()).get(tg.type()).get(endLoc);

        // need abs in case the tg is going to the left.
        short del  = (short) (k0 + k1 * l + k2 * Math.abs(sp-st));
        if (verbose > 5) {
            System.out.printf("calTiming %11s   len %2d  begLoc %3d  endLoc %3d  ",
                    tg.name(), tg.length(), begLoc, endLoc);
            System.out.printf(" dly %4d   k0 %3.1f k1 %3.1f k2 %4.1f   l %2d   d %3d   dst %3d   dsp %3d" +
                            "    begDly %4d endDly %4d\n",
                    del, k0, k1, k2, l, (sp - st), st, sp, dly.shortValue(), dly.shortValue()+del);
        }
        return del;
    }


    /**
     * To remove effect of distance during some part of the test.
     */
    protected void zeroDistArrays() {
        System.out.println("zeroDistArrays before");
        System.out.println(distArrays);

        for (T.Direction d : T.Direction.values()) {
            Map<GroupDelayType, List<Short>> mg = distArrays.get(d);
            for (GroupDelayType t : GroupDelayType.values()) {
                if (mg.get(t) != null) {
                    List<Short> nls = new ArrayList<Short>(Collections.nCopies(mg.get(t).size(), (short) 0));
                    mg.put(t, nls);
                } else {
                    System.out.println("skip " + t.name());
                }
            }
        }

        System.out.println("zeroDistArrays after");
        System.out.println(distArrays);
    }
}






























