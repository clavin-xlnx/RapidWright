package com.xilinx.rapidwright.timing.delayestimator;

import com.xilinx.rapidwright.timing.GroupDelayType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Encapsulate interconnect information for a device family.
 * This is for Ultrascale+.
 * The routing fabric just need to be captured to provide accurate for estimating the min delay.
 * In US+, LONG can drive SINGLE, DOUBLE or QUAD. But, to get to a site pin it must go through SINGLE or DOUBLE.
 * Connection from LONG to QUAD does not need to be captured because if QUAD is really needed it can be before LONG.
 * This omission allows simpler data structure because we don't need to capture both incompatible requirements of LONG.
 */
public class InterconnectInfo implements java.io.Serializable {

    public static enum TileSide {
        E,
        W,
        M;

        public TileSide getInverted() {
            if (this == TileSide.E)
                return TileSide.W;
            else if (this == TileSide.W)
                return TileSide.E;
            else
                return TileSide.M;
        }
    };

    // up is in increasing INT tile coordinate direction
    public static enum Orientation {
        U, // up
        D, // down
        S; // same place

        public Orientation getInverted() {
            if (this == Orientation.U)
                return Orientation.D;
            else if (this == Orientation.D)
                return Orientation.U;
            else
                return Orientation.S;
        }
    }

    // override must be a superset
    public static enum Direction {
        VERTICAL,
        HORIZONTAL,
        INPUT,
        OUTPUT,
        LOCAL
    };

    public static enum Behavior {
        STATIONARY, // like CLE_IN/OUT
        SWITCH_SIDE_INTERNAL, // like BOUNCE/internal HORT_SINGLE E going W and W going E
        SWITCH_SIDE_EXTERNAL, // like HORT_SINGLE E going E and W going W
        GO_BOTH_SIDES,  // like HORT_LONG/VERT_LONG
        SAME_SIDE // like HORT/VERT DOUBLE/QUAD, VERT_SINGLE
    }

    // Enum ensure there is no duplication of each type stored in the tables.
    // Break these up if they are never used together to avoid filtering.
    // Need to distinguish between ver and hor. Thus can't use GroupDelayType.
    //
    // override must be a superset. length and index can be changed.
    public static enum TimingGroup {
        // direction, length and index (to lookup d)
        VERT_SINGLE (Direction.VERTICAL, GroupDelayType.SINGLE,(short) 1, Behavior.SAME_SIDE, new TileSide[]{TileSide.E,TileSide.W},'S'),
        VERT_DOUBLE (Direction.VERTICAL, GroupDelayType.DOUBLE,(short) 2, Behavior.SAME_SIDE, new TileSide[]{TileSide.E,TileSide.W},'D'),
        VERT_QUAD   (Direction.VERTICAL, GroupDelayType.QUAD,(short) 4, Behavior.SAME_SIDE, new TileSide[]{TileSide.E,TileSide.W},'Q'),
        VERT_LONG   (Direction.VERTICAL, GroupDelayType.LONG,(short) 12, Behavior.GO_BOTH_SIDES, new TileSide[]{TileSide.M},'L'),

        HORT_SINGLE  (Direction.HORIZONTAL, GroupDelayType.SINGLE,(short) 1, Behavior.SWITCH_SIDE_EXTERNAL, new TileSide[]{TileSide.E,TileSide.W},'s'),
        HORT_DOUBLE  (Direction.HORIZONTAL, GroupDelayType.DOUBLE,(short) 1, Behavior.SAME_SIDE, new TileSide[]{TileSide.E,TileSide.W},'d'),
        HORT_QUAD    (Direction.HORIZONTAL, GroupDelayType.QUAD,(short) 2, Behavior.SAME_SIDE, new TileSide[]{TileSide.E,TileSide.W},'q'),
        HORT_LONG    (Direction.HORIZONTAL, GroupDelayType.LONG,(short) 6, Behavior.GO_BOTH_SIDES, new TileSide[]{TileSide.M},'l'),

