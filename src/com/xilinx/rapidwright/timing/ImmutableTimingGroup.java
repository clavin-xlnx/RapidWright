/*
 * Original work: Copyright (c) 2020 Xilinx, Inc.
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
package com.xilinx.rapidwright.timing;
// Consider moving this to device

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.util.Pair;

/**
 * There are two types of TGs.
 * 1) TG with two nodes. The TG drives other TG from exitNode and it is driven to entryNode.
 * 2) TG with one node, ie.,  CLE_OUT, GLOBAL, LONG. TG of this type has only exitNode.
 *    The node drives other TGs and is driven by another TG.
 *
 * Globally, a node that is an entry node of any TG can drive another node.
 * However, an entry node can drive only an exit node, never an entry node.
 */
public class ImmutableTimingGroup {

    // NODE_PINBOUNCE can be driven by NODE_LOCAL or INTENT_DEFAULT (which is vcc in this case).
    // We don't need a TG with vcc, but there is no way to back off once this ctor is called.
    public ImmutableTimingGroup(NodeWithFaninInfo exitNode, NodeWithFaninInfo entryNode, IntentCode exitNodeIntentCode, IntentCode entryNodeIntentCode) {
        this.exitNode  = exitNode;
        this.entryNode = entryNode;
        Pair<GroupDelayType,GroupWireDirection> data = computeTypes(exitNode, exitNodeIntentCode);
        this.groupDelayType = data.getFirst();
        this.groupWireDir   = data.getSecond();
    }

    public ImmutableTimingGroup(NodeWithFaninInfo exitNode, IntentCode exitNodeIntentCode) {
        this.exitNode  = exitNode;
        this.entryNode = null;
        Pair<GroupDelayType,GroupWireDirection> data = computeTypes(exitNode, exitNodeIntentCode);
        this.groupDelayType = data.getFirst();
        this.groupWireDir   = data.getSecond();
    }

    @Override
    public int hashCode() {
        return exitNode.hashCode();
    }

    public GroupDelayType delayType() {
        return groupDelayType;
    }

    public GroupWireDirection wireDirection() {
        return groupWireDir;
    }

    public NodeWithFaninInfo exitNode() {
        return exitNode;
    }

    public NodeWithFaninInfo entryNode() {
        return entryNode;
    }
    
    public void setDelay(short delay){
    	this.groupDelay = delay;
    	this.delaySet = true;
    }
    
    public short getDelay(){
    	return this.groupDelay;
    }
    
    public short get_d() {
    	return this.d;
    }
    
    @Override
    public String toString(){
    	String s = "Nodes = ( ";
    	if(entryNode != null)
    		s += entryNode.toString();
    	else 
    		s += "exit only";
    	s += ", " + exitNode.toString() + " )";
    	return String.format("%-90s delay = %5d ps", s, this.groupDelay) + ", " + this.groupDelayType;
    }
    // ------------------------------------   private ----------------------------------------


    // TODO: Do I need to keep nodes around? If not, store hashCode
    final private NodeWithFaninInfo exitNode;  // drive entryNode of other TGs.
    // TODO: Do we need to keep track of entryNode?  We can save memory by not keeping it, but will spend more time for backward traveral.
    final private NodeWithFaninInfo entryNode; // can drive exitNode of other TGs, but never drive entry nodes.

    // if groupDelayType = INTERNAL, PINFEED, PIN_BOUNCE, GLOBAL, or OTHER ignore GroupWireDir
    final private GroupDelayType groupDelayType;
    // used to lookup coefficients based on directions
    final private GroupWireDirection groupWireDir; // ver, hor
    private short groupDelay; //TODO Yun
    public boolean delaySet; //TODO remove

    private GroupWireDirection getDirection (NodeWithFaninInfo node) {
        // TODO: is there a better than name checking ?
        String wName = node.getAllWiresInNode()[0].getWireName();
        if (wName.startsWith("SS") || wName.startsWith("NN"))
            return GroupWireDirection.VERTICAL;
        else
            return GroupWireDirection.HORIZONTAL;
    }

