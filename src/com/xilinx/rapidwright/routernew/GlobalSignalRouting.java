package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.placer.blockplacer.SmallestEnclosingCircle;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;

/**
 * A collection of methods for routing global signals, i.e. GLOBAL_CLOCK, VCC and GND
 * Adapted from RW APIs
 */
public class GlobalSignalRouting {
	private static Map<Node, RoutableNode> rnodesCreated;
	private static int rnodeId;
	private static Design design;
	private static RouteThruHelper routeThruHelper;
	
	static boolean clkDebug = false;
	static boolean debugPrintClkPIPs = false;
	static ClockRegion assignedCentroid = null;
	
	public GlobalSignalRouting(Design design, Map<Node, RoutableNode> rnodesCreated, int rnodeId, RouteThruHelper routeThruHelper) {
		setRnodesCreated(rnodesCreated);
		setRnodeId(rnodeId);
		GlobalSignalRouting.design = design;
		GlobalSignalRouting.routeThruHelper = routeThruHelper;
	}
	
	public static Map<Node, RoutableNode> getRnodesCreated() {
		return rnodesCreated;
	}

	public static void setRnodesCreated(Map<Node, RoutableNode> rnodesCreated) {
		GlobalSignalRouting.rnodesCreated = rnodesCreated;
	}

	public static int getRnodeId() {
		return rnodeId;
	}
	public static void setRnodeId(int rnodeId) {
		GlobalSignalRouting.rnodeId = rnodeId;
	}
	
	/**
	 *  GLOBAL_CLOCK routing
	 * @param clk: GLBAL_CLOCK net
	 * @param dev: the device that the design uses
	 */
	public static void setDebug() {
		clkDebug = true;
	}
	public static void setPrintCLKPIPs() {
		debugPrintClkPIPs = true;
	}
	public static void setCentroid(ClockRegion cr) {
		assignedCentroid = cr;
	}
	
	public static void clkRouting(Net clk, Device dev) {
		
 		if(clkDebug) System.out.println("\nROUTE CLK NET...");
 		
		List<ClockRegion> clockRegions = new ArrayList<>();
		for(SitePinInst pin : clk.getPins()) {
			if(pin.isOutPin()) continue;
			Tile t = pin.getTile();
			ClockRegion cr = t.getClockRegion();
			if(!clockRegions.contains(cr)) clockRegions.add(cr);
		}
		if(clkDebug) System.out.println("clock regions " + clockRegions);
		
		ClockRegion centroid;
		if (assignedCentroid != null) centroid = assignedCentroid;
		else centroid = findCentroid(clk, dev);
		if(clkDebug) System.out.println(" centroid clock region is  \n \t" + centroid);
		
		//Route from BUFG to Clock Routing Tracks
		//using RouteNode would be better than rewriting the methods and chaning from RouteNode to RoutableNode
		RouteNode clkRoutingLine = UltraScaleClockRouting.routeBUFGToNearestRoutingTrack(clk);//HROUTE
		if(clkDebug) System.out.println("route BUFG to nearest routing track: \n \t" + clkRoutingLine);
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		if(clkDebug) System.out.println("route To Centroid ");
		RouteNode centroidRouteNode = UltraScaleClockRouting.routeToCentroid(clk, clkRoutingLine, centroid);//VROUTE
		if(clkDebug) System.out.println(" clk centroid route node is \n \t" + centroidRouteNode);
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		// Transition centroid from routing track to vertical distribution track
		if(clkDebug) System.out.println("transition Centroid To Distribution Line");
		RouteNode centroidDistNode = UltraScaleClockRouting.transitionCentroidToDistributionLine(clk,centroidRouteNode);
		if(clkDebug) System.out.println(" centroid distribution node is \n \t" + centroidDistNode);
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		//routeCentroidToVerticalDistributionLines and routeCentroidToHorizontalDistributionLines could result in duplicated PIPs
		if(clkDebug) System.out.println("route Centroid To Vertical Distribution Lines");
		
		//Each ClockRegion is not necessarily the one that each RouteNode value belongs to (same row is a must)
		Map<ClockRegion, RouteNode> vertDistLines = UltraScaleClockRouting.routeCentroidToVerticalDistributionLines(clk,centroidDistNode, clockRegions);
		if(clkDebug) {
			System.out.println(" clock region - vertical distribution node ");
			for(ClockRegion cr : vertDistLines.keySet()) System.out.println(" \t" + cr + " \t " + vertDistLines.get(cr));
		}
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		if(clkDebug) System.out.println("route Centroid To Horizontal Distribution Lines");
		List<RouteNode> distLines = new ArrayList<>();
		distLines.addAll(UltraScaleClockRouting.routeCentroidToHorizontalDistributionLines(clk, centroidDistNode, vertDistLines));
		if(clkDebug) System.out.println(" dist lines are \n \t" + distLines);
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		//I changed this method to just map connected node to SitePinInsts
		if(clkDebug) System.out.println("get LCB Pin mappings");
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = getLCBPinMappings(clk);
		
		// Route from clock distribution to all leaf clock buffers
		if(clkDebug) System.out.println("route distribution to LCBs");
		UltraScaleClockRouting.routeDistributionToLCBs(clk, distLines, lcbMappings.keySet());		
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		// Route from each LCB to sinks
		if(clkDebug) System.out.println("route LCBs to sinks");
		UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings);
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		Set<PIP> clkPIPsWithoutDuplication = new HashSet<>();
		clkPIPsWithoutDuplication.addAll(clk.getPIPs());
		clk.getPIPs().clear();
		clk.setPIPs(clkPIPsWithoutDuplication);
		
