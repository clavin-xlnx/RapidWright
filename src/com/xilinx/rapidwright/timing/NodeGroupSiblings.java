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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * A class to represent a list of NodeGroups that share the same exit node.
 * This class is immutable because its states never change after construction.
 * There only two way to instantiate object of this class.
 * 1) Call constructor on a SitePinInst.
 * 2) Call getNextNodeGroups to get a downhill siblings of the given sibling.
 */
public class NodeGroupSiblings {

    public EntryNode getExitNode() {
        return siblings[0].exitNode();
    }
    
    public NodeGroup[] getSiblings() {
        return siblings;
    }

    public GroupDelayType groupDelayType() {
        return this.type;
    }
    
    public GroupWireDirection groupWireDirectiont() {
        return this.siblings[0].wireDirection();
    }
    
    public NodeGroup getThruNodeGroup(EntryNode lastExitNode){
    	if(this.fanins.size() > 0)
    		return this.fanins.get(lastExitNode);
    	return this.siblings[0];
    }
    
    /**
     * Construct NodeGroupSiblings from a sitePin which can be input or output.
     * This is the only public constructor of this class. Other NodeGroupSiblings can be created indirectly from an object of this class.
     * @param sitePin
     * @throws IllegalArgumentException
     */
    // This method don't check if the corresponding node of the sitePin is reserved node.
    // It is because, there is way to not create NodeGroupSiblings if the node is reserved.
    // Thus, the caller must check that before calling this ctor.
    public NodeGroupSiblings (SitePinInst sitePin) {

        Node node = sitePin.getConnectedNode();

        List<NodeGroup> siblings    = new ArrayList<>();
        this.fanins = new HashMap<>();
        //TODO YZ: connected node of IN sitePinInst to BRAM has INTENT_DEFAULT code
        if (sitePin.getPinType() == PinType.IN) {
            // input sitepin has two nodes
            Node nextNextNode = node;
            IntentCode nextNextIc = nextNextNode.getIntentCode();

            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) {
                IntentCode nextPrvIc = nextPrvNode.getIntentCode();
                NodeGroup newTS = new NodeGroup(
                        EntryNode.create(nextNextNode),
                        EntryNode.create(nextPrvNode),
                        nextNextIc, nextPrvIc);
                siblings.add(newTS);
            }
        } else {
            // output sitepin has only one node
            NodeGroup ng = new NodeGroup(
                    EntryNode.create(node), node.getIntentCode());
            siblings.add(ng);
        }