        CLE_OUT      (Direction.OUTPUT, GroupDelayType.OTHER,(short) 0, Behavior.STATIONARY, new TileSide[]{TileSide.E,TileSide.W}, '-'),
        CLE_IN       (Direction.INPUT, GroupDelayType.PINFEED,(short) 0, Behavior.STATIONARY, new TileSide[]{TileSide.E,TileSide.W}, '-'),
        // actual BOUNCE jump within the same side. But, there is no used of that in delay estimator.
        INTERNAL_SINGLE (Direction.LOCAL, GroupDelayType.PIN_BOUNCE,(short) 0, Behavior.SWITCH_SIDE_INTERNAL, new TileSide[]{TileSide.E,TileSide.W}, 'i'),
        // global has a very high delay and thus is not on a min delay path. Thus, its use case is when it is a source for a lookup.
        // global go to PIN_BOUNCE and PINFEED, ie., INT_X0Y0/BYPASS_E9  - NODE_PINBOUNCE and INT_X0Y0/IMUX_E9  - NODE_PINFEED
        // global node drive only one side of INT_TILE, but its driver drive 2 globals, one for one side.
        // because we only consider when global is a source of a lookup, logically a global node has side. However, it side must be derived from its grandchild.
        // TODO: if global is used often, consider adding cache for it instead of look at its grandchild every time.
        GLOBAL  (Direction.HORIZONTAL, GroupDelayType.GLOBAL,(short) 0, Behavior.SAME_SIDE, new TileSide[]{TileSide.E,TileSide.W},'g');

        private final Direction direction;
        private final GroupDelayType type;
        private final short length;
        private final Behavior behavior;
        private final TileSide[] exsistence;
        private Map<TileSide,List<Orientation>> orientation;
        private Map<TileSide,List<TileSide>> toSide;
        private final char  abbr;
//        private Map<TileSide,Map<Orientation,Orientation>> orientationMap;



        TimingGroup(Direction direction, GroupDelayType type, short length, Behavior behavior, TileSide[] existence, char abbr) {
            this.direction = direction;
            this.type = type;
            this.length = length;
            this.behavior = behavior;
            this.exsistence = existence;
            this.abbr = abbr;

            populateOrientation();
            populateToSide();
//            populateOrientationMap();
        }

        private void populateToSide() {
            this.toSide = new EnumMap<>(TileSide.class);
            if (behavior == Behavior.SWITCH_SIDE_INTERNAL || behavior == Behavior.SWITCH_SIDE_EXTERNAL) {
                this.toSide.put(TileSide.W, new ArrayList<TileSide>(){{add(TileSide.E);}});
                this.toSide.put(TileSide.E, new ArrayList<TileSide>(){{add(TileSide.W);}});
            } else if (behavior == Behavior.GO_BOTH_SIDES) {
                this.toSide.put(TileSide.M, new ArrayList<TileSide>(){{add(TileSide.W);add(TileSide.E);add(TileSide.M);}});
            } else {
                // M is for Quad to connect to Long. Others won't have long as their child and won't matter.
                this.toSide.put(TileSide.W, new ArrayList<TileSide>(){{add(TileSide.W);add(TileSide.M);}});
                this.toSide.put(TileSide.E, new ArrayList<TileSide>(){{add(TileSide.E);add(TileSide.M);}});
            }
        }

//        private void populateOrientationMap() {
//            this.orientationMap = new EnumMap<>(TileSide.class);
//            if (behavior == Behavior.STATIONARY) {
//                Map<Orientation, Orientation> temp = new EnumMap<>(Orientation.class);
//                temp.put(Orientation.U, Orientation.S);
//                temp.put(Orientation.D, Orientation.S);
//                temp.put(Orientation.S, Orientation.S);
//                this.orientationMap.put(TileSide.W, temp);
//                this.orientationMap.put(TileSide.E, temp);
//            } else if (behavior == Behavior.SWITCH_SIDE_INTERNAL) {
//                Map<Orientation, Orientation> tempW = new EnumMap<>(Orientation.class);
//                tempW.put(Orientation.U, Orientation.U);
//                tempW.put(Orientation.D, Orientation.U);
//                tempW.put(Orientation.S, Orientation.U);
//                this.orientationMap.put(TileSide.W, tempW);
//                Map<Orientation, Orientation> tempE = new EnumMap<>(Orientation.class);
//                tempE.put(Orientation.U, Orientation.D);
//                tempE.put(Orientation.D, Orientation.D);
//                tempE.put(Orientation.S, Orientation.D);
//                this.orientationMap.put(TileSide.E, tempE);
//            } else if (behavior == Behavior.SWITCH_SIDE_EXTERNAL) {
//                Map<Orientation, Orientation> tempW = new EnumMap<>(Orientation.class);
//                tempW.put(Orientation.U, Orientation.D);
//                tempW.put(Orientation.D, Orientation.D);
//                tempW.put(Orientation.S, Orientation.D);
//                this.orientationMap.put(TileSide.W, tempW);
//                Map<Orientation, Orientation> tempE = new EnumMap<>(Orientation.class);
//                tempE.put(Orientation.U, Orientation.U);
//                tempE.put(Orientation.D, Orientation.U);
//                tempE.put(Orientation.S, Orientation.U);
//                this.orientationMap.put(TileSide.E, tempE);
//            } else if (behavior == Behavior.GO_BOTH_SIDES) {
//                // TODO GO_BOTH_SIDES should be controlled from here not from Table
//            } else {
//                Map<Orientation, Orientation> temp = new EnumMap<>(Orientation.class);
//                temp.put(Orientation.U, Orientation.U);
//                temp.put(Orientation.D, Orientation.D);
//                temp.put(Orientation.S, Orientation.S);
//                this.orientationMap.put(TileSide.W, temp);
//                this.orientationMap.put(TileSide.E, temp);
//            }
//        }

