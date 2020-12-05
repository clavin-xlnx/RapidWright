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
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Wire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


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
        this.virtual  = false;
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
     * Find all downhill timing groups of the current timing group.
     * @return a list of list of timing groups representing a list of siblings -- timing groups sharing the same last nodes,
     * instead of an array of timing groups, returned by getNextTimingGroups().
     */
    public List<SiblingsTimingGroup> getNextSiblingTimingGroups(Set<Node> reservedNodes) {




        List<SiblingsTimingGroup> result =  new ArrayList<>();

        if (this.isVirtual()) {
            this.virtual = false;
//             Route should evaluate MH distance of the virtual node and delay of its parent. Does this make sense?
//             It will not if none of the virtual nodes in this batch will become least cost.
            result.add(this);
            return result;
        }

        boolean aboveRclk = true;
        boolean belowRclk = true;

        Node prevNode = siblings[0].exitNode();

        // I don't see pip is used in computeTypes or delay calculation. Thus, I don't populate it.
        for (Node nextNode : prevNode.getAllDownhillNodes()) {
            if (!reservedNodes.contains(nextNode)) {

                // If this tile is next to RCLK SDQNODE will bleed over. This is likely to cause long delay.
                // TODO: to exclude only next to RCLK
//                if (nextNode.toString().contains("/SDQNODE_"))
//                    continue;
                if (isExcluded(nextNode))
                    continue;


                IntentCode ic = nextNode.getAllWiresInNode()[0].getIntentCode();

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
                                                                  GroupDelayType.GLOBAL, false));
                } else if (ic == IntentCode.NODE_CLE_OUTPUT) {
                    ImmutableTimingGroup newTS = new ImmutableTimingGroup(NodeWithFaninInfo.create(nextNode), ic);
                    result.add(new SiblingsTimingGroup(new ArrayList<ImmutableTimingGroup>(){{ add(newTS); }},
                                                                  GroupDelayType.OTHER, false));
                } else if (ic == IntentCode.NODE_HLONG || ic == IntentCode.NODE_VLONG) {
                    // TODO: to exclude only next to RCLK
//                    if (       nextNode.toString().contains("/EE12_BEG0")  // bleed down
//                            || nextNode.toString().contains("/EE12_BEG7")) // bleed up
//                      if (isExcluded(nextNode))
//                          continue;

                    ImmutableTimingGroup newTS = new ImmutableTimingGroup(NodeWithFaninInfo.create(nextNode), ic);
                    result.add(new SiblingsTimingGroup(new ArrayList<ImmutableTimingGroup>(){{ add(newTS); }},
                                                                  GroupDelayType.LONG, false));
                } else {
                    // for other TGs look for the 2nd node
                    for (Node nextNextNode : nextNode.getAllDownhillNodes()) {
                        if (isExcluded(nextNextNode))
                            continue;
                        if (!reservedNodes.contains(nextNextNode)) {
                            IntentCode nextNextIc = nextNextNode.getAllWiresInNode()[0].getIntentCode();

                            List<ImmutableTimingGroup> tgs = new ArrayList<>();
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
                                IntentCode nextPrvIc       = nextPrvNode.getAllWiresInNode()[0].getIntentCode();
                                ImmutableTimingGroup newTS = new ImmutableTimingGroup(
                                        NodeWithFaninInfo.create(nextNextNode),
                                        NodeWithFaninInfo.create(nextPrvNode),
                                        nextNextIc, nextPrvIc);
                                tgs.add(newTS);
                            }
                            // TODO: find out the type if the type is needed. Don't do it to reduce runtime
                            result.add(new SiblingsTimingGroup(tgs,tgs.get(0).delayType(),true));
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

    public boolean isVirtual() {
        return this.virtual;
    }


    // ------------------------------------   private ----------------------------------------


    final private ImmutableTimingGroup[] siblings;
    final private int                    hashCode;
    final private GroupDelayType         type;
    private       boolean                virtual;


    private SiblingsTimingGroup (List<ImmutableTimingGroup> tgs, GroupDelayType type, boolean virtual) {
        this.siblings = tgs.toArray(new ImmutableTimingGroup[tgs.size()]);
        this.hashCode = this.siblings[0].hashCode();
        this.type     = type;
        this.virtual  = virtual;
    }


    public int testExclude(String nodeName, boolean should, Device device) {
        int count = 0;
        Node node  = new Node(nodeName, device);
        boolean res = isExcluded(node);
        String error = "";
        if (res != should) {
            error = " *ERROR*";
            count++;
        }
        if (res)
            System.out.println(error + " " + node + " is excluded");
        else
            System.out.println(error + " " + node + " is NOT excluded");
        return count;
    }
    // ------------------------------------   test ----------------------------------------


    public static void main(String args[]) {
        Device device = Device.getDevice("xcvu3p-ffvc1517");
        String siteName = "SLICE_X0Y0";
        SiteInst siteInst = new SiteInst(siteName, SiteTypeEnum.SLICEL);
        siteInst.place(device.getSite(siteName));
        SitePinInst pin = new SitePinInst("AQ", siteInst);

        int numExpansion = 10;
        SiblingsTimingGroup s = new SiblingsTimingGroup(pin);
        System.out.println("Start expansion from " + s.toString());
        Set<Node> empty = new HashSet<Node>();
        for (int i = 0; i < numExpansion; i++) {
            System.out.println("----");
            List<SiblingsTimingGroup> next = s.getNextSiblingTimingGroups(empty);
            for (SiblingsTimingGroup sb : next) {
                System.out.println(sb.toString());
            }
            // just pack one to expand
            s = next.get(0);
        }

        int numErr = 0;

        System.out.println("\ndon't exclude");
        // bleed down
        numErr += s.testExclude("INT_X15Y29/SDQNODE_E_0_FT1",false,device);
        numErr += s.testExclude("INT_X15Y29/SDQNODE_E_2_FT1",false,device);
        numErr += s.testExclude("INT_X15Y29/SDQNODE_W_0_FT1",false,device);
        numErr += s.testExclude("INT_X15Y29/SDQNODE_W_2_FT1",false,device);
        numErr += s.testExclude("INT_X15Y29/EE12_BEG0",false,device);
        numErr += s.testExclude("INT_X15Y29/WW2_E_BEG0",false,device);
        numErr += s.testExclude("INT_X15Y29/WW2_W_BEG0",false,device);

        System.out.println("\nexclude");
        // bleed up
        numErr += s.testExclude("INT_X15Y29/SDQNODE_W_91_FT0",true,device);
        numErr += s.testExclude("INT_X15Y29/SDQNODE_W_93_FT0",true,device);
        numErr += s.testExclude("INT_X15Y29/SDQNODE_W_95_FT0",true,device);
        numErr += s.testExclude("INT_X15Y29/SDQNODE_E_91_FT0",true,device);
        numErr += s.testExclude("INT_X15Y29/SDQNODE_E_93_FT0",true,device);
        numErr += s.testExclude("INT_X15Y29/SDQNODE_E_95_FT0",true,device);
        numErr += s.testExclude("INT_X15Y29/EE12_BEG7",true,device);
        numErr += s.testExclude("INT_X15Y29/WW1_W_BEG7",true,device);


        System.out.println("\nexclude");
        // bleed down
        numErr += s.testExclude("INT_X15Y30/SDQNODE_E_0_FT1",true,device);
        numErr += s.testExclude("INT_X15Y30/SDQNODE_E_2_FT1",true,device);
        numErr += s.testExclude("INT_X15Y30/SDQNODE_W_0_FT1",true,device);
        numErr += s.testExclude("INT_X15Y30/SDQNODE_W_2_FT1",true,device);
        numErr += s.testExclude("INT_X15Y30/EE12_BEG0",true,device);
        numErr += s.testExclude("INT_X15Y30/WW2_E_BEG0",true,device);
        numErr += s.testExclude("INT_X15Y30/WW2_W_BEG0",true,device);

        System.out.println("\ndon't exclude");
        // bleed up
        numErr += s.testExclude("INT_X15Y30/SDQNODE_W_91_FT0",false,device);
        numErr += s.testExclude("INT_X15Y30/SDQNODE_W_93_FT0",false,device);
        numErr += s.testExclude("INT_X15Y30/SDQNODE_W_95_FT0",false,device);
        numErr += s.testExclude("INT_X15Y30/SDQNODE_E_91_FT0",false,device);
        numErr += s.testExclude("INT_X15Y30/SDQNODE_E_93_FT0",false,device);
        numErr += s.testExclude("INT_X15Y30/SDQNODE_E_95_FT0",false,device);
        numErr += s.testExclude("INT_X15Y30/EE12_BEG7",false,device);
        numErr += s.testExclude("INT_X15Y30/WW1_W_BEG7",false,device);




        System.out.println("\ndon't exclude");
        // bleed down
        numErr += s.testExclude("INT_X15Y89/SDQNODE_E_0_FT1",false,device);
        numErr += s.testExclude("INT_X15Y89/SDQNODE_E_2_FT1",false,device);
        numErr += s.testExclude("INT_X15Y89/SDQNODE_W_0_FT1",false,device);
        numErr += s.testExclude("INT_X15Y89/SDQNODE_W_2_FT1",false,device);
        numErr += s.testExclude("INT_X15Y89/EE12_BEG0",false,device);
        numErr += s.testExclude("INT_X15Y89/WW2_E_BEG0",false,device);
        numErr += s.testExclude("INT_X15Y89/WW2_W_BEG0",false,device);

        System.out.println("\nexclude");
        // bleed up
        numErr += s.testExclude("INT_X15Y89/SDQNODE_W_91_FT0",true,device);
        numErr += s.testExclude("INT_X15Y89/SDQNODE_W_93_FT0",true,device);
        numErr += s.testExclude("INT_X15Y89/SDQNODE_W_95_FT0",true,device);
        numErr += s.testExclude("INT_X15Y89/SDQNODE_E_91_FT0",true,device);
        numErr += s.testExclude("INT_X15Y89/SDQNODE_E_93_FT0",true,device);
        numErr += s.testExclude("INT_X15Y89/SDQNODE_E_95_FT0",true,device);
        numErr += s.testExclude("INT_X15Y89/EE12_BEG7",true,device);
        numErr += s.testExclude("INT_X15Y89/WW1_W_BEG7",true,device);


        System.out.println("\nexclude");
        // bleed down
        numErr += s.testExclude("INT_X15Y90/SDQNODE_E_0_FT1",true,device);
        numErr += s.testExclude("INT_X15Y90/SDQNODE_E_2_FT1",true,device);
        numErr += s.testExclude("INT_X15Y90/SDQNODE_W_0_FT1",true,device);
        numErr += s.testExclude("INT_X15Y90/SDQNODE_W_2_FT1",true,device);
        numErr += s.testExclude("INT_X15Y90/EE12_BEG0",true,device);
        numErr += s.testExclude("INT_X15Y90/WW2_E_BEG0",true,device);
        numErr += s.testExclude("INT_X15Y90/WW2_W_BEG0",true,device);

        System.out.println("\ndon't exclude");
        // bleed up
        numErr += s.testExclude("INT_X15Y90/SDQNODE_W_91_FT0",false,device);
        numErr += s.testExclude("INT_X15Y90/SDQNODE_W_93_FT0",false,device);
        numErr += s.testExclude("INT_X15Y90/SDQNODE_W_95_FT0",false,device);
        numErr += s.testExclude("INT_X15Y90/SDQNODE_E_91_FT0",false,device);
        numErr += s.testExclude("INT_X15Y90/SDQNODE_E_93_FT0",false,device);
        numErr += s.testExclude("INT_X15Y90/SDQNODE_E_95_FT0",false,device);
        numErr += s.testExclude("INT_X15Y90/EE12_BEG7",false,device);
        numErr += s.testExclude("INT_X15Y90/WW1_W_BEG7",false,device);


        System.out.println("\ndon't exclude");
        // bleed down
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_0_FT1",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_2_FT1",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_0_FT1",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_2_FT1",false,device);
        numErr += s.testExclude("INT_X15Y99/EE12_BEG0",false,device);
        numErr += s.testExclude("INT_X15Y99/WW2_E_BEG0",false,device);
        numErr += s.testExclude("INT_X15Y99/WW2_W_BEG0",false,device);

        // bleed up
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_91_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_93_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_95_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_91_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_93_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_95_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/EE12_BEG7",false,device);
        numErr += s.testExclude("INT_X15Y99/WW1_W_BEG7",false,device);


        // bleed down
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_0_FT1",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_2_FT1",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_0_FT1",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_2_FT1",false,device);
        numErr += s.testExclude("INT_X15Y99/EE12_BEG0",false,device);
        numErr += s.testExclude("INT_X15Y99/WW2_E_BEG0",false,device);
        numErr += s.testExclude("INT_X15Y99/WW2_W_BEG0",false,device);

        // bleed up
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_91_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_93_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_W_95_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_91_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_93_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/SDQNODE_E_95_FT0",false,device);
        numErr += s.testExclude("INT_X15Y99/EE12_BEG7",false,device);
        numErr += s.testExclude("INT_X15Y99/WW1_W_BEG7",false,device);


        System.out.println("total number of errors is " + numErr);
    }
}





















