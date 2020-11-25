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
import com.xilinx.rapidwright.device.Wire;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * A class to represent a list of ImmutableTimingGroups that share the same exit node.
 * This class is immutable because its states never change after construction.
 * There only two way to instantiate object of this class.
 * 1) Call constructor on a SitePinInst.
 * 2) Call getNextSiblingTimingGroups to get a downhill siblings of the given sibling.
 */
public class SiblingsTimingGroup {

    public NodeWithFaninInfo getExitNode() {
        return siblings[0].exitNode();
    }

    // TODO: remove this if we know for sure that entryNode has only one wire
    public ImmutableTimingGroup[] getSiblings() {
        return siblings;
    }

    public GroupDelayType type() {
        return this.type;
    }
    
    public ImmutableTimingGroup getThruImmuTg(NodeWithFaninInfo lastExitNode){
    	if(this.fanins.size() > 0)
    		return this.fanins.get(lastExitNode);
    	return this.siblings[0];
    }
    
    /**
     * Construct SiblingsTimingGroup from a sitePin which can be input or output.
     * This is the only public constructor of this class. Other SiblingsTimingGroups can be created indirectly from an object of this class.
     * @param sitePin
     * @throws IllegalArgumentException
     */
    // This method don't check if the corresponding node of the sitePin is reserved node.
    // It is because, there is way to not create SiblingsTimingGroup if the node is reserved.
    // Thus, the caller must check that before calling this ctor.
    public SiblingsTimingGroup (SitePinInst sitePin) {

        Node node = sitePin.getConnectedNode();

        List<ImmutableTimingGroup> siblings    = new ArrayList<>();
        this.fanins = new HashMap<>();

        if (sitePin.getPinType() == PinType.IN) {
            // input sitepin has two nodes
            Node nextNextNode = node;
            IntentCode nextNextIc = nextNextNode.getAllWiresInNode()[0].getIntentCode();

            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) {
                IntentCode nextPrvIc = nextPrvNode.getAllWiresInNode()[0].getIntentCode();
                ImmutableTimingGroup newTS = new ImmutableTimingGroup(
                        NodeWithFaninInfo.create(nextNextNode),
                        NodeWithFaninInfo.create(nextPrvNode),
                        nextNextIc, nextPrvIc);
                siblings.add(newTS);
            }
        } else {
            // output sitepin has only one node
            ImmutableTimingGroup tg = new ImmutableTimingGroup(
                    NodeWithFaninInfo.create(node), node.getAllWiresInNode()[0].getIntentCode());
            siblings.add(tg);
        }