        private void populateOrientation() {
            List<Orientation> emptyOrientation = new ArrayList<Orientation>();

            this.orientation = new EnumMap<>(TileSide.class);
            if (behavior == Behavior.STATIONARY) {
                List<Orientation> tempOrientation = new ArrayList<Orientation>(){{add(Orientation.S);}};
                this.orientation.put(TileSide.W, tempOrientation);
                this.orientation.put(TileSide.E, tempOrientation);
                this.orientation.put(TileSide.M, emptyOrientation);
            } else if (behavior == Behavior.SWITCH_SIDE_INTERNAL) {
                this.orientation.put(TileSide.W, new ArrayList<Orientation>(){{add(Orientation.U);}});
                this.orientation.put(TileSide.E, new ArrayList<Orientation>(){{add(Orientation.D);}});
                this.orientation.put(TileSide.M, emptyOrientation);
            } else if (behavior == Behavior.SWITCH_SIDE_EXTERNAL) {
                this.orientation.put(TileSide.W, new ArrayList<Orientation>(){{add(Orientation.D);}});
                this.orientation.put(TileSide.E, new ArrayList<Orientation>(){{add(Orientation.U);}});
                this.orientation.put(TileSide.M, emptyOrientation);
            } else if (behavior == Behavior.GO_BOTH_SIDES) {
                List<Orientation> tempOrientation = new ArrayList<Orientation>(){{add(Orientation.U);add(Orientation.D);}};
                this.orientation.put(TileSide.W, emptyOrientation);
                this.orientation.put(TileSide.E, emptyOrientation);
                this.orientation.put(TileSide.M, tempOrientation);
            } else {
                List<Orientation> tempOrientation = new ArrayList<Orientation>(){{add(Orientation.U);add(Orientation.D);}};
                this.orientation.put(TileSide.W, tempOrientation);
                this.orientation.put(TileSide.E, tempOrientation);
                this.orientation.put(TileSide.M, emptyOrientation);
            }
        }

        public Direction direction() {
            return direction;
        }
        public short length() {
            return length;
        }
        public GroupDelayType type() {
            return type;
        }
        public char abbr() {
            return abbr;
        }
//        public Orientation getToOrientation(TileSide side, Orientation orient) {
//            return orientationMap.get(side).get(orient);
//        }

        public List<Orientation> getOrientation(TileSide side) {
            return orientation.get(side);
        }

        public TileSide[] getExsistence() {
            return exsistence;
        }

        public List<TileSide> toSide(TileSide side) {
            return toSide.get(side);
        }
    }

    public short minTableWidth() {
        return  (short) (TimingGroup.HORT_LONG.length() + TimingGroup.HORT_QUAD.length() +
                TimingGroup.HORT_DOUBLE.length() + 1);

    }

    public short minTableHeight() {
        return (short) (TimingGroup.VERT_LONG.length() + TimingGroup.VERT_QUAD.length() +
                TimingGroup.VERT_DOUBLE.length() + 1);
    }

    protected static List<TimingGroup> getTimingGroup(Predicate<? super TimingGroup> predicate) {
        List <TimingGroup> res = new ArrayList<>();
        for (TimingGroup tg : TimingGroup.values()) {
            if (predicate.test(tg)) {
                res.add(tg);
            }
        }
        return res;
    }