    /**
     * Computes the TimingGroup GroupDelayType for this group.
     */
    // TODO: if TG is from device model, this method is not needed because the type is already known.
    private Pair<GroupDelayType, GroupWireDirection> computeTypes(NodeWithFaninInfo node, IntentCode intentCode) {
        GroupDelayType     groupDelayType = null;
        GroupWireDirection groupWireDir   = null;

        switch(intentCode) {
            case NODE_SINGLE:
                // TODO: if the delay of INTERNAL is the same as that of SINGLE with d 0, the if will be eliminated.
                Wire[] wires = node.getAllWiresInNode();
                if (wires[0].getTile() == wires[wires.length-1].getTile()) {
                    groupDelayType = GroupDelayType.INTERNAL;
                    groupWireDir   = null;
                } else {
                    groupDelayType = GroupDelayType.SINGLE;
                    groupWireDir   = getDirection(node);
                }
                break;

            case NODE_DOUBLE:
                groupDelayType = GroupDelayType.DOUBLE;
                groupWireDir   = getDirection(node);
                break;

            case NODE_HQUAD:
            case NODE_VQUAD:
                groupDelayType = GroupDelayType.QUAD;
                groupWireDir   = getDirection(node);
                break;

            case NODE_HLONG:
            case NODE_VLONG:
                groupDelayType = GroupDelayType.LONG;
                groupWireDir   = getDirection(node);
                break;

            case NODE_PINFEED:
                groupDelayType = GroupDelayType.PINFEED;
                groupWireDir   = null;
                break;

            case NODE_PINBOUNCE:
                groupDelayType = GroupDelayType.PIN_BOUNCE;
                groupWireDir   = null;
                break;

            // when NODE_LOCAL is the exitNode, it is global node.
            case NODE_LOCAL:
                groupDelayType = GroupDelayType.GLOBAL;
                groupWireDir   = null;
                break;

            default:
                groupDelayType = GroupDelayType.OTHER;
                groupWireDir   = null;
        }

        return new Pair(groupDelayType,groupWireDir);
    }
    
    // added by Yun
    private short d;
    /**
     * Computes the D (distance) term used by the TimingModel calculation.
     * @param n Given Node to use when checking the wire names.
     * @return The D term used by the TimingModel delay calculation.
     */
    short computeD(Node n, TimingModel timingModel) {
        int result = 0;
        int minRow = 1<<20;
        int maxRow = 0;
        int minCol = 1<<20;
        int maxCol = 0;
        List<Wire> wList = new ArrayList<>();
        for (Wire w : n.getAllWiresInNode()) {
            if (w.getWireName().contains("BEG")) {
                wList.add(0,w);
            }
            if (w.getWireName().contains("END")) {
                wList.add(w);
            }
        }
        for (Wire w1 : wList) {
            if (w1.getTile().getColumn() < minCol)
                minCol = w1.getTile().getColumn();
            if (w1.getTile().getColumn() > maxCol)
                maxCol = w1.getTile().getColumn();
            if (w1.getTile().getRow() < minRow)
                minRow = w1.getTile().getRow();
            if (w1.getTile().getRow() > maxRow)
                maxRow = w1.getTile().getRow();
        }
        int col1 = minCol;
        int row1 = minRow;
        int col2 = maxCol;
        int row2 = maxRow;
        if (groupWireDir == GroupWireDirection.HORIZONTAL && col1 < col2) {
            result += timingModel.computeHorizontalDistFromArray(col1, col2, groupDelayType);
        }
        if (groupWireDir == GroupWireDirection.VERTICAL && row1 < row2) {
            result += timingModel.computeVerticalDistFromArray(row1, row2, groupDelayType);
        }
        this.d = (short) result;
        return (short) result;
    }
}
