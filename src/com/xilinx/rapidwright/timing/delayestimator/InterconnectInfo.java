package com.xilinx.rapidwright.timing.delayestimator;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Encapsulate interconnect information for a device family.
 * This is for Ultrascale+.
 */
public class InterconnectInfo {

    /**
     * Return a list of timingGroup driven by the fromTimingGroup.
     */
    public List<DelayEstimatorBase.TimingGroup> nextTimingGroups(DelayEstimatorBase.TimingGroup fromTimingGroup) {
        return interconnectHier.get(fromTimingGroup);
    }

    public List<DelayEstimatorBase.TimingGroup> nextTimingGroups(DelayEstimatorBase.TimingGroup fromTimingGroup,
                                                Predicate<? super DelayEstimatorBase.TimingGroup> filter) {

        List<DelayEstimatorBase.TimingGroup> tempList  =  new ArrayList<>(interconnectHier.get(fromTimingGroup));
        tempList.removeIf(filter.negate());
        return Collections.unmodifiableList(tempList);
    }



    /**
     * List possible TG types that can be driven by a TG type.
     * It is immutable.
     */
    private Map<DelayEstimatorBase.TimingGroup, List<DelayEstimatorBase.TimingGroup>> interconnectHier;

    InterconnectInfo() {
        buildInterconnectHier();
    }

    private void buildInterconnectHier() {

        Map<DelayEstimatorBase.TimingGroup, List<DelayEstimatorBase.TimingGroup>> ictHier = new HashMap();

        // UltraScale+
        ictHier.put(DelayEstimatorBase.TimingGroup.CLE_IN, new ArrayList<DelayEstimatorBase.TimingGroup>());
        ictHier.put(DelayEstimatorBase.TimingGroup.CLE_OUT, new ArrayList<DelayEstimatorBase.TimingGroup>() {{
            add(DelayEstimatorBase.TimingGroup.CLE_IN);
            add(DelayEstimatorBase.TimingGroup.HORT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.HORT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.HORT_QUAD);
            add(DelayEstimatorBase.TimingGroup.VERT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.VERT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.VERT_QUAD);
        }});
        ictHier.put(DelayEstimatorBase.TimingGroup.HORT_SINGLE, new ArrayList<>() {{
            add(DelayEstimatorBase.TimingGroup.HORT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.HORT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.HORT_QUAD);
            add(DelayEstimatorBase.TimingGroup.CLE_IN);
            add(DelayEstimatorBase.TimingGroup.VERT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.VERT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.VERT_QUAD);
            add(DelayEstimatorBase.TimingGroup.BOUNCE);
        }});
        ictHier.put(DelayEstimatorBase.TimingGroup.HORT_DOUBLE, new ArrayList<>() {{
            add(DelayEstimatorBase.TimingGroup.HORT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.HORT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.HORT_QUAD);
            add(DelayEstimatorBase.TimingGroup.CLE_IN);
            add(DelayEstimatorBase.TimingGroup.VERT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.VERT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.VERT_QUAD);
            add(DelayEstimatorBase.TimingGroup.BOUNCE);
        }});
        ictHier.put(DelayEstimatorBase.TimingGroup.HORT_QUAD, new ArrayList<>() {{
            add(DelayEstimatorBase.TimingGroup.HORT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.HORT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.HORT_QUAD);
            add(DelayEstimatorBase.TimingGroup.HORT_LONG);
            add(DelayEstimatorBase.TimingGroup.VERT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.VERT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.VERT_QUAD);
            add(DelayEstimatorBase.TimingGroup.VERT_LONG);
        }});
        // TODO: CHeck if Long can drive quad
        ictHier.put(DelayEstimatorBase.TimingGroup.HORT_LONG, new ArrayList<>() {{
            add(DelayEstimatorBase.TimingGroup.HORT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.HORT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.HORT_LONG);
            add(DelayEstimatorBase.TimingGroup.VERT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.VERT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.VERT_LONG);
        }});


        ictHier.put(DelayEstimatorBase.TimingGroup.VERT_SINGLE, new ArrayList<>() {{
            add(DelayEstimatorBase.TimingGroup.HORT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.HORT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.HORT_QUAD);
            add(DelayEstimatorBase.TimingGroup.CLE_IN);
            add(DelayEstimatorBase.TimingGroup.VERT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.VERT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.VERT_QUAD);
            add(DelayEstimatorBase.TimingGroup.BOUNCE);
        }});
        ictHier.put(DelayEstimatorBase.TimingGroup.VERT_DOUBLE, new ArrayList<>() {{
            add(DelayEstimatorBase.TimingGroup.HORT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.HORT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.HORT_QUAD);
            add(DelayEstimatorBase.TimingGroup.CLE_IN);
            add(DelayEstimatorBase.TimingGroup.VERT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.VERT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.VERT_QUAD);
            add(DelayEstimatorBase.TimingGroup.BOUNCE);
        }});
        ictHier.put(DelayEstimatorBase.TimingGroup.VERT_QUAD, new ArrayList<>() {{
            add(DelayEstimatorBase.TimingGroup.HORT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.HORT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.HORT_QUAD);
            add(DelayEstimatorBase.TimingGroup.HORT_LONG);
            add(DelayEstimatorBase.TimingGroup.VERT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.VERT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.VERT_QUAD);
            add(DelayEstimatorBase.TimingGroup.VERT_LONG);
        }});
        // TODO: CHeck if Long can drive quad
        ictHier.put(DelayEstimatorBase.TimingGroup.VERT_LONG, new ArrayList<>() {{
            add(DelayEstimatorBase.TimingGroup.HORT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.HORT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.HORT_LONG);
            add(DelayEstimatorBase.TimingGroup.VERT_SINGLE);
            add(DelayEstimatorBase.TimingGroup.VERT_DOUBLE);
            add(DelayEstimatorBase.TimingGroup.VERT_LONG);
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
            for (DelayEstimatorBase.TimingGroup i : (DelayEstimatorBase.TimingGroup[]) me.getValue()) {
                System.out.printf("    ");
                System.out.println(i.toString());
            }
        }
    }
}