    protected static short maxTgLength(Direction dir) {
        short length = 0;
        for (TimingGroup tg : getTimingGroup((TimingGroup e) -> (e.direction() == dir))) {
            if (length < tg.length())
                length = tg.length();
        }
        return length;
    }


    /**
     * Return a list of timingGroup driven by the fromTimingGroup.
     */
    public List<TimingGroup> nextTimingGroups(TimingGroup fromTimingGroup) {
        return fanoutInterconnectHier.get(fromTimingGroup);
    }

    public List<TimingGroup> nextTimingGroups(TimingGroup fromTimingGroup,
                                                Predicate<? super TimingGroup> filter) {

        List<TimingGroup> tempList  =  new ArrayList<>(fanoutInterconnectHier.get(fromTimingGroup));
        tempList.removeIf(filter.negate());
        return tempList;
    }

    public List<TimingGroup> prvTimingGroups(TimingGroup toTimingGroup) {
        return faninInterconnectHier.get(toTimingGroup);
    }

    public Map<String,String> getNodeToSitePin() {
        return nodeToSitePin;
    }

    public List<TimingGroup> prvTimingGroups(TimingGroup toTimingGroup,
                                              Predicate<? super TimingGroup> filter) {

        List<TimingGroup> tempList  =  new ArrayList<>(faninInterconnectHier.get(toTimingGroup));
        tempList.removeIf(filter.negate());
        return tempList;
    }
    public short minDetourFrTg(TimingGroup tg) {
        return minDetourMapFrTg.get(tg);
    }
    public short minDetourToTg(TimingGroup tg) {
        return minDetourMapToTg.get(tg);
    }
    /**
     * List possible TG types that can be driven by a TG type.
     * It is immutable.
     */
    private Map<TimingGroup, List<TimingGroup>> fanoutInterconnectHier;
    private Map<TimingGroup, List<TimingGroup>> faninInterconnectHier;
    private Map<TimingGroup, Short> minDetourMapFrTg;
    private Map<TimingGroup, Short> minDetourMapToTg;
    
    private Map<String,String> nodeToSitePin;
    
  //TODO changed to public by Yun
    public InterconnectInfo() {
        buildInterconnectHier();
        buildNodeToSitePin();
    }

