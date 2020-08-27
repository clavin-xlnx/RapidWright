package com.xilinx.rapidwright.timing.delayestimator;

import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo.TimingGroup;

import org.jgrapht.graph.DefaultWeightedEdge;

/**
 * Use as an edge for DelayGraph. It allows update the edge weight based on the distance at the source of this edge.
 */
public class TimingGroupEdge extends DefaultWeightedEdge {

    private TimingGroup tg;
    private boolean     reverseDirection;

    public TimingGroup getTimingGroup() {
        return tg;
    }
    public boolean isReverseDirection() { return reverseDirection; }

    TimingGroupEdge(TimingGroup tg, boolean reverseDirection) {
        this.tg = tg;
        this.reverseDirection = reverseDirection;
    }

    @Override
    public String toString() {
        return "TimingGroupEdge{" + "tg=" + tg.name() + '}';
    }

    public String toGraphvizDotString() {
        if (tg==null)
            return "null";
        else
            return tg.name();
    }
}
