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
public class InterconnectInfo {

    // override must be a superset
    public static enum Direction {
        VERTICAL,
        HORIZONTAL,
        INPUT,
        OUTPUT,
        LOCAL
    };

    // Enum ensure there is no duplication of each type stored in the tables.
    // Break these up if they are never used together to avoid filtering.
    // Need to distinguish between ver and hor. Thus can't use GroupDelayType.
    //
    // override must be a superset. length and index can be changed.
    public static enum TimingGroup {
        // direction, length and index (to lookup d)
        VERT_SINGLE (Direction.VERTICAL, GroupDelayType.SINGLE,(short) 1,(short) 0),
        VERT_DOUBLE (Direction.VERTICAL, GroupDelayType.DOUBLE,(short) 2,(short) 0),
        VERT_QUAD   (Direction.VERTICAL, GroupDelayType.QUAD,(short) 4,(short) 1),
        VERT_LONG   (Direction.VERTICAL, GroupDelayType.LONG,(short) 12,(short) 2),

        HORT_SINGLE  (Direction.HORIZONTAL, GroupDelayType.SINGLE,(short) 1,(short) 0),
        HORT_DOUBLE  (Direction.HORIZONTAL, GroupDelayType.DOUBLE,(short) 1,(short) 0),
        HORT_QUAD    (Direction.HORIZONTAL, GroupDelayType.QUAD,(short) 2,(short) 1),
        HORT_LONG    (Direction.HORIZONTAL, GroupDelayType.LONG,(short) 6,(short) 2),

        CLE_OUT      (Direction.OUTPUT, GroupDelayType.OTHER,(short) 0,(short) -1),
        CLE_IN       (Direction.INPUT, GroupDelayType.PINFEED,(short) 0,(short) -1),
        BOUNCE       (Direction.LOCAL, GroupDelayType.PIN_BOUNCE,(short) 0,(short) -1);


        private final Direction direction;
        private final GroupDelayType type;
        private final short length;
        private final short index;


        TimingGroup(Direction direction, GroupDelayType type, short length, short index) {
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
        public GroupDelayType type() {
            return type;
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


    /**
     * Return a list of timingGroup driven by the fromTimingGroup.
     */
    public List<TimingGroup> nextTimingGroups(TimingGroup fromTimingGroup) {
        return interconnectHier.get(fromTimingGroup);
    }

    public List<TimingGroup> nextTimingGroups(TimingGroup fromTimingGroup,
                                                Predicate<? super TimingGroup> filter) {

        List<TimingGroup> tempList  =  new ArrayList<>(interconnectHier.get(fromTimingGroup));
        tempList.removeIf(filter.negate());
        return Collections.unmodifiableList(tempList);
    }

    public short minDetour(TimingGroup tg) {
        return minDetourMap.get(tg);
    }

    /**
     * List possible TG types that can be driven by a TG type.
     * It is immutable.
     */
    private Map<TimingGroup, List<TimingGroup>> interconnectHier;
    private Map<TimingGroup, Short> minDetourMap;

    InterconnectInfo() {
        buildInterconnectHier();
    }

    private void buildInterconnectHier() {


        minDetourMap = new EnumMap<>(TimingGroup.class);
        for (TimingGroup tg : TimingGroup.values()) {
            minDetourMap.put(tg, (short) 0);
        }
        minDetourMap.put(TimingGroup.VERT_LONG, TimingGroup.VERT_QUAD.length());
        // TODO: investigate why using HORT_QUAD crate unreachable
        minDetourMap.put(TimingGroup.HORT_LONG, TimingGroup.VERT_QUAD.length());


        Map<TimingGroup, List<TimingGroup>> ictHier = new HashMap();

        // UltraScale+
        ictHier.put(TimingGroup.CLE_IN, new ArrayList<TimingGroup>());
        ictHier.put(TimingGroup.CLE_OUT, new ArrayList<TimingGroup>() {{
            add(TimingGroup.CLE_IN);
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
        }});
        ictHier.put(TimingGroup.HORT_SINGLE, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.CLE_IN);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.BOUNCE);
        }});
        ictHier.put(TimingGroup.HORT_DOUBLE, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.CLE_IN);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.BOUNCE);
        }});
        ictHier.put(TimingGroup.HORT_QUAD, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.HORT_LONG);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.VERT_LONG);
        }});
        // LONG can drive quad, but that is incompatible with that LONG must go to SINGLE/DOUBLE to get to CLE_IN.
        // To keep simple data structure, don't allow LONG -> QUAD
        ictHier.put(TimingGroup.HORT_LONG, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_LONG);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_LONG);
        }});
        ictHier.put(TimingGroup.VERT_SINGLE, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.CLE_IN);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.BOUNCE);
        }});
        ictHier.put(TimingGroup.VERT_DOUBLE, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_QUAD);
            add(TimingGroup.CLE_IN);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_QUAD);
            add(TimingGroup.BOUNCE);
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
        }});
        ictHier.put(TimingGroup.VERT_LONG, new ArrayList<TimingGroup>() {{
            add(TimingGroup.HORT_SINGLE);
            add(TimingGroup.HORT_DOUBLE);
            add(TimingGroup.HORT_LONG);
            add(TimingGroup.VERT_SINGLE);
            add(TimingGroup.VERT_DOUBLE);
            add(TimingGroup.VERT_LONG);
        }});

        // TODO: What's about bounce?

        // No one has access to the modifiable version, ictHier.
        // Thus, the content of interconnectHier never changes.
        interconnectHier = Collections.unmodifiableMap(ictHier);
    }

    /**
     * Helper function to dump the contents.
     */
    void dumpInterconnectHier() {
        for (Map.Entry me : interconnectHier.entrySet()) {
            System.out.println(me.getKey().toString());
            for (TimingGroup i : (TimingGroup[]) me.getValue()) {
                System.out.printf("    ");
                System.out.println(i.toString());
            }
        }
    }
}
