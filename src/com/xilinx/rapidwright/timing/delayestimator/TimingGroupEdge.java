package com.xilinx.rapidwright.timing.delayestimator;

import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo.TimingGroup;

import org.jgrapht.graph.DefaultWeightedEdge;

/**
 * Use as an edge for DelayGraph. It allows update the edge weight based on the distance at the source of this edge.
 */
public class TimingGroupEdge extends DefaultWeightedEdge {

    private TimingGroup tg;

    public TimingGroup getTimingGroup() {
        return tg;
    }

    TimingGroupEdge(TimingGroup tg) {
        this.tg = tg;
    }

    @Override
    public String toString() {
        return "TimingGroupEdge{" + "tg=" + tg.name() + '}';
    }

    public String toGraphvizDotString() {
       return tg.name();
    }
}