        this.siblings = siblings.toArray(new ImmutableTimingGroup[siblings.size()]);
        this.hashCode = this.siblings[0].exitNode().hashCode();
        this.type     = GroupDelayType.PINFEED;
        this.populateFanin();
    }

    /**
     * Find all downhill timing groups of the current timing group.
     * @return a list of list of timing groups representing a list of siblings -- timing groups sharing the same last nodes,
     * instead of an array of timing groups, returned by getNextTimingGroups().
     */
    public List<SiblingsTimingGroup> getNextSiblingTimingGroups(Set<Node> reservedNodes) {
        List<SiblingsTimingGroup> result = new ArrayList<SiblingsTimingGroup>();

        Node prevNode = siblings[0].exitNode();
        if(prevNode.getAllWiresInNode()[0].getIntentCode() == IntentCode.NODE_PINFEED){ 
        	return result;
        }
        
        // I don't see pip is used in computeTypes or delay calculation. Thus, I don't populate it.
        for (Node nextNode : prevNode.getAllDownhillNodes()) {
        	
            if (!reservedNodes.contains(nextNode)) {

                // If this tile is next to RCLK SDQNODE will bleed over. This is likely to cause long delay.
                // TODO: to exclude only next to RCLK
                if (nextNode.toString().contains("/SDQNODE_"))
                    continue;
                
                IntentCode ic = nextNode.getAllWiresInNode()[0].getIntentCode();
                //TODO check: only excluding NODE_CLE_OUTUT will cause an issue of non-thruImmuTg between two ITERRR in setChildren()
                if(ic == IntentCode.NODE_PINFEED || ic == IntentCode.NODE_CLE_OUTPUT){ 
                	continue;
                }
                
                // TODO: is there a better way then relying on name?
                // TODO: I don't to do this loop if ic == NDDE_CLE_OUTPUT. That's way I do 2 level of ifs.
                boolean nextNodeHasGlobalWire = false;
                for (Wire w : nextNode.getAllWiresInNode()) {
                    if (w.getWireName().contains("_GLOBAL"))
                        nextNodeHasGlobalWire = true;
                }

                // CLE_OUT, GLOBAL, LONG TGs have only one node, others have 2 nodes.
                // TG with one node has no siblings.
                if (nextNodeHasGlobalWire) {
                    ImmutableTimingGroup newTS = new ImmutableTimingGroup(NodeWithFaninInfo.create(nextNode), ic);
                    result.add(new SiblingsTimingGroup(new ArrayList<ImmutableTimingGroup>() {{add(newTS);}}, 
                    		GroupDelayType.GLOBAL));

                } else if (ic == IntentCode.NODE_CLE_OUTPUT) {
                    ImmutableTimingGroup newTS = new ImmutableTimingGroup(NodeWithFaninInfo.create(nextNode), ic);
                    result.add(new SiblingsTimingGroup(new ArrayList<ImmutableTimingGroup>(){{ add(newTS); }},
                                                                  GroupDelayType.OTHER));

                } else if (ic == IntentCode.NODE_HLONG || ic == IntentCode.NODE_VLONG) {
                    // TODO: to exclude only next to RCLK
                    if (       nextNode.toString().contains("/EE12_BEG0")  // bleed down
                            || nextNode.toString().contains("/EE12_BEG7")) // bleed up
                        continue;

                    ImmutableTimingGroup newTS = new ImmutableTimingGroup(NodeWithFaninInfo.create(nextNode), ic);
                    result.add(new SiblingsTimingGroup(new ArrayList<ImmutableTimingGroup>(){{ add(newTS); }}, 
                    									GroupDelayType.LONG));

                } else {
                    // for other TGs look for the 2nd node
                    for (Node nextNextNode : nextNode.getAllDownhillNodes()) {
                    	//TODO check - wrong - will cause non-thruImmuTg issue for other siblings
                    	/*IntentCode code = nextNextNode.getAllWiresInNode()[0].getIntentCode();
                        if(code == IntentCode.NODE_CLE_OUTPUT){//code == IntentCode.NODE_PINFEED){
                        	continue;
                        }*/
                        
                        if (!reservedNodes.contains(nextNextNode)) {
                            IntentCode nextNextIc = nextNextNode.getAllWiresInNode()[0].getIntentCode();
                            List<ImmutableTimingGroup> tgs = new ArrayList<>();
                            ImmutableTimingGroup throughTg = null;
                            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) { // need to get all downhill PIPs

                                String[] int_node = nextPrvNode.toString().split("/");
                                if (int_node[1].contains("VCC_WIRE"))
                                    continue;

                                // EE12_BEG0 bleed down, EE12_BEG7 bleed up
                                // TODO: to exclude only next to RCLK
                                if (     nextPrvNode.toString().contains("/WW1_W_BEG7") // bleed up
                                      || nextPrvNode.toString().contains("/WW2_E_BEG0") // bleed down
                                      || nextPrvNode.toString().contains("/WW2_W_BEG0"))// bleed down
                                    continue;
                                
                                // TODO: Currently the whole sibling is considered together as a whole.
                                // TODO: (need to revisit this if the assumption changes.)
                                // TODO: Thus only check nodes that can be key nodes. nextPrvNode is not a key node.
                                IntentCode nextPrvIc       = nextPrvNode.getAllWiresInNode()[0].getIntentCode();
                                ImmutableTimingGroup newTS = new ImmutableTimingGroup(
                                        NodeWithFaninInfo.create(nextNextNode),
                                        NodeWithFaninInfo.create(nextPrvNode),
                                        nextNextIc, nextPrvIc);
                                tgs.add(newTS);
                                if (nextPrvNode.equals(nextNode))
                                    throughTg = newTS;
                                    /*if(prevNode.toString().equals("INT_X12Y97/EE12_BEG7") && 
                                    		nextNextNode.toString().equals("INT_X18Y97/INT_INT_SDQ_7_INT_OUT0"))
                                    		System.out.println(throughTg.toString());*/
                                
                            }
                            // TODO: find out the type if the type is needed. Don't do it to reduce runtime
                            result.add(new SiblingsTimingGroup(tgs,GroupDelayType.OTHER));
                        }
                    }
                }
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

        // toString will return INT_X0Y1/INODE_E_1_FT1 , while first wire name return just INODE_E_1_FT1
        builder.append(siblings[0].exitNode().toString());
        builder.append("  - ");
        builder.append(siblings[0].exitNode().getAllWiresInNode()[0].getIntentCode());
////        builder.append("  - ");
////        builder.append(siblings[0].exitNode().getAllWiresInNode()[0].getWireName());
//        builder.append("  : ");
//
//        for (ImmutableTimingGroup tg : siblings) {
//            builder.append(" , ");
//            Node entryNode = tg.entryNode();
//            if (entryNode != null) {
//                builder.append(entryNode.toString());
////                builder.append("  - ");
////                builder.append(entryNode.getAllWiresInNode()[0].getWireName());
//            }
//        }
//        RoutingNode n = getTermInfo(siblings[0]);
//        builder.append(n.toString());
        return builder.toString();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
    

    // ------------------------------------   private ----------------------------------------


    final private ImmutableTimingGroup[] siblings;
    final private int                    hashCode;
    final private GroupDelayType         type;
    private Map<NodeWithFaninInfo,ImmutableTimingGroup> fanins; //for nextSiblings <last exit node, timingGroup in this siblings>


    private SiblingsTimingGroup (List<ImmutableTimingGroup> tgs, GroupDelayType type) {
        this.siblings = tgs.toArray(new ImmutableTimingGroup[tgs.size()]); // *******
        this.hashCode = this.siblings[0].hashCode();
        this.type     = type;
        this.fanins = new HashMap<>();
        this.populateFanin();//use the List<Pair<SiblingsTimingGroup, ImmutbleTimingGroup>> returned from getNextSiblingsTimingGroups()
    }
    
    private void populateFanin(){
		for(ImmutableTimingGroup immuTg : this.siblings){
    		if(immuTg.entryNode() != null){
    			for(Node n:immuTg.entryNode().getAllUphillNodes()){
//    				System.out.println(immuTg.toString());
        			this.fanins.put(NodeWithFaninInfo.create(n), immuTg);
        		}
    		}else{//output pin Sibling and global siblings
    			for(Node n:this.siblings[0].exitNode().getAllUphillNodes()){
        			this.fanins.put(NodeWithFaninInfo.create(n), this.siblings[0]);
        		}
    		}
		}
    	
    }
    // ------------------------------------   test ----------------------------------------


   /* public static void main(String args[]) {
        Device device = Device.getDevice("xcvu3p-ffvc1517");
        String siteName = "SLICE_X0Y0";
        SiteInst siteInst = new SiteInst(siteName, SiteTypeEnum.SLICEL);
        siteInst.place(device.getSite(siteName));
        SitePinInst pin = new SitePinInst("AQ", siteInst);
        RouteThruHelper helper = new RouteThruHelper(device);
        int numExpansion = 10;
        SiblingsTimingGroup s = new SiblingsTimingGroup(pin);
        System.out.println("Start expansion from " + s.toString());
        Set<Node> empty = new HashSet<Node>();
        for (int i = 0; i < numExpansion; i++) {
            System.out.println("----");
            List<Pair<SiblingsTimingGroup,ImmutableTimingGroup>> next = s.getNextSiblingTimingGroups(empty, helper);
            for (Pair<SiblingsTimingGroup,ImmutableTimingGroup> sb : next) {
                System.out.println(sb.toString());
            }
            // just pack one to expand
            s = next.get(0).getFirst();
        }
    }*/
}


