        this.siblings = siblings.toArray(new NodeGroup[siblings.size()]);
        this.hashCode = this.siblings[0].exitNode().hashCode();
        this.type     = GroupDelayType.PINFEED;
        this.populateFanin();
    }

    // TODO: this should come from device model
    boolean isExcluded(Node node) {
        // these nodes are bleeding down
        HashSet<String> excludeAboveRclk = new HashSet<String>() {{
            add("SDQNODE_E_0_FT1");
            add("SDQNODE_E_2_FT1");
            add("SDQNODE_W_0_FT1");
            add("SDQNODE_W_2_FT1");
            add("EE12_BEG0");
            add("WW2_E_BEG0");
            add("WW2_W_BEG0");
        }};
        // these nodes are bleeding up
        HashSet<String> excludeBelowRclk = new HashSet<String>() {{
            add("SDQNODE_E_91_FT0");
            add("SDQNODE_E_93_FT0");
            add("SDQNODE_E_95_FT0");
            add("SDQNODE_W_91_FT0");
            add("SDQNODE_W_93_FT0");
            add("SDQNODE_W_95_FT0");
            add("EE12_BEG7");
            add("WW1_W_BEG7");
        }};

        Pattern pattern = Pattern.compile("^INT_X[\\d]+Y([\\d]+)");
        List<String> items  = Arrays.asList(node.toString().split("/"));
        Matcher matcher = pattern.matcher(items.get(0));

        if (matcher.find()) {
            String yCoorStr = matcher.group(1);
            int y = Integer.parseInt(yCoorStr);

            if ((y-30)%60 == 0) { // above RCLK
                if (excludeAboveRclk.contains(items.get(1))) {
                    return true;
                }
            } else if ((y-29)%60 == 0) { // below RCLK
                if (excludeBelowRclk.contains(items.get(1))) {
                    return true;
                }
            }
        } else {

        }
        return false;
    }


    /**
     * Find all downhill node groups of the current node group.
     * @return a list of list of node groups representing a list of siblings -- node groups sharing the same last nodes,
     * instead of an array of node groups, returned by getNextNodeGroupSiblings().
     */
    public List<NodeGroupSiblings> getNextNodeGroupSiblings(Set<Node> reservedNodes) {
        List<NodeGroupSiblings> result =  new ArrayList<>();

        Node prevNode = siblings[0].exitNode();
        if(prevNode.getIntentCode() == IntentCode.NODE_PINFEED){ 
        	return result;
        }
        
        // I don't see pip is used in computeTypes or delay calculation. Thus, I don't populate it.
        for (Node nextNode : prevNode.getAllDownhillNodes()) {
        	
            if (!reservedNodes.contains(nextNode)) {

                // If this tile is next to RCLK SDQNODE will bleed over. This is likely to cause long delay.
                // TODO: to exclude only next to RCLK
                if (isExcluded(nextNode))
                    continue;
                
                IntentCode ic = nextNode.getIntentCode();
                //TODO check: only excluding NODE_CLE_OUTUT will cause an issue of non-thruNodeGroup between two ITERRR in setChildren()
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

                // CLE_OUT, GLOBAL, LONG NGs have only one node, others have 2 nodes.
                // NG with one node has no siblings.
                if (nextNodeHasGlobalWire) {
                    NodeGroup newTS = new NodeGroup(EntryNode.create(nextNode), ic);
                    result.add(new NodeGroupSiblings(new ArrayList<NodeGroup>() {{add(newTS);}}, 
                    		GroupDelayType.GLOBAL));

                } else if (ic == IntentCode.NODE_CLE_OUTPUT) {
                    NodeGroup newTS = new NodeGroup(EntryNode.create(nextNode), ic);
                    result.add(new NodeGroupSiblings(new ArrayList<NodeGroup>(){{ add(newTS); }},
                                                                  GroupDelayType.OTHER));

                } else if (ic == IntentCode.NODE_HLONG || ic == IntentCode.NODE_VLONG) {
                    NodeGroup newTS = new NodeGroup(EntryNode.create(nextNode), ic);
                    result.add(new NodeGroupSiblings(new ArrayList<NodeGroup>(){{ add(newTS); }}, 
                    									GroupDelayType.LONG));

                } else {
                    // for other NGs look for the 2nd node
                    for (Node nextNextNode : nextNode.getAllDownhillNodes()) {

                        if (isExcluded(nextNextNode))
                            continue;
                        
                        if (!reservedNodes.contains(nextNextNode)) {
                            IntentCode nextNextIc = nextNextNode.getIntentCode();
                            List<NodeGroup> ngs = new ArrayList<>();
                            NodeGroup throughNg = null;
                            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) { // need to get all downhill PIPs

                                String[] int_node = nextPrvNode.toString().split("/");
//                                if (int_node[1].contains("VCC_WIRE"))
//                                    continue;

                                // EE12_BEG0 bleed down, EE12_BEG7 bleed up
                                // TODO: to exclude only next to RCLK
//                                if (     nextPrvNode.toString().contains("/WW1_W_BEG7") // bleed up
//                                      || nextPrvNode.toString().contains("/WW2_E_BEG0") // bleed down
//                                      || nextPrvNode.toString().contains("/WW2_W_BEG0"))// bleed down
                                if (isExcluded(nextPrvNode) || int_node[1].contains("VCC_WIRE"))
                                    continue;
                                
                                // TODO: Currently the whole sibling is considered together as a whole.
                                // TODO: (need to revisit this if the assumption changes.)
                                // TODO: Thus only check nodes that can be key nodes. nextPrvNode is not a key node.
                                IntentCode nextPrvIc       = nextPrvNode.getIntentCode();
                                NodeGroup newTS = new NodeGroup(
                                        EntryNode.create(nextNextNode),
                                        EntryNode.create(nextPrvNode),
                                        nextNextIc, nextPrvIc);
                                ngs.add(newTS);
                                if (nextPrvNode.equals(nextNode))
                                    throughNg = newTS;
                                    /*if(prevNode.toString().equals("INT_X12Y97/EE12_BEG7") && 
                                    		nextNextNode.toString().equals("INT_X18Y97/INT_INT_SDQ_7_INT_OUT0"))
                                    		System.out.println(throughNg.toString());*/
                                
                            }
                            // TODO: find out the type if the type is needed. Don't do it to reduce runtime
                            result.add(new NodeGroupSiblings(ngs, ngs.get(0).delayType()));//GroupDelayType.OTHER));
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * @return a brief description of SiblingsNodeGroup. The key node is listed first follows by ':"
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
        builder.append(siblings[0].exitNode().getIntentCode());
////        builder.append("  - ");
////        builder.append(siblings[0].exitNode().getAllWiresInNode()[0].getWireName());
//        builder.append("  : ");
//
//        for (NodeGroup ng : siblings) {
//            builder.append(" , ");
//            Node entryNode = ng.entryNode();
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


    final private NodeGroup[] siblings;
    final private int                    hashCode;
    final private GroupDelayType         type;
    private Map<EntryNode,NodeGroup> fanins; //for nextSiblings <last exit node, nodeGroup in this siblings>


    private NodeGroupSiblings (List<NodeGroup> ngs, GroupDelayType type) {
        this.siblings = ngs.toArray(new NodeGroup[ngs.size()]); // *******
        this.hashCode = this.siblings[0].hashCode();
        this.type     = type;
        this.fanins = new HashMap<>();
        this.populateFanin();//use the List<Pair<nodeGroupSiblings, NodeGroup>> returned from getNextNodeGroupSiblings()
    }
    
    private void populateFanin(){
		for(NodeGroup nodeGroup : this.siblings){
    		if(nodeGroup.entryNode() != null){
    			for(Node n:nodeGroup.entryNode().getAllUphillNodes()){
        			this.fanins.put(EntryNode.create(n), nodeGroup);
        		}
    		}else{//output pin Sibling and global siblings
    			for(Node n:this.siblings[0].exitNode().getAllUphillNodes()){
        			this.fanins.put(EntryNode.create(n), this.siblings[0]);
        		}
    		}
		}
    	
    }


}


















