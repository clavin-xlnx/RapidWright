<<<<<<< HEAD
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
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;

import java.util.ArrayList;
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

        // TODO: Can sitePin.getConnectedNode return null ?
//        if (node == null) {
//            String errMsg = String.format("SiblingsTimingGroup ctor found sitePin %s whose node is null.", sitePin.getName());
//            throw new IllegalArgumentException(errMsg);
//        }

        List<ImmutableTimingGroup> siblings    = new ArrayList<>();

        if (sitePin.getPinType() == PinType.IN) {
            // input sitepin has two nodes
            Node nextNextNode = node;
            IntentCode nextNextIc = nextNextNode.getAllWiresInNode()[0].getIntentCode();

            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) {
                IntentCode nextPrvIc = nextPrvNode.getAllWiresInNode()[0].getIntentCode();
                ImmutableTimingGroup newTS = new ImmutableTimingGroup(nextNextNode, nextPrvNode, nextNextIc, nextPrvIc);
                siblings.add(newTS);
            }
        } else {
            // output sitepin has only one node
            ImmutableTimingGroup tg = new ImmutableTimingGroup(node, node.getAllWiresInNode()[0].getIntentCode());
            siblings.add(tg);
        }

        this.siblings = siblings.toArray(new ImmutableTimingGroup[siblings.size()]);
        this.hashCode = this.siblings[0].exitNode().hashCode();
    }

    /**
     * Find all downhill timing groups of the current timing group.
     * @return a list of list of timing groups representing a list of siblings -- timing groups sharing the same last nodes,
     * instead of an array of timing groups, returned by getNextTimingGroups().
     */
    public List<SiblingsTimingGroup> getNextSiblingTimingGroups(Set<Node> reservedNodes) {
        List<SiblingsTimingGroup> result = new ArrayList<>();
        Node prevNode = siblings[0].exitNode();

        // I don't see pip is used in computeTypes or delay calculation. Thus, I don't populate it.
        for (Node nextNode : prevNode.getAllDownhillNodes()) {
            if (!reservedNodes.contains(nextNode)) {
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
                if (nextNodeHasGlobalWire || ic == IntentCode.NODE_CLE_OUTPUT ||
                        ic == IntentCode.NODE_HLONG || ic == IntentCode.NODE_VLONG) {
                    ImmutableTimingGroup newTS = new ImmutableTimingGroup(nextNode, ic);
                    result.add(new SiblingsTimingGroup(new ArrayList<ImmutableTimingGroup>(){{ add(newTS); }}));
                } else {
                    // for other TGs look for the 2nd node
                    for (Node nextNextNode : nextNode.getAllDownhillNodes()) {
                        if (!reservedNodes.contains(nextNextNode)) {
                            IntentCode nextNextIc = nextNextNode.getAllWiresInNode()[0].getIntentCode();

                            List<ImmutableTimingGroup> tgs = new ArrayList<>();
                            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) { // need to get all downhill PIPs
                                // TODO: Currently the whole sibling is considered together as a whole.
                                // TODO: (need to revisit this if the assumption changes.)
                                // TODO: Thus only check nodes that can be key nodes. nextPrvNode is not a key node.
                                IntentCode nextPrvIc       = nextPrvNode.getAllWiresInNode()[0].getIntentCode();
                                ImmutableTimingGroup newTS = new ImmutableTimingGroup(nextNextNode, nextPrvNode, nextNextIc, nextPrvIc);
                                tgs.add(newTS);
                            }
                            result.add(new SiblingsTimingGroup(tgs));
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
        RoutingNode n = getTermInfo(siblings[0]);
        builder.append(n.toString());
        return builder.toString();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }


    // ------------------------------------   private ----------------------------------------


    final private ImmutableTimingGroup[] siblings;
    final private int                    hashCode;


    private SiblingsTimingGroup (List<ImmutableTimingGroup> tgs) {
        this.siblings = tgs.toArray(new ImmutableTimingGroup[tgs.size()]);
        this.hashCode = this.siblings[0].hashCode();
    }
    
	public ImmutableTimingGroup[] getSiblings() {
		// TODO Auto-generated method stub
		return this.siblings;
	}

    class RoutingNode {
        // INT_TILE coordinate
        short x;
        short y;
        // E or W side of INT_TILE
        InterconnectInfo.TileSide side;
        // U or D
        InterconnectInfo.Orientation orientation;
        InterconnectInfo.TimingGroup tg;

        RoutingNode(int x, int y, String side, String direction, String tg) {
            this.x         = (short) x;
            this.y         = (short) y;
            this.side      = InterconnectInfo.TileSide.valueOf(side);
            this.orientation = InterconnectInfo.Orientation.valueOf(direction);
            this.tg        = InterconnectInfo.TimingGroup.valueOf(tg);
        }
        RoutingNode() {
        }
        public String toString() {
            return String.format("x:%d y:%d %s %s %s", x,y,side.name(),tg.name(),orientation.name());
        }
    }

    // input node is exitNode of a tg
    // TODO: This method is loop heavy. If TG is prebuilt, this problem will be solved because all info is pre-recorded.
    private InterconnectInfo.TileSide findTileSideForInternalSingle(Node node) {
        Pattern EPattern = Pattern.compile("([\\w]+)_(E)_");
        Pattern WPattern = Pattern.compile("([\\w]+)_(W)_");

        for (Node prvNode : node.getAllUphillNodes()) { // need to get all downhill PIPs
            String prvNodeName = prvNode.getAllWiresInNode()[0].getWireName();

            Matcher EMatcher = EPattern.matcher(prvNodeName);
            if (EMatcher.find()) {
                return InterconnectInfo.TileSide.E;
            } else {
                Matcher WMatcher = WPattern.matcher(prvNodeName);
                if (WMatcher.find())
                    return InterconnectInfo.TileSide.W;
            }

        }
        return InterconnectInfo.TileSide.M;
    }

    // node.toString()     -  node.getAllWiresInNode()[0].getIntentCode()
    // INT_X0Y0/BYPASS_E9  - NODE_PINBOUNCE  :
    // INT_X0Y0/IMUX_E9  - NODE_PINFEED  :
    // INT_X0Y0/EE2_E_BEG3  - NODE_DOUBLE  :
    // INT_X0Y0/NN1_E_BEG3  - NODE_SINGLE  :
    // INT_X0Y0/NN4_E_BEG2  - NODE_VQUAD  :
    // INT_X0Y0/INT_INT_SDQ_33_INT_OUT1  - NODE_SINGLE  :
    private RoutingNode getTermInfo(ImmutableTimingGroup tg) {
        Node node = tg.exitNode();
        Pattern tilePattern     = Pattern.compile("X([\\d]+)Y([\\d]+)");

        RoutingNode res = new RoutingNode();

        // INT_X45Y109/EE2_E_BEG6
        // TODO: should I use getTile and wire instead of spliting the name?
        String[] int_node = node.toString().split("/");

        Matcher tileMatcher = tilePattern.matcher(int_node[0]);
        if (tileMatcher.find()) {
            res.x = Short.valueOf(tileMatcher.group(1));
            res.y = Short.valueOf(tileMatcher.group(2));
        } else {
            System.out.println("getTermInfo coordinate matching error for node " + node.toString());
        }

        String[] tg_side = int_node[1].split("_");

        // THIS IF MUST BE ABOVE THE IF BELOW (for res.side).
        if (tg_side[0].startsWith("SS") || tg_side[0].startsWith("WW"))
            res.orientation = InterconnectInfo.Orientation.D;
        else if (tg_side[0].startsWith("NN") || tg_side[0].startsWith("EE"))
            res.orientation = InterconnectInfo.Orientation.U;
        else
            res.orientation = InterconnectInfo.Orientation.S;

        // THIS IF MUST BE BELOW THE IF ABOVE (for res.orientation).
        if (tg_side[1].startsWith("E"))
            res.side = InterconnectInfo.TileSide.E;
        else if (tg_side[1].startsWith("W"))
            res.side = InterconnectInfo.TileSide.W;
        else
        if (int_node[1].startsWith("INT")) {
            // Special for internal single such as INT_X0Y0/INT_INT_SDQ_33_INT_OUT1  - NODE_SINGLE
            // Check intendcode is an alternative to above if condition.
            res.side = findTileSideForInternalSingle(tg.entryNode());
            // let res.orientation above set a wrong value and override it because findTileSideForInternalSingle is slow
            res.orientation = (res.side == InterconnectInfo.TileSide.E) ? InterconnectInfo.Orientation.D : InterconnectInfo.Orientation.U;
        } else {
            res.side = InterconnectInfo.TileSide.M;
        }

        return res;
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

    }
}





















||||||| merged common ancestors
=======
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
import java.util.List;
import java.util.Set;


/**
 * A class to represent a list of ImmutableTimingGroups that share the same exit node.
 * This class is immutable because its states never change after construction.
 * There only two way to instantiate object of this class.
 * 1) Call constructor on a SitePinInst.
 * 2) Call getNextSiblingTimingGroups to get a downhill siblings of the given sibling.
 */
public class SiblingsTimingGroup {

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

        // TODO: Can sitePin.getConnectedNode return null ?
//        if (node == null) {
//            String errMsg = String.format("SiblingsTimingGroup ctor found sitePin %s whose node is null.", sitePin.getName());
//            throw new IllegalArgumentException(errMsg);
//        }

        List<ImmutableTimingGroup> siblings    = new ArrayList<>();

        if (sitePin.getPinType() == PinType.IN) {
            // input sitepin has two nodes
            Node nextNextNode = node;
            IntentCode nextNextIc = nextNextNode.getAllWiresInNode()[0].getIntentCode();

            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) {
                IntentCode nextPrvIc = nextPrvNode.getAllWiresInNode()[0].getIntentCode();
                ImmutableTimingGroup newTS = new ImmutableTimingGroup(nextNextNode, nextPrvNode, nextNextIc, nextPrvIc);
                siblings.add(newTS);
            }
        } else {
            // output sitepin has only one node
            ImmutableTimingGroup tg = new ImmutableTimingGroup(node, node.getAllWiresInNode()[0].getIntentCode());
            siblings.add(tg);
        }

        this.siblings = siblings.toArray(new ImmutableTimingGroup[siblings.size()]);
        this.hashCode = this.siblings[0].hashCode();
    }

    /**
     * Find all downhill timing groups of the current timing group.
     * @return a list of list of timing groups representing a list of siblings -- timing groups sharing the same last nodes,
     * instead of an array of timing groups, returned by getNextTimingGroups().
     */
    public List<SiblingsTimingGroup> getNextSiblingTimingGroups(Set<Node> reservedNodes) {
        List<SiblingsTimingGroup> result = new ArrayList<>();
        Node prevNode = siblings[0].exitNode();

        // I don't see pip is used in computeTypes or delay calculation. Thus, I don't populate it.
        for (Node nextNode : prevNode.getAllDownhillNodes()) {
            if (!reservedNodes.contains(nextNode)) {
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
                if (nextNodeHasGlobalWire || ic == IntentCode.NODE_CLE_OUTPUT ||
                        ic == IntentCode.NODE_HLONG || ic == IntentCode.NODE_VLONG) {
                    ImmutableTimingGroup newTS = new ImmutableTimingGroup(nextNode, ic);
                    result.add(new SiblingsTimingGroup(new ArrayList<ImmutableTimingGroup>(){{ add(newTS); }}));
                } else {
                    // for other TGs look for the 2nd node
                    for (Node nextNextNode : nextNode.getAllDownhillNodes()) {
                        if (!reservedNodes.contains(nextNextNode)) {
                            IntentCode nextNextIc = nextNextNode.getAllWiresInNode()[0].getIntentCode();

                            List<ImmutableTimingGroup> tgs = new ArrayList<>();
                            for (Node nextPrvNode : nextNextNode.getAllUphillNodes()) { // need to get all downhill PIPs
                                // TODO: Currently the whole sibling is considered together as a whole.
                                // TODO: (need to revisit this if the assumption changes.)
                                // TODO: Thus only check nodes that can be key nodes. nextPrvNode is not a key node.
                                IntentCode nextPrvIc       = nextPrvNode.getAllWiresInNode()[0].getIntentCode();
                                ImmutableTimingGroup newTS = new ImmutableTimingGroup(nextNextNode, nextPrvNode, nextNextIc, nextPrvIc);
                                tgs.add(newTS);
                            }
                            result.add(new SiblingsTimingGroup(tgs));
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
//        builder.append(siblings[0].getLastNode().getAllWiresInNode()[0].getWireName());
//        builder.append("  : ");
//
//        if (siblings.get(0).getNodes().size() > 1) {
//            for (TimingGroup tg : siblings) {
//                builder.append(' ');
//                builder.append(tg.getNode(0).getAllWiresInNode()[0].getWireName());
//            }
//        }
        return builder.toString();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }


    // ------------------------------------   private ----------------------------------------


    final private ImmutableTimingGroup[] siblings;
    final private int                    hashCode;


    private SiblingsTimingGroup (List<ImmutableTimingGroup> tgs) {
        this.siblings = tgs.toArray(new ImmutableTimingGroup[tgs.size()]);
        this.hashCode = this.siblings[0].hashCode();
    }
}





















>>>>>>> f98c11b1bc9e93bd34eed5fda7ac149be524089a
