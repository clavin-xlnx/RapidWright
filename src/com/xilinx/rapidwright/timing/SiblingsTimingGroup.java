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

import com.xilinx.rapidwright.design.PinType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


/**
 * A class to represent a list of TimingGroups that share the same key timing group.
 * This class is immutable because its states never change after construction.
 */
public class SiblingsTimingGroup {

    private List<TimingGroup> siblings;
    private TimingModel       timingModel;
    private int               hashCode;

    /**
     * Construct SiblingsTimingGroup from a sitePin which can be input or output.
     * This is the only public constructor of this class. Other SiblingsTimingGroups can be created indirectly.
     * @param sitePin
     * @param timingModel
     * @throws IllegalArgumentException
     */
    // This method don't check if the corresponding node of the sitePin is reserved node.
    // It is because, there is way to not create SiblingsTimingGroup if the node is reserved.
    // Thus, the caller must check that before calling this ctor.
    public SiblingsTimingGroup (SitePinInst sitePin, TimingModel timingModel) throws IllegalArgumentException {

        Node node = sitePin.getConnectedNode();
        if (node == null) {
            String errMsg = String.format("SiblingsTimingGroup ctor found sitePin %s whose node is null.", sitePin.getName());
            throw new IllegalArgumentException(errMsg);
        }

        this.timingModel = timingModel;
        this.siblings    = new ArrayList<>();
        
        if (sitePin.getPinType() == PinType.IN) {
            // input sitepin has two nodes
            Node nextNextNode = node;
            IntentCode nextNextIc = nextNextNode.getAllWiresInNode()[0].getIntentCode();

            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) {
                IntentCode nextPrvIc = nextPrvNode.getAllWiresInNode()[0].getIntentCode();
                // I don't see pip is used in computeTypes or delay calculation. Thus, I don't populate it.
                TimingGroup newTS = new TimingGroup(timingModel,
                        new ArrayList<Pair<Node, IntentCode>>() {{
                            add(new Pair<>(nextPrvNode, nextPrvIc));
                            add(new Pair<>(nextNextNode, nextNextIc));
                        }},
                        new ArrayList<PIP>());
                siblings.add(newTS);
            }
        } else {
            // output sitepin has only one node
            TimingGroup tg = new TimingGroup(timingModel);
            tg.add(node, node.getAllWiresInNode()[0].getIntentCode());
            tg.computeTypes();
            timingModel.calcDelay(tg);
            siblings.add(tg);
        }

        this.hashCode    = siblings.get(0).getLastNode().hashCode();
    }

    private SiblingsTimingGroup (List<TimingGroup> tgs, TimingModel timingModel) {
        this.timingModel = timingModel;
        this.siblings    = new ArrayList<>(tgs);
        this.hashCode    = tgs.get(0).getLastNode().hashCode();
    }

    /**
     * Find all downhill timing groups of the current timing group.
     * @return a list of list of timing groups representing a list of siblings -- timing groups sharing the same last nodes,
     * instead of an array of timing groups, returned by getNextTimingGroups().
     */
    public List<SiblingsTimingGroup> getNextSiblingsTimingGroups (Set<Node> reservedNodes) {
        List<SiblingsTimingGroup> result = new ArrayList<>();
        Node prevNode = siblings.get(0).getLastNode();

        // I don't see pip is used in computeTypes or delay calculation. Thus, I don't populate it.
        for (Node nextNode : prevNode.getAllDownhillNodes()) {
            if (!reservedNodes.contains(nextNode)) {
                IntentCode ic = nextNode.getAllWiresInNode()[0].getIntentCode();

                // TODO: is there a better way then relying on name?
                // TODO: I don't to do this loop if ic == NDDE_CLE_OUTPUT. That's way I do 2 level of ifs.
                boolean nextNodeHasGlobalWire = false;
                for (Wire w : nextNode.getAllWiresInNode()) {
                    if (w.getWireName().contains("_GLOBAL")){
                        nextNodeHasGlobalWire = true;
                        break;
                    }
                }

                // CLE_OUT, GLOBAL, LONG TGs have only one node, others have 2 nodes.
                // TG with one node has no siblings.
                if (nextNodeHasGlobalWire || ic == IntentCode.NODE_CLE_OUTPUT ||
                        ic == IntentCode.NODE_HLONG || ic == IntentCode.NODE_VLONG) {
                    TimingGroup newTS = new TimingGroup(timingModel,
                            new ArrayList<Pair<Node, IntentCode>>() {{ add(new Pair<>(nextNode, ic));}},
                            new ArrayList<>());
                   // System.out.println("one node TG: " + newTS.getLastNode().toString());
                    result.add(new SiblingsTimingGroup(new ArrayList<TimingGroup>(){{ add(newTS); }}, this.timingModel));
                } else {
                    // for other TGs look for the 2nd node
                    for (Node nextNextNode : nextNode.getAllDownhillNodes()) {
                        if (!reservedNodes.contains(nextNextNode)) {
                            IntentCode nextNextIc = nextNextNode.getAllWiresInNode()[0].getIntentCode();

                            List<TimingGroup> tgs = new ArrayList<>();
                            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) { // need to get all downhill PIPs
                                // TODO: Currently the whole sibling is considered together as a whole.
                                // TODO: (need to revisit this if the assumption changes.)
                                // TODO: Thus only check nodes that can be key nodes. nextPrvNode is not a key node.
                                IntentCode nextPrvIc = nextPrvNode.getAllWiresInNode()[0].getIntentCode();
                                TimingGroup newTS = new TimingGroup(timingModel,
                                        new ArrayList<Pair<Node, IntentCode>>() {{
                                            add(new Pair<>(nextPrvNode, nextPrvIc));
                                            add(new Pair<>(nextNextNode, nextNextIc));
                                        }},
                                        new ArrayList<>());
                                tgs.add(newTS);
                            }
                            result.add(new SiblingsTimingGroup(tgs, this.timingModel));//TODO siblings used here causing the bug
                        }else{
                        	//System.out.println("nextNextNode is reserved");
                        }
                    }
                }
            }else{
            	//System.out.println("nextNode is reserved");
            }
        }
        return result;
    }

    /**
     * @return a brief description of SiblingsTimingGroup. The key node is listed first follows by ':"
     * and then the list of first node of each sibling. For example, an object with 2 siblings will return
     * A : B C . An object with no sibling returns "A :" if it has only one node (no sibling by definition) or "A : B"
     * if it has two nodes. Each node information is the name of the first wire of the node.
     */
    // Assume that this is used only for debuging -- infrequent call. Otherwise, it should be done in ctor and cache it.
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(siblings.get(0).getLastNode().getAllWiresInNode()[0].getWireName());
        builder.append("  : ");

        if (siblings.get(0).getNodes().size() > 1) {
            for (TimingGroup tg : siblings) {
                builder.append(' ');
                builder.append(tg.getNode(0).getAllWiresInNode()[0].getWireName());
            }
        }
        return builder.toString();
    }

    public List<TimingGroup> getSiblings() {
		return siblings;
	}

	@Override
    public int hashCode() {
        return hashCode;
    }
}
