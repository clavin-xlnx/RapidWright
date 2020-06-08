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
public abstract class DelayEstimatorBase  implements DelayEstimator {

    // Consider moving DelayEstimator as a member of TimingManager.
    // Until then, keep timingModel here for testing.

//        int result = est.getTimingModel().computeHorizontalDistFromArray(74,76, GroupDelayType.QUAD);
    // Concise
    // TODO: single and double have their own arrays. their values are the same.
    // Not all type has dist arrays. The arrays will never be addressed by those types.
    // distArrays are cumulative. It is also inclusive, ie.,
    // for segment spaning x-y,  d[y] is  included d of the segment.
    protected static Map<Direction,Map<GroupDelayType, List<Short>>> distArrays;
    protected static Map<Direction,Map<GroupDelayType, Short>> K0;
    protected static Map<Direction,Map<GroupDelayType, Short>> K1;
    protected static Map<Direction,Map<GroupDelayType, Short>> K2;
    protected static Map<Direction,Map<GroupDelayType, Short>> L;


    enum Direction {
        VERTICAL,
        HORIZONTAL,
        INPUT,
        OUTPUT,
        LOCAL
    };

    // Enum ensure there is no duplication of each type stored in the tables.
    // Break these up if they are never used together to avoid filtering.
    // TODO: should be move to InterconnectInfo
    enum TimingGroup {
        // direction, length and index (to lookup d)
        VERT_SINGLE (Direction.VERTICAL, GroupDelayType.SINGLE,(short) 1,(short) 0),
        VERT_DOUBLE (Direction.VERTICAL, GroupDelayType.DOUBLE,(short) 2,(short) 0),
        VERT_QUAD   (Direction.VERTICAL, GroupDelayType.QUAD,(short) 4,(short) 1),
        VERT_LONG   (Direction.VERTICAL, GroupDelayType.LONG,(short) 12,(short) 2),

        HORT_SINGLE  (Direction.HORIZONTAL, GroupDelayType.SINGLE,(short) 1,(short) 0),
        HORT_DOUBLE  (Direction.HORIZONTAL, GroupDelayType.DOUBLE,(short) 2,(short) 0),
        HORT_QUAD    (Direction.HORIZONTAL, GroupDelayType.QUAD,(short) 4,(short) 1),
        HORT_LONG    (Direction.HORIZONTAL, GroupDelayType.LONG,(short) 6,(short) 2),

        CLE_OUT      (Direction.OUTPUT, GroupDelayType.OTHER,(short) 0,(short) -1),
        CLE_IN       (Direction.HORIZONTAL, GroupDelayType.PINFEED,(short) 0,(short) -1),
        BOUNCE       (Direction.LOCAL, GroupDelayType.PIN_BOUNCE,(short) 0,(short) -1);


        private final Direction direction;
        private final GroupDelayType type;
        private final short length;
        private final short index;


        TimingGroup(DelayEstimatorBase.Direction direction, GroupDelayType type, short length, short index) {
            this.direction = direction;
            this.type      = type;
            this.length    = length;
            this.index     = index;
        }

        public Direction direction() {
            return direction;
        }

        public short length() {
            return length;
        }
    }


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
        distArrays = new EnumMap<Direction,Map<GroupDelayType, List<Short>>>(Direction.class);

        Map<GroupDelayType, List<Short>> thor = new EnumMap<GroupDelayType, List<Short>>(GroupDelayType.class);

        thor.put(GroupDelayType.PINFEED, new ArrayList<Short>());
        thor.get(GroupDelayType.PINFEED).add((short) 0);
        thor.get(GroupDelayType.PINFEED).add((short) 0);
        thor.get(GroupDelayType.PINFEED).add((short) 0);
        for (int i = 0; i < 20; i++)
            thor.get(GroupDelayType.PINFEED).add((short) 0);

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

        distArrays.put(Direction.HORIZONTAL, thor);


        // TODO: move it to interconectInfo

        K0 = new EnumMap<Direction,Map<GroupDelayType, Short>>(Direction.class);
        K1 = new EnumMap<Direction,Map<GroupDelayType, Short>>(Direction.class);
        K2 = new EnumMap<Direction,Map<GroupDelayType, Short>>(Direction.class);
        L  = new EnumMap<Direction,Map<GroupDelayType, Short>>(Direction.class);

        // TG delay = k0 + k1 * L + k2 * d;
        Map<GroupDelayType, Short> tk0 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
        tk0.put(GroupDelayType.PINFEED,(short) 0);
        tk0.put(GroupDelayType.SINGLE, (short) 43);
        tk0.put(GroupDelayType.DOUBLE, (short) 43);
        tk0.put(GroupDelayType.QUAD,   (short) 43);
        tk0.put(GroupDelayType.LONG,   (short) 43);
        K0.put(Direction.HORIZONTAL,tk0);

        Map<GroupDelayType, Short> tk1 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
        tk1.put(GroupDelayType.PINFEED,(short) 0);
        tk1.put(GroupDelayType.SINGLE, (short) 4);
        tk1.put(GroupDelayType.DOUBLE, (short) 4);
        tk1.put(GroupDelayType.QUAD,   (short) 4);
        tk1.put(GroupDelayType.LONG,   (short) 4);
        K1.put(Direction.HORIZONTAL,tk1);

        Map<GroupDelayType, Short> tk2 = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
        tk2.put(GroupDelayType.PINFEED,(short) 0);
        tk2.put(GroupDelayType.SINGLE, (short) 2);
        tk2.put(GroupDelayType.DOUBLE, (short) 2);
        tk2.put(GroupDelayType.QUAD,   (short) 2);
        tk2.put(GroupDelayType.LONG,   (short) 1);
        K2.put(Direction.HORIZONTAL,tk2);

        Map<GroupDelayType, Short> tl = new EnumMap<GroupDelayType, Short>(GroupDelayType.class);
        tl.put(GroupDelayType.PINFEED,(short) 0);
        tl.put(GroupDelayType.SINGLE, (short) 1);
        tl.put(GroupDelayType.DOUBLE, (short) 5);
        tl.put(GroupDelayType.QUAD,   (short) 10);
        tl.put(GroupDelayType.LONG,   (short) 14);
        L.put(Direction.HORIZONTAL,tl);
    }

    /**
     *
     * @param e The current timing group.
     * @param v The INT tile index at the beginning of the timing group.
     * @return  The delay of this timing group.
     */
    protected static double calcTimingGroupDelay(TimingGroupEdge e, Double v) {
        TimingGroup tg = e.getTimingGroup();
        short k0 = K0.get(tg.direction()).get(tg.type);
        short k1 = K1.get(tg.direction()).get(tg.type);
        short k2 = K2.get(tg.direction()).get(tg.type);
        short l  = L .get(tg.direction()).get(tg.type);

        short st  = distArrays.get(tg.direction()).get(tg.type).get(v.shortValue());
        short sp  = distArrays.get(tg.direction()).get(tg.type).get(v.shortValue() + tg.length());

        // need abs in case the tg is going to the left.
        short del  = (short) (k0 + k1 * l + k2 * Math.abs(sp-st));
//        System.out.println(tg.name());
//        System.out.println("   v " + v + " len " + tg.length() + "  calc " + tg.name() +
//                " k0 " + k0 + " k1 " + k1 + " l " + l + " k2 " + k2 + " d " + (sp-st) + " del " + del +
//                "         fr " + v.shortValue() +  " to " + (v.shortValue() + tg.length()) + " st " + st + " sp " + sp);

        return del;
    }
}






























