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
    public ImmutableTimingGroup(Node exitNode, Node entryNode, IntentCode exitNodeIntentCode, IntentCode entryNodeIntentCode) {
        this.exitNode  = exitNode;
        this.entryNode = entryNode;
        Pair<GroupDelayType,GroupWireDirection> data = computeTypes(exitNode, exitNodeIntentCode);
        this.groupDelayType = data.getFirst();
        this.groupWireDir   = data.getSecond();
    }

    public ImmutableTimingGroup(Node exitNode, IntentCode exitNodeIntentCode) {
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

    public Node exitNode() {
        return exitNode;
    }

    public Node entryNode() {
        return entryNode;
    }
    // ------------------------------------   private ----------------------------------------


    // TODO: Do I need to keep nodes around? If not, store hashCode
    final private Node exitNode;  // drive entryNode of other TGs.
    // TODO: Do we need to keep track of entryNode?  We can save memory by not keeping it, but will spend more time for backward traveral.
    final private Node entryNode; // can drive exitNode of other TGs, but never drive entry nodes.

    // if groupDelayType = INTERNAL, PINFEED, PIN_BOUNCE, GLOBAL, or OTHER ignore GroupWireDir
    final private GroupDelayType groupDelayType;
    // used to lookup coefficients based on directions
    final private GroupWireDirection groupWireDir; // ver, hor


    private GroupWireDirection getDirection (Node node) {
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
    private Pair<GroupDelayType, GroupWireDirection> computeTypes(Node node, IntentCode intentCode) {
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
}
