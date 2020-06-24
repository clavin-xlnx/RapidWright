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
import com.xilinx.rapidwright.timing.TimingModel;
import com.xilinx.rapidwright.util.Pair;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
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

    enum TileSide {
        E,
        W,
        M
    };

        /**
         * Constructor from a device.
         * Package scope to disable creating DelayEstimatorBase by a user.
     * Create one using DelayEstimatorBuilder instead.
     * @param device target device.
     */
    DelayEstimatorBase(Device device, T ictInfo, int verbose) {
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

        boolean usePMTable = true;

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

                        System.out.println(listOfShort);
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
            K0.put(T.Direction.HORIZONTAL, tk0);

            Map<GroupDelayType, Float> tk1 = new EnumMap<>(GroupDelayType.class);
            tk1.put(GroupDelayType.SINGLE, 3.5f);
            tk1.put(GroupDelayType.DOUBLE, 3.5f);
            tk1.put(GroupDelayType.QUAD, 3.5f);
            tk1.put(GroupDelayType.LONG, 3.5f);
            K1.put(T.Direction.HORIZONTAL, tk1);

            Map<GroupDelayType, Float> tk2 = new EnumMap<>(GroupDelayType.class);
            tk2.put(GroupDelayType.SINGLE, 2.3f);
            tk2.put(GroupDelayType.DOUBLE, 2.3f);
            tk2.put(GroupDelayType.QUAD, 2.4f);
            tk2.put(GroupDelayType.LONG, 1.3f);
            K2.put(T.Direction.HORIZONTAL, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<>(GroupDelayType.class);
            tl.put(GroupDelayType.SINGLE, (short) 1);
            tl.put(GroupDelayType.DOUBLE, (short) 5);
            tl.put(GroupDelayType.QUAD, (short) 10);
            tl.put(GroupDelayType.LONG, (short) 14);
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

            System.out.printf("          **updateVtx %11s   rev %5s  bwd %5s  len %2d dly %3d\n",
                    tg.name(), e.isReverseDirection(), isBackward, tg.length(), dly.shortValue());
        }
        return 0f;
    }

    /**
     * Compute the location of the target node of the edge. This is called once when the target node is first seen.
     * @param e The edge
     * @param loc Location at the source of the edge
     * @param isBackward Direction of the edge
     * @return Location at the targetof the edge
     */
    protected double discoverVertex(TimingGroupEdge e, Double loc, Double dly, boolean isBackward) {
        // TRY
        if (e.getTimingGroup() == null)
            return loc;


        boolean isReverseDirection =  e.isReverseDirection() ^ isBackward;
        Double newLoc = loc + (isReverseDirection ? -e.getTimingGroup().length() : e.getTimingGroup().length());
        if (verbose > 4) {
            T.TimingGroup tg = e.getTimingGroup();

            System.out.printf("          discoverVtx %11s   rev %5s  bwd %5s" +
                    "                                          len %2d  begLoc %3d  endLoc %3d   dly %4d\n",
                    tg.name(),  e.isReverseDirection(), isBackward,
                    tg.length(), loc.shortValue(), newLoc.shortValue(), dly.shortValue());
        }
        return newLoc;
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
     * @param dst
     * @param loc
     * @param isBackward
     * @return
     */
    protected double calcTimingGroupDelayOnEdge(TimingGroupEdge e, Object u, Object dst, Double loc, Double dly, boolean isBackward) {
//        boolean dbg = false;
//        if (e.getTimingGroup() == InterconnectInfo.TimingGroup.HORT_LONG)
//            dbg = true;

        if (u == dst)
            return 0;
        else {
           // return calcTimingGroupDelay(e.getTimingGroup(), loc.shortValue(), e.isReverseDirection() ^ isBackward);
            T.TimingGroup tg = e.getTimingGroup();
            if (tg == null)
                return 0;
            short begLoc = loc.shortValue();
            boolean isReverseDirection = e.isReverseDirection() ^ isBackward;

            int limit = 0;
            if (tg.direction() == InterconnectInfo.Direction.VERTICAL) {
                limit = numRow;
            } else if (tg.direction() == InterconnectInfo.Direction.HORIZONTAL) {
                limit = numCol;
            } else {
                limit = Math.max(numRow,numCol);
            }

            short endLoc = (short) (begLoc + (isReverseDirection ? -tg.length() : tg.length()));
            if ((endLoc >= limit) || (endLoc < 0)) {
                // Can't do MAX_VALUE as adding that to other value will become negative.
                // TODO: consider using INT as intemediate computation
                return Short.MAX_VALUE/2;
            }

            if (verbose > 4) {
                System.out.printf("          examineEdge %11s   rev %5s  bwd %5s        ",
                        tg.name(), e.isReverseDirection(), isBackward);
            }

            return calcTimingGroupDelay(tg, begLoc, endLoc, dly);
        }
    }

    /**
     *
     * @param tg
     * @param begLoc
     * @param endLoc
     * @return
     */
    protected double calcTimingGroupDelay(T.TimingGroup tg, short begLoc, short endLoc, Double dly) {

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
            System.out.printf(" k0 %3.1f k1 %3.1f k2 %4.1f   l %2d   d %3d   dst %3d   dsp %3d" +
                            "    del %4d   begDly %4d endDly %4d\n",
                    k0, k1, k2, l, (sp - st), st, sp, del, dly.shortValue(), dly.shortValue()+del);
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






























