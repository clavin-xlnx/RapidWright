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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    protected Map<T.Direction,Map<Orientation,Map<GroupDelayType, List<Short>>>> distArrays;
    protected Map<T.Direction,Map<GroupDelayType, Short>> K0;
    protected Map<T.Direction,Map<GroupDelayType, Short>> K1;
    protected Map<T.Direction,Map<GroupDelayType, Short>> K2;
    protected Map<T.Direction,Map<GroupDelayType, Short>> L;

    enum TileSide {
        E,
        W
    };
    enum Orientation {
        FORWARD,
        BACKWARD
    };


        /**
         * Constructor from a device.
         * Package scope to disable creating DelayEstimatorBase by a user.
     * Create one using DelayEstimatorBuilder instead.
     * @param device target device.
     */
    DelayEstimatorBase(Device device) {
        TimingModel timingModel = new TimingModel(device);
        timingModel.build();
        buildDistanceArrays(timingModel);
    }

    /**
     * Constructor from a part name.
     * @param partName string specifying a valid part, ie., "xcvu3p-ffvc1517"
     * Package scope to disable creating DelayEstimatorBase by a user.
     * Create one using DelayEstimatorBuilder instead.
     */
    DelayEstimatorBase(String partName) {
        this(Device.getDevice(partName));
    }

    /**
     * DistanceArray in TimingModel is using INT tile coordinate.
     * Convert the arrays to INT tile coordinate.
     */
    private void buildDistanceArrays(TimingModel tm) {

//        // TODO: convert from timingModel

        distArrays = new EnumMap<> (InterconnectInfo.Direction.class);
        Map<Orientation,Map<GroupDelayType, List<Short>>> orientationMap = new EnumMap<>(Orientation.class);

        {
            Map<GroupDelayType, List<Short>> thor = new EnumMap<GroupDelayType, List<Short>>(GroupDelayType.class);

            thor.put(GroupDelayType.SINGLE, new ArrayList<Short>());
            thor.get(GroupDelayType.SINGLE).add((short) 0);
            thor.get(GroupDelayType.SINGLE).add((short) 0);
            thor.get(GroupDelayType.SINGLE).add((short) 0);
            for (int i = 0; i < 20; i++)
                thor.get(GroupDelayType.SINGLE).add((short) 3);

            thor.put(GroupDelayType.DOUBLE, new ArrayList<Short>());
            thor.get(GroupDelayType.DOUBLE).add((short) 0);
            thor.get(GroupDelayType.DOUBLE).add((short) 0);
            thor.get(GroupDelayType.DOUBLE).add((short) 0);
            for (int i = 0; i < 20; i++)
                thor.get(GroupDelayType.DOUBLE).add((short) 3);

            thor.put(GroupDelayType.QUAD, new ArrayList<Short>());
            thor.get(GroupDelayType.QUAD).add((short) 0);
            thor.get(GroupDelayType.QUAD).add((short) 0);
            thor.get(GroupDelayType.QUAD).add((short) 0);
            for (int i = 0; i < 20; i++)
                thor.get(GroupDelayType.QUAD).add((short) 90);

            thor.put(GroupDelayType.LONG, new ArrayList<Short>());
            thor.get(GroupDelayType.LONG).add((short) 0);
            thor.get(GroupDelayType.LONG).add((short) 0);
            thor.get(GroupDelayType.LONG).add((short) 0);
            for (int i = 0; i < 20; i++)
                thor.get(GroupDelayType.LONG).add((short) 3);

            orientationMap.put(Orientation.FORWARD, thor);
            distArrays.put(InterconnectInfo.Direction.HORIZONTAL, orientationMap);
        }
        {
            Map<GroupDelayType, List<Short>> thor = new EnumMap<GroupDelayType, List<Short>>(GroupDelayType.class);
            thor.put(GroupDelayType.PINFEED, new ArrayList<Short>());
            thor.get(GroupDelayType.PINFEED).add((short) 0);
            thor.get(GroupDelayType.PINFEED).add((short) 0);
            thor.get(GroupDelayType.PINFEED).add((short) 0);
            for (int i = 0; i < 20; i++)
                thor.get(GroupDelayType.PINFEED).add((short) 0);
            orientationMap.put(Orientation.FORWARD, thor);
            distArrays.put(InterconnectInfo.Direction.INPUT, orientationMap);
        }

        // TODO: move it to interconectInfo

        K0 = new EnumMap<T.Direction,Map<GroupDelayType, Short>>(T.Direction.class);
        K1 = new EnumMap<T.Direction,Map<GroupDelayType, Short>>(T.Direction.class);
        K2 = new EnumMap<T.Direction,Map<GroupDelayType, Short>>(T.Direction.class);
        L  = new EnumMap<T.Direction,Map<GroupDelayType, Short>>(T.Direction.class);

        // TODO: convert from TimingModel
        // I could read it from files, but want to not diverge over time.
        // TG delay = k0 + k1 * L + k2 * d;
        {
            Map<GroupDelayType, Short> tk0 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tk0.put(GroupDelayType.SINGLE, (short) 43);
            tk0.put(GroupDelayType.DOUBLE, (short) 43);
            tk0.put(GroupDelayType.QUAD, (short) 43);
            tk0.put(GroupDelayType.LONG, (short) 43);
            K0.put(T.Direction.HORIZONTAL, tk0);

            Map<GroupDelayType, Short> tk1 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tk1.put(GroupDelayType.SINGLE, (short) 4);
            tk1.put(GroupDelayType.DOUBLE, (short) 4);
            tk1.put(GroupDelayType.QUAD, (short) 4);
            tk1.put(GroupDelayType.LONG, (short) 4);
            K1.put(T.Direction.HORIZONTAL, tk1);

            Map<GroupDelayType, Short> tk2 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tk2.put(GroupDelayType.SINGLE, (short) 2);
            tk2.put(GroupDelayType.DOUBLE, (short) 2);
            tk2.put(GroupDelayType.QUAD, (short) 2);
            tk2.put(GroupDelayType.LONG, (short) 1);
            K2.put(T.Direction.HORIZONTAL, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tl.put(GroupDelayType.SINGLE, (short) 1);
            tl.put(GroupDelayType.DOUBLE, (short) 5);
            tl.put(GroupDelayType.QUAD, (short) 10);
            tl.put(GroupDelayType.LONG, (short) 14);
            L.put(T.Direction.HORIZONTAL, tl);
        }

        {
            Map<GroupDelayType, Short> tk0 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tk0.put(GroupDelayType.SINGLE, (short) 43);
            tk0.put(GroupDelayType.DOUBLE, (short) 43);
            tk0.put(GroupDelayType.QUAD, (short) 43);
            tk0.put(GroupDelayType.LONG, (short) 43);
            K0.put(T.Direction.VERTICAL, tk0);

            Map<GroupDelayType, Short> tk1 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tk1.put(GroupDelayType.SINGLE, (short) 4);
            tk1.put(GroupDelayType.DOUBLE, (short) 4);
            tk1.put(GroupDelayType.QUAD, (short) 4);
            tk1.put(GroupDelayType.LONG, (short) 4);
            K1.put(T.Direction.VERTICAL, tk1);

            Map<GroupDelayType, Short> tk2 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tk2.put(GroupDelayType.SINGLE, (short) 14);
            tk2.put(GroupDelayType.DOUBLE, (short) 6);
            tk2.put(GroupDelayType.QUAD, (short) 10);
            tk2.put(GroupDelayType.LONG, (short) 4);
            K2.put(T.Direction.VERTICAL, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tl.put(GroupDelayType.SINGLE, (short) 1);
            tl.put(GroupDelayType.DOUBLE, (short) 3);
            tl.put(GroupDelayType.QUAD, (short) 5);
            tl.put(GroupDelayType.LONG, (short) 12);
            L.put(T.Direction.VERTICAL, tl);
        }
        {
            Map<GroupDelayType, Short> tk0 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tk0.put(GroupDelayType.PINFEED, (short) 43);
            K0.put(T.Direction.INPUT, tk0);

            Map<GroupDelayType, Short> tk1 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tk1.put(GroupDelayType.PINFEED, (short) 0);
            K1.put(T.Direction.INPUT, tk1);

            Map<GroupDelayType, Short> tk2 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tk2.put(GroupDelayType.PINFEED, (short) 0);
            K2.put(T.Direction.INPUT, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
            tl.put(GroupDelayType.PINFEED, (short) 0);
            L.put(T.Direction.INPUT, tl);
        }
    }

    /**
     *
     * @param e The current timing group.
     * @param v The INT tile index at the beginning of the timing group.
     * @return  The delay of this timing group.
     */
    protected double calcTimingGroupDelay(TimingGroupEdge e, Double v, Orientation orientation) {
        T.TimingGroup tg = e.getTimingGroup();
        System.out.println("calcTimingGroupDelay " + tg.name() + " " + v );
        short k0 = K0.get(tg.direction()).get(tg.type());
        short k1 = K1.get(tg.direction()).get(tg.type());
        short k2 = K2.get(tg.direction()).get(tg.type());
        short l  = L .get(tg.direction()).get(tg.type());

        short st  = distArrays.get(tg.direction()).get(orientation).get(tg.type()).get(v.shortValue());
        short sp  = distArrays.get(tg.direction()).get(orientation).get(tg.type()).get(v.shortValue() + tg.length());

        // need abs in case the tg is going to the left.
        short del  = (short) (k0 + k1 * l + k2 * Math.abs(sp-st));
//        System.out.println(tg.name());
//        System.out.println("   v " + v + " len " + tg.length() + "  calc " + tg.name() +
//                " k0 " + k0 + " k1 " + k1 + " l " + l + " k2 " + k2 + " d " + (sp-st) + " del " + del +
//                "         fr " + v.shortValue() +  " to " + (v.shortValue() + tg.length()) + " st " + st + " sp " + sp);

        return del;
    }
}






