    private void buildNodeToSitePin() {
        // TODO: consider 1) load from file and 2) build it from RW device model.
        nodeToSitePin = new HashMap<>();
        nodeToSitePin.put("IMUX_E10"           ,"A1"                );
        nodeToSitePin.put("IMUX_E8"            ,"A2"                );
        nodeToSitePin.put("IMUX_E24"           ,"A3"                );
        nodeToSitePin.put("IMUX_E22"           ,"A4"                );
        nodeToSitePin.put("IMUX_E26"           ,"A5"                );
        nodeToSitePin.put("IMUX_E18"           ,"A6"                );
        nodeToSitePin.put("BOUNCE_E_0_FT1"     ,"AX"                );
        nodeToSitePin.put("BYPASS_E4"          ,"A_I"               );
        nodeToSitePin.put("IMUX_E11"           ,"B1"                );
        nodeToSitePin.put("IMUX_E9"            ,"B2"                );
        nodeToSitePin.put("IMUX_E25"           ,"B3"                );
        nodeToSitePin.put("IMUX_E23"           ,"B4"                );
        nodeToSitePin.put("IMUX_E27"           ,"B5"                );
        nodeToSitePin.put("IMUX_E19"           ,"B6"                );
        nodeToSitePin.put("BYPASS_E1"          ,"BX"                );
        nodeToSitePin.put("BYPASS_E5"          ,"B_I"               );
        nodeToSitePin.put("IMUX_E6"            ,"C1"                );
        nodeToSitePin.put("IMUX_E12"           ,"C2"                );
        nodeToSitePin.put("IMUX_E16"           ,"C3"                );
        nodeToSitePin.put("IMUX_E28"           ,"C4"                );
        nodeToSitePin.put("IMUX_E30"           ,"C5"                );
        nodeToSitePin.put("IMUX_E20"           ,"C6"                );
        nodeToSitePin.put("BOUNCE_E_2_FT1"     ,"CX"                );
        nodeToSitePin.put("BYPASS_E6"          ,"C_I"               );
        nodeToSitePin.put("IMUX_E7"            ,"D1"                );
        nodeToSitePin.put("IMUX_E13"           ,"D2"                );
        nodeToSitePin.put("IMUX_E17"           ,"D3"                );
        nodeToSitePin.put("IMUX_E29"           ,"D4"                );
        nodeToSitePin.put("IMUX_E31"           ,"D5"                );
        nodeToSitePin.put("IMUX_E21"           ,"D6"                );
        nodeToSitePin.put("BYPASS_E3"          ,"DX"                );
        nodeToSitePin.put("BYPASS_E7"          ,"D_I"               );
        nodeToSitePin.put("IMUX_E2"            ,"E1"                );
        nodeToSitePin.put("IMUX_E4"            ,"E2"                );
        nodeToSitePin.put("IMUX_E40"           ,"E3"                );
        nodeToSitePin.put("IMUX_E44"           ,"E4"                );
        nodeToSitePin.put("IMUX_E42"           ,"E5"                );
        nodeToSitePin.put("IMUX_E34"           ,"E6"                );
        nodeToSitePin.put("BYPASS_E8"          ,"EX"                );
        nodeToSitePin.put("BYPASS_E12"         ,"E_I"               );
        nodeToSitePin.put("IMUX_E3"            ,"F1"                );
        nodeToSitePin.put("IMUX_E5"            ,"F2"                );
        nodeToSitePin.put("IMUX_E41"           ,"F3"                );
        nodeToSitePin.put("IMUX_E45"           ,"F4"                );
        nodeToSitePin.put("IMUX_E43"           ,"F5"                );
        nodeToSitePin.put("IMUX_E35"           ,"F6"                );
        nodeToSitePin.put("BYPASS_E9"          ,"FX"                );
        nodeToSitePin.put("BOUNCE_E_13_FT0"    ,"F_I"               );
        nodeToSitePin.put("IMUX_E0"            ,"G1"                );
        nodeToSitePin.put("IMUX_E14"           ,"G2"                );
        nodeToSitePin.put("IMUX_E32"           ,"G3"                );
        nodeToSitePin.put("IMUX_E36"           ,"G4"                );
        nodeToSitePin.put("IMUX_E38"           ,"G5"                );
        nodeToSitePin.put("IMUX_E46"           ,"G6"                );
        nodeToSitePin.put("BYPASS_E10"         ,"GX"                );
        nodeToSitePin.put("BYPASS_E14"         ,"G_I"               );
        nodeToSitePin.put("IMUX_E1"            ,"H1"                );
        nodeToSitePin.put("IMUX_E15"           ,"H2"                );
        nodeToSitePin.put("IMUX_E33"           ,"H3"                );
        nodeToSitePin.put("IMUX_E37"           ,"H4"                );
        nodeToSitePin.put("IMUX_E39"           ,"H5"                );
        nodeToSitePin.put("IMUX_E47"           ,"H6"                );
        nodeToSitePin.put("BYPASS_E11"         ,"HX"                );
        nodeToSitePin.put("BOUNCE_E_15_FT0"    ,"H_I"               );

        nodeToSitePin.put("IMUX_W10"           ,"A1"                );
        nodeToSitePin.put("IMUX_W8"            ,"A2"                );
        nodeToSitePin.put("IMUX_W24"           ,"A3"                );
        nodeToSitePin.put("IMUX_W22"           ,"A4"                );
        nodeToSitePin.put("IMUX_W26"           ,"A5"                );
        nodeToSitePin.put("IMUX_W18"           ,"A6"                );
        nodeToSitePin.put("BOUNCE_W_0_FT1"     ,"AX"                );
        nodeToSitePin.put("BYPASS_W4"          ,"A_I"               );
        nodeToSitePin.put("IMUX_W11"           ,"B1"                );
        nodeToSitePin.put("IMUX_W9"            ,"B2"                );
        nodeToSitePin.put("IMUX_W25"           ,"B3"                );
        nodeToSitePin.put("IMUX_W23"           ,"B4"                );
        nodeToSitePin.put("IMUX_W27"           ,"B5"                );
        nodeToSitePin.put("IMUX_W19"           ,"B6"                );
        nodeToSitePin.put("BYPASS_W1"          ,"BX"                );
        nodeToSitePin.put("BYPASS_W5"          ,"B_I"               );
        nodeToSitePin.put("IMUX_W6"            ,"C1"                );
        nodeToSitePin.put("IMUX_W12"           ,"C2"                );
        nodeToSitePin.put("IMUX_W16"           ,"C3"                );
        nodeToSitePin.put("IMUX_W28"           ,"C4"                );
        nodeToSitePin.put("IMUX_W30"           ,"C5"                );
        nodeToSitePin.put("IMUX_W20"           ,"C6"                );
        nodeToSitePin.put("BOUNCE_W_2_FT1"     ,"CX"                );
        nodeToSitePin.put("BYPASS_W6"          ,"C_I"               );
        nodeToSitePin.put("IMUX_W7"            ,"D1"                );
        nodeToSitePin.put("IMUX_W13"           ,"D2"                );
        nodeToSitePin.put("IMUX_W17"           ,"D3"                );
        nodeToSitePin.put("IMUX_W29"           ,"D4"                );
        nodeToSitePin.put("IMUX_W31"           ,"D5"                );
        nodeToSitePin.put("IMUX_W21"           ,"D6"                );
        nodeToSitePin.put("BYPASS_W3"          ,"DX"                );
        nodeToSitePin.put("BYPASS_W7"          ,"D_I"               );
        nodeToSitePin.put("IMUX_W2"            ,"E1"                );
        nodeToSitePin.put("IMUX_W4"            ,"E2"                );
        nodeToSitePin.put("IMUX_W40"           ,"E3"                );
        nodeToSitePin.put("IMUX_W44"           ,"E4"                );
        nodeToSitePin.put("IMUX_W42"           ,"E5"                );
        nodeToSitePin.put("IMUX_W34"           ,"E6"                );
        nodeToSitePin.put("BYPASS_W8"          ,"EX"                );
        nodeToSitePin.put("BYPASS_W12"         ,"E_I"               );
        nodeToSitePin.put("IMUX_W3"            ,"F1"                );
        nodeToSitePin.put("IMUX_W5"            ,"F2"                );
        nodeToSitePin.put("IMUX_W41"           ,"F3"                );
        nodeToSitePin.put("IMUX_W45"           ,"F4"                );
        nodeToSitePin.put("IMUX_W43"           ,"F5"                );
        nodeToSitePin.put("IMUX_W35"           ,"F6"                );
        nodeToSitePin.put("BYPASS_W9"          ,"FX"                );
        nodeToSitePin.put("BOUNCE_W_13_FT0"    ,"F_I"               );
        nodeToSitePin.put("IMUX_W0"            ,"G1"                );
        nodeToSitePin.put("IMUX_W14"           ,"G2"                );
        nodeToSitePin.put("IMUX_W32"           ,"G3"                );
        nodeToSitePin.put("IMUX_W36"           ,"G4"                );
        nodeToSitePin.put("IMUX_W38"           ,"G5"                );
        nodeToSitePin.put("IMUX_W46"           ,"G6"                );
        nodeToSitePin.put("BYPASS_W10"         ,"GX"                );
        nodeToSitePin.put("BYPASS_W14"         ,"G_I"               );
        nodeToSitePin.put("IMUX_W1"            ,"H1"                );
        nodeToSitePin.put("IMUX_W15"           ,"H2"                );
        nodeToSitePin.put("IMUX_W33"           ,"H3"                );
        nodeToSitePin.put("IMUX_W37"           ,"H4"                );
        nodeToSitePin.put("IMUX_W39"           ,"H5"                );
        nodeToSitePin.put("IMUX_W47"           ,"H6"                );
        nodeToSitePin.put("BYPASS_W11"         ,"HX"                );
        nodeToSitePin.put("BOUNCE_W_15_FT0"    ,"H_I"               );
    }
    private void buildInterconnectHier() {


        minDetourMapFrTg = new EnumMap<>(TimingGroup.class);
        minDetourMapToTg = new EnumMap<>(TimingGroup.class);
        for (TimingGroup tg : TimingGroup.values()) {
            minDetourMapFrTg.put(tg, (short) 0);
            minDetourMapToTg.put(tg, (short) 0);
        }
        // the length of itself
        minDetourMapFrTg.put(TimingGroup.VERT_LONG, TimingGroup.VERT_LONG.length());
        minDetourMapFrTg.put(TimingGroup.HORT_LONG, TimingGroup.HORT_LONG.length());
        minDetourMapFrTg.put(TimingGroup.VERT_QUAD, TimingGroup.VERT_QUAD.length());
        minDetourMapFrTg.put(TimingGroup.HORT_QUAD, TimingGroup.HORT_QUAD.length());
        minDetourMapFrTg.put(TimingGroup.VERT_DOUBLE, TimingGroup.VERT_DOUBLE.length());
        minDetourMapFrTg.put(TimingGroup.HORT_DOUBLE, TimingGroup.HORT_DOUBLE.length());
        // the length of required driver on the other direction
        minDetourMapToTg.put(TimingGroup.VERT_LONG, TimingGroup.HORT_QUAD.length());
        minDetourMapToTg.put(TimingGroup.HORT_LONG, TimingGroup.VERT_QUAD.length());

        Map<TimingGroup, List<TimingGroup>> ictHier = new HashMap();

        // UltraScale+
        ictHier.put(TimingGroup.CLE_IN, new ArrayList<TimingGroup>());
        ictHier.put(TimingGroup.CLE_OUT, new ArrayList<TimingGroup>() {{
            add(TimingGroup.CLE_IN);
//          Don't use single, in general, it will be added in listPaths when dist is 1
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.INTERNAL_SINGLE);
        }});
        // HORT_SINGLE can't to another HORT_SINGLE , why ?
        ictHier.put(TimingGroup.HORT_SINGLE, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.CLE_IN);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.INTERNAL_SINGLE);
        }});
        ictHier.put(TimingGroup.HORT_DOUBLE, new ArrayList<TimingGroup>() {{
//          Don't use single, in general, it will be added in listPaths when dist is 1
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.CLE_IN);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.INTERNAL_SINGLE);
        }});
        ictHier.put(TimingGroup.HORT_QUAD, new ArrayList<TimingGroup>() {{
//          Don't use single, in general, it will be added in listPaths when dist is 1
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.HORT_LONG);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.VERT_LONG);
            add(TimingGroup.INTERNAL_SINGLE);
        }});
        // LONG can drive quad, but that is incompatible with that LONG must go to SINGLE/DOUBLE to get to CLE_IN.
        // it is not incompatible, if long go to quad it will eventually go to single/double because quad can't drive CLE_IN either.
        // To keep simple data structure, don't allow LONG -> QUAD
        ictHier.put(TimingGroup.HORT_LONG, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.HORT_LONG);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.VERT_LONG);
            add(TimingGroup.INTERNAL_SINGLE);
        }});
        ictHier.put(TimingGroup.VERT_SINGLE, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.CLE_IN);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.INTERNAL_SINGLE);
        }});
        ictHier.put(TimingGroup.VERT_DOUBLE, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.CLE_IN);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.INTERNAL_SINGLE);
        }});
        ictHier.put(TimingGroup.VERT_QUAD, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.HORT_LONG);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.VERT_LONG);
            add(TimingGroup.INTERNAL_SINGLE);
        }});
        ictHier.put(TimingGroup.VERT_LONG, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.HORT_LONG);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.VERT_LONG);
            add(TimingGroup.INTERNAL_SINGLE);
        }});

        ictHier.put(TimingGroup.INTERNAL_SINGLE, new ArrayList<TimingGroup>() {{
            add(TimingGroup.CLE_IN);
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
        }});

        ictHier.put(TimingGroup.GLOBAL, new ArrayList<TimingGroup>() {{
            add(TimingGroup.CLE_IN);
        }});

        // No one has access to the modifiable version, ictHier.
        // Thus, the content of interconnectHier never changes.
        fanoutInterconnectHier = Collections.unmodifiableMap(ictHier);


        // build fanin from the fanout above
        Map<TimingGroup, List<TimingGroup>> faninIctHier = new HashMap();
        for (Map.Entry<TimingGroup, List<TimingGroup>> entry : fanoutInterconnectHier.entrySet()) {
            TimingGroup frTg = entry.getKey();
            for (TimingGroup toTg : entry.getValue()) {
                if (!faninIctHier.containsKey(toTg))
                    faninIctHier.put(toTg, new ArrayList<TimingGroup>());

                faninIctHier.get(toTg).add(frTg);
            }
        }
        faninInterconnectHier = Collections.unmodifiableMap(faninIctHier);
    }


    /**
     * Helper function to dump the contents.
     */
    void dumpInterconnectHier() {
        for (Map.Entry me : fanoutInterconnectHier.entrySet()) {
            System.out.println(me.getKey().toString());
            for (TimingGroup i : (TimingGroup[]) me.getValue()) {
                System.out.printf("    ");
                System.out.println(i.toString());
            }
        }
    }
}