		if(debugPrintClkPIPs) {
			System.out.println("Final CLK routing");
			printCLKPIPs(clk);
		}
		List<Site> sites = new ArrayList<>();
		for(PIP p:clkPIPsWithoutDuplication) {
			if(p.getEndNode().getIntentCode() == IntentCode.NODE_GLOBAL_LEAF) {
				Site s = p.getEndNode().getSitePin().getSite();
				if(!sites.contains(s)) sites.add(s);
			}
		}
		for(Site site : sites) {
			int clockRegionHeightToCentroid = getClockRegionSpanHeight(site, centroid);
			int delay = 0;
			if(clockRegionHeightToCentroid == 0) {
				delay = 8;
			}else if(clockRegionHeightToCentroid == 1) {
				delay = 4;
			}else if(clockRegionHeightToCentroid == 2) {
				delay = 2;
			}else {
				delay = 1;
			}
			clk.setBufferDelay(site, delay);//delay 0 by default, 0,1,2,4,8
		}
	}
	
	public static int getClockRegionSpanHeight(Site bufceRowSite, ClockRegion centroid) {
		ClockRegion bufceCR = bufceRowSite.getTile().getClockRegion();
		return Math.abs(bufceCR.getInstanceY() - centroid.getInstanceY());	
	}
	
	public static Map<RouteNode, ArrayList<SitePinInst>> getLCBPinMappings(Net clk){
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = new HashMap<>();
		for(SitePinInst p : clk.getPins()){
			if(p.isOutPin()) continue;
			Node n = null;//n should be a node whose name ends with "CLK_LEAF"
			//tile = p.getSite.getINTtile() wireIndex = getWire(wireName)
			//wire name GCLK_B_0_...
			// RoutableNodes can have multiple mapped sitePinInsts
			for(Node prev : p.getConnectedNode().getAllUphillNodes()) {
				if(prev.getTile().equals(p.getSite().getIntTile())) {
					for(Node prevPrev : prev.getAllUphillNodes()) {
						if(prevPrev.getIntentCode() == IntentCode.NODE_GLOBAL_LEAF) {
							n = prevPrev;
							break;
						}
					}
				}
			}
			
			RouteNode rn = n != null? new RouteNode(n.getTile(), n.getWire()):null;
			if(rn == null) throw new RuntimeException("ERROR: No mapped LCB to SitePinInst " + p);
			ArrayList<SitePinInst> sinks = lcbMappings.get(rn);
			if(sinks == null){
				sinks = new ArrayList<>();
				lcbMappings.put(rn, sinks);
			}
			sinks.add(p);
			
		}
		
		return lcbMappings;
	}
	
	public static void printCLKPIPs(Net clk) {
		System.out.println(" \t  used pips");
		for(PIP pip : clk.getPIPs()) {
			System.out.println(" \t  " + pip.getStartNode() + "  ->  " + pip.getEndNode());
		}
		System.out.println();
	}
	
	//adapted from RW API
	public static ClockRegion findCentroid(Net clk, Device dev) {
		HashSet<Point> sitePinInstTilePoints = new HashSet<>();
		
		for(SitePinInst spi : clk.getPins()) {
			if(spi.isOutPin()) continue;
			Tile t = spi.getSite().getTile();
			sitePinInstTilePoints.add(new Point(t.getColumn(),t.getRow()));
		}
		
		Point center = SmallestEnclosingCircle.getCenterPoint(sitePinInstTilePoints);
		Tile c = dev.getTile(center.y, center.x);
		int i=1;
		int dir = -1;
		int count = 0;
		// Some tiles don't belong to a clock region, we need to wiggle around 
		// until we find one that is
		while(c.getClockRegion() == null){
			int neighborOffset = (count % 2 == 0) ? dir*i : i; 
			c = c.getTileNeighbor(neighborOffset, 0);
			count++;
			if(count % 2 == 0) i++;
		}
		return c.getClockRegion();
	}
	
	/**
	 * static net (GND and VCC) routing
	 * @param currNet
	 * @param unavailableNodes
	 * @param routethruHelper
	 */
	private static HashSet<String> lutOutputPinNames;
	static {
		lutOutputPinNames = new HashSet<String>();
		for(String cle : new String[]{"L", "M"}){
			for(String pin : new String[]{"A", "B", "C", "D", "E", "F", "G", "H"}){
				lutOutputPinNames.add("CLE_CLE_"+cle+"_SITE_0_"+pin+"_O");
			}
		}
	}
	
	public static Map<SitePinInst, List<Node>> routeStaticNet(Net currNet, Set<Node> unavailableNodes){
		NetType netType = currNet.getType();
		
		Set<PIP> netPIPs = new HashSet<>();
		Map<SitePinInst, List<Node>> sinkPathNodes = new HashMap<>();
		Queue<RoutableNode> q = new LinkedList<>();
		Set<RoutableNode> visitedRoutable = new HashSet<>();
		Set<RoutableNode> usedRoutable = new HashSet<>();
		
		boolean debug = false;
		if(debug) {
			System.out.println("Net: " + currNet.getName());
		}
		
		for(SitePinInst sink : currNet.getPins()) {
			if(sink.isOutPin()) continue;
			int watchdog = 10000;
			int wire = sink.getSiteInst().getSite().getTileWireIndexFromPinName(sink.getName());
			
			if(wire == -1) {
				throw new RuntimeException("ERROR: Problem while trying to route static sink " + sink);
			}
			Tile t = sink.getTile();
			if(debug) {
				System.out.println("SINK: TILE = " + t.getName() + " WIRE NAME = " + t.getWireName(wire) + " NODE = " + sink.getConnectedNode().toString());
			}
			
			q.clear();
			visitedRoutable.clear();
			List<Node> pathNodes = new ArrayList<>();
			
			Node node = new Node(t,wire); // same as sink.getConnectedNode()
			if(debug) 
				System.out.println(node);
			RoutableNode sinkRNode = createRoutableNodeAndAdd(rnodeId, node, RoutableType.SINKRR, currNet);
			sinkRNode.type = RoutableType.SINKRR;
			sinkRNode.getRoutableData().setPrev(null);
			
			q.add(sinkRNode);
			boolean success = false;
			while(!q.isEmpty()){
				RoutableNode n = q.poll();
				visitedRoutable.add(n);
				
				if(debug) System.out.println("DEQUEUE:" + n);
				if(debug) System.out.println(", PREV = " + n.getRoutableData().getPrev() == null ? " null" : n.getRoutableData().getPrev());
				
				if(success = isThisOurStaticSource(design, n, netType, usedRoutable, debug)){
					n.type = RoutableType.SOURCERR;//set as a source
					//trace back for a complete path
					if(debug){
						System.out.println("SINK: TILE = " + t.getName() + " WIRE NAME = " + t.getWireName(wire) + " NODE = " + sink.getConnectedNode().toString());
						System.out.println("SOURCE " + n.toString() + " found");
					}
					
					while(n != null){
						usedRoutable.add(n);// use routed RNodes as the source
						pathNodes.add(n.getNode());
						
						if(debug) System.out.println("  " + n.toString());
						n = (RoutableNode) n.getRoutableData().getPrev();
					}
					Collections.reverse(pathNodes);
					sinkPathNodes.put(sink, pathNodes);
					
					if(debug){
						for(Node pathNode:pathNodes){
							System.out.println(pathNode.toString());
						}
					}
					break;
				}
				
				if(debug){
					System.out.println("KEEP LOOKING FOR A SOURCE...");
				}
				for(Node uphillNode : n.getNode().getAllUphillNodes()){
					if(routeThruHelper.isRouteThru(uphillNode, n.getNode())) continue;
					RoutableNode nParent = createRoutableNodeAndAdd(rnodeId, uphillNode, RoutableType.INTERRR, currNet);
					
					if(!pruneNode(nParent, unavailableNodes, visitedRoutable)) {
						nParent.getRoutableData().setPrev(n);
						q.add(nParent);
					}
				}
				watchdog--;
				if(watchdog < 0) {
					break;
				}
			}
			if(!success){
				System.out.println("FAILED to route " + currNet.getName() + " pin " + sink.toString());
			}else{
				sink.setRouted(true);
			}
		}
		
		for(List<Node> nodes:sinkPathNodes.values()){
			netPIPs.addAll(RouterHelper.conPIPs(nodes));
		}
		
		currNet.setPIPs(netPIPs);
		return sinkPathNodes;
	}
	
	public static RoutableNode createRoutableNodeAndAdd(int globalIndex, Node node, RoutableType type, Net net){
		NetType netType = net.getType();
		RoutableNode rrgNode = rnodesCreated.get(node);
		
		if(!rnodesCreated.containsKey(node)){//TODO reserved rnodes.contains()
			//this is for initializing sources and sinks of those to-be-routed nets's connections
			rrgNode = new RoutableNode(globalIndex, node, type);
			rnodesCreated.put(rrgNode.getNode(), rrgNode);
			rnodeId++;
		}else{
			//this is for checking preserved routing resource conflicts among routed nets
			rrgNode = rnodesCreated.get(node);
			if(rrgNode.type == type && type == RoutableType.SINKRR && netType == NetType.WIRE)
				System.out.println("! Conflicting Sink Site Pin Connected Node: " + node);
		}
		
		return rrgNode;
	}
	
	private static boolean pruneNode(RoutableNode parent, Set<Node> unavailableNodes, Set<RoutableNode> visitedRoutable){
		Node n = parent.getNode();
		IntentCode ic = n.getTile().getWireIntentCode(n.getWire());
		switch(ic){
			case NODE_GLOBAL_VDISTR:
			case NODE_GLOBAL_HROUTE:
			case NODE_GLOBAL_HDISTR:
			case NODE_HLONG:
			case NODE_VLONG:
			case NODE_GLOBAL_VROUTE:
			case NODE_GLOBAL_LEAF:
			case NODE_GLOBAL_BUFG:
				return true;
			default:
		}
		if(unavailableNodes.contains(n)) return true;
		if(visitedRoutable.contains(parent)) return true;
		return false;
	}
	
	/**
	 * Determines if the given node can serve as our sink.
	 * @param rnode RoutableNode in question
	 * @param type The net type to designate the static source type
	 * @return true if this sources is usable, false otherwise. 
	 */
	private static boolean isThisOurStaticSource(Design design, RoutableNode rnode, NetType type, Set<RoutableNode> usedRoutable, boolean debug){
		if(type == NetType.VCC){
			return isNodeUsableStaticSource(rnode, type, design);
		}else{
			if(usedRoutable != null && usedRoutable.contains(rnode))
				return true;
			return isNodeUsableStaticSource(rnode, type, design);
		}
	}
	
	/**
	 * This method handles queries during the static source routing process. 
	 * It determines if the node in question can be used as a source for the current
	 * NetType.
	 * @param rnode The RoutableNode in question
	 * @param type The NetType to indicate what kind of static source we need (GND/VCC)
	 * @return True if the pin is a hard source or an unused LUT output that can be repurposed as a source
	 */
	private static boolean isNodeUsableStaticSource(RoutableNode rnode, NetType type, Design design){
		// We should look for 3 different potential sources
		// before we stop:
		// (1) GND_WIRE 
		// (2) VCC_WIRE 
		// (3) Unused LUT Outputs (A_0, B_0,...,H_0)
		String pinName = type == NetType.VCC ? Net.VCC_WIRE_NAME : Net.GND_WIRE_NAME;
		Node n = rnode.getNode();
		if(n.getWireName().startsWith(pinName)){
			return true;
		}else if(lutOutputPinNames.contains(n.getWireName())){
			Site slice = n.getTile().getSites()[0];
			SiteInst i = design.getSiteInstFromSite(slice);			
			if(i == null) return true; // Site is not used
			char uniqueId = n.getWireName().charAt(n.getWireName().length()-3);
			Net currNet = i.getNetFromSiteWire(uniqueId + "_O");
			if(currNet == null) return true;
			if(currNet.getType() == type) return true;
			return false;
		}
		return false;
	}
	
}
