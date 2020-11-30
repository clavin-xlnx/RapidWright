package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.device.e;
import com.xilinx.rapidwright.tests.CodePerfTracker;

import com.xilinx.rapidwright.util.Pair;

import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.RouteThruHelper;

public class RoutableNodeRouter{
	public Design design;
	public String dcpFileName;
	public int nrOfTrials;
	public CodePerfTracker t;
	
	public List<Netplus> nets;
	public List<Connection> connections;
	public int fanout1Net;
	public int inetToBeRouted;
	public int iconToBeRouted;
	public int iclockAndStaticNet;
	public int iWirePinsUnknown;
	public int iWireOneTypePin;
	public int iUnknownTypeNet;
	
//	public Map<Net, List<Node>> netsReservedNodes;
	public PriorityQueue<QueueElement> queue;
	public Collection<RoutableData> rnodesTouched;
	public Map<Node, RoutableNode> rnodesCreated;//node and rnode pair
	public Set<Node> reservedNodes;
	
	private static HashSet<String> lutOutputPinNames;
	
	public List<Connection> sortedListOfConnection;
	public List<Netplus> sortedListOfNetplus;
	public Map<Net, List<RoutableNode>> staticNetAndSinkRoutables;

	public RouteThruHelper routethruHelper;
	
	public RouterTimer routerTimer;
	public long iterationStart;
	public long iterationEnd;
	
	public int itry;
	public float pres_fac;
	public float initial_pres_fac; 
	public float pres_fac_mult; 
	public float acc_fac;
	public float base_cost_fac;
	
	public float mdWeight;
	public float hopWeight;
	public float averFanoutRNodes;
	
	public int rrgNodeId;
	public int firstIterRNodes;
	public float firstRouting;
	
	public int connectionsRouted;
	public long nodesExpanded;
	public long nodesExpandedFirstIter;
	public int connectionsRoutedIteration;
	public long nodesPopedFromQueue;
	public long nodesPopedFromQueueFirstIter;
	public long callingOfGetNextRoutable;
	
	public Set<Integer> overUsedRNodes;
	public Set<Integer> usedRNodes;
	public Set<Integer> illegalRNodes;//nodes that have multiple drivers in a net
	
	public long hops;
	public float manhattanD;
	
	public boolean partialRouting;
	
	public boolean trial = false;
	public boolean debugRoutingCon = false;
	public boolean debugExpansion = false;
	boolean printNodeInfo = false;
	public float firtRnodeT;
	
	static {
		lutOutputPinNames = new HashSet<String>();
		for(String cle : new String[]{"L", "M"}){
			for(String pin : new String[]{"A", "B", "C", "D", "E", "F", "G", "H"}){
				lutOutputPinNames.add("CLE_CLE_"+cle+"_SITE_0_"+pin+"_O");
			}
		}
	}
	
	public RoutableNodeRouter(Design design,
			String dcpFileName,
			int nrOfTrials,
			CodePerfTracker t,
			short bbRange,
			float mdWeight,
			float hopWeight,
			float initial_pres_fac, 
			float pres_fac_mult, 
			float acc_fac,
			float base_cost_fac,
			boolean partialRouting){
		this.design = design;
		
//		this.design.unrouteDesign();//global signal routing needed for a complete routing flow
//		DesignTools.unrouteDualOutputSitePinRouting(this.design);//this is for removing the unmatched SitePinInst - TimingVertex issue for TimingGraph 
		DesignTools.createMissingSitePinInsts(this.design);
		
		this.queue = new PriorityQueue<>(Comparators.PRIORITY_COMPARATOR);
		this.rnodesTouched = new ArrayList<>();
		this.reservedNodes = new HashSet<>();
		this.rnodesCreated = new HashMap<>();
		this.dcpFileName = dcpFileName;
		this.nrOfTrials = nrOfTrials;
		this.t = t;
		
		this.initial_pres_fac = initial_pres_fac;
		this.pres_fac_mult = pres_fac_mult;
		this.acc_fac = acc_fac;
		this.base_cost_fac = base_cost_fac;
		this.mdWeight = mdWeight;
		this.hopWeight = hopWeight;
		
		this.partialRouting = partialRouting;
		
		this.routerTimer = new RouterTimer();
		this.fanout1Net = 0;
		this.rrgNodeId = 0;
		this.rrgNodeId = this.initializeNetsCons(bbRange, this.base_cost_fac);
				
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfNetplus = new ArrayList<>();
		
		this.routethruHelper = new RouteThruHelper(this.design.getDevice());
//		this.routerHelper = new RouterHelper();
		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesExpanded = 0;
		this.nodesExpandedFirstIter = 0;
		this.nodesPopedFromQueue = 0;
		this.nodesPopedFromQueueFirstIter = 0;
		this.callingOfGetNextRoutable = 0;
		
		this.usedRNodes = new HashSet<>();
		this.overUsedRNodes = new HashSet<>();
		this.illegalRNodes = new HashSet<>();
	}
	
	public int initializeNetsCons(short bbRange, float base_cost_fac){
		this.inetToBeRouted = 0;
		this.iconToBeRouted = 0;
		this.iclockAndStaticNet = 0;
		this.iWirePinsUnknown = 0;
		this.iWireOneTypePin = 0;
		this.iUnknownTypeNet = 0;
		
		this.nets = new ArrayList<>();
		this.connections = new ArrayList<>();
		
		this.staticNetAndSinkRoutables = new HashMap<>();
		
		for(Net n:this.design.getNets()){
			
			if(n.isClockNet()){
				this.reserveNet(n);
				this.iclockAndStaticNet++;
				
			}else if(n.isStaticNet()){
				n.unroute();
				this.iclockAndStaticNet++;
				this.initializeStaticNets(n);
				
			}else if (n.getType().equals(NetType.WIRE)){
				/*if(n.getName().equals("S_CONTROL_USS/tile1_3_control_S_AXI_WDATA[26]") || 
				   n.getName().equals("S_CONTROL_USS/tile2_2_control_S_AXI_WDATA[24]")){
					System.out.println(n.toStringFull());
					continue;//net with a null source SitePinInst
				}*/
				if(RouterHelper.isRegularNetToBeRouted(n)){
					if(!this.partialRouting){
						//four nets with BYPASS_W8
						/*if(n.getName().equals("tile2_2/rxByteCount_reg[4]") ||
								n.getName().equals("S_CONTROL_USS/tile3_2_control_S_AXI_WDATA[19]") ||
								n.getName().equals("S_CONTROL_USS/tile1_2_control_S_AXI_ARADDR[4]") ||
								n.getName().equals("S_CONTROL_USS/tile3_3_control_S_AXI_ARADDR[4]")){
							System.out.println(n.toStringFull());
							this.reserveNet(n);
							continue;
						}*/
						n.unroute();
						this.initializeNetAndCons(n, bbRange);
						/*if(RouteStatusHelper.isNetWithUnroutedPins(n)){
							String sourceSize;
							if(n.getSource() != null) sourceSize = "1";
							else sourceSize = "null";
							System.out.println("NetWithUnroutedPins " + n.toStringFull() + " falls into TO_BE_ROUTED, sinks = " + n.getSinkPins().size() + ", source = " + sourceSize);
						}*/
					}else{
//						if(n.toString().equals("opr[54]") || n.toString().equals("n1a4") || n.toString().equals("n1a2")){
//							n.unroute();//for creating partially routed dcps
//							continue;
//						}else{
//							this.reserveNet(n);
//						}
						if(n.hasPIPs()){
							this.reservePipsOfNet(n);
						}else{
							this.initializeNetAndCons(n, bbRange);
						}	
					}
					
				}else if(RouterHelper.isOneTypePinNet(n)){
					this.reserveNet(n);
					this.iWireOneTypePin++;
					/*if(RouteStatusHelper.isUnroutedRoutableNet(n)){
						System.out.println("Unrouted " + n.toStringFull() + " fallS into ONE_TYPE_PIN");
					}
					if(RouteStatusHelper.isNoLoadsNet(n)){
						System.out.println("No loads " + n.toStringFull() + " fallS into ONE_TYPE_PIN");
					}
					if(RouteStatusHelper.isNetWithUnroutedPins(n)){
						String sourceSize;
						if(n.getSource() != null) sourceSize = "1";
						else sourceSize = "null";
						System.out.println("NetWithUnroutedPins " + n.toStringFull() + " falls into ONE_TYPE_PIN, sinks = " + n.getSinkPins().size() + ", source = " + sourceSize);
					}*/
					
				}else if(RouterHelper.isNoPinNets(n)){
					this.reserveNet(n);
					this.iWirePinsUnknown++;
//					System.out.println(n.toStringFull());
					/*if(RouteStatusHelper.isUnroutedRoutableNet(n)){
						System.out.println("Unrouted " + n.toStringFull() + " fallS into UNKOWN_PINS");
					}
					if(RouteStatusHelper.isNoLoadsNet(n)){
						System.out.println("No loads " + n.toStringFull() + " fallS into UNKOWN_PINs");
					}
					if(RouteStatusHelper.isNetWithUnroutedPins(n)){
						String sourceSize;
						if(n.getSource() != null) sourceSize = "1";
						else sourceSize = "null";
						System.out.println("NetWithUnroutedPins " + n.toStringFull() + " falls into UNKOWN_PINs, sinks = " + n.getSinkPins().size() + ", source = " + sourceSize);
					}*/
				}
			}else{
				this.iUnknownTypeNet++;
				System.err.println("UNKNOWN type net: " + n.toString());
			}
		}
		
//		this.checkDesignWideInputPinFeed();
		
		return rrgNodeId;
	}
	
	public void initializeStaticNets(Net currNet){
		List<RoutableNode> sinkrns = new ArrayList<>();
		for(SitePinInst sink : currNet.getPins()){
			if(sink.isOutPin()) continue;
			sinkrns.add(this.createRoutableNodeAndAdd(this.rrgNodeId, sink.getConnectedNode(), RoutableType.SINKRR, this.base_cost_fac, currNet.getType()));	
		}
		this.staticNetAndSinkRoutables.put(currNet, sinkrns);
	}
	
	public void routeStaticNets(){
		for(Net n:this.staticNetAndSinkRoutables.keySet()){
			this.routeStaticNet(n);
		}
	}
	
	//TODO VCC GND routing
	public void routeStaticNet(Net currNet){
		NetType netType = currNet.getType();
		
		Set<Node> unavailableNodes = this.getAllUsedNodesOfRoutedNets();
		unavailableNodes.addAll(this.reservedNodes);
		
		Set<PIP> netPIPs = new HashSet<>();
		Map<SitePinInst, List<Node>> sinkPathNodes = new HashMap<>();
		Queue<RoutableNode> q = new LinkedList<>();
		Set<RoutableNode> visitedRoutable = new HashSet<>();
		Set<RoutableNode> usedRoutable = new HashSet<>();
		
		boolean debug = false;
		if(debug) {
			System.out.println("Net: " + currNet.getName());
		}
		
		for(SitePinInst sink : currNet.getPins()){
			
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
			RoutableNode sinkRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, node, RoutableType.SINKRR, this.base_cost_fac, currNet.getType());
			sinkRNode.type = RoutableType.SINKRR;
			sinkRNode.rnodeData.setPrev(null);
			
			q.add(sinkRNode);
			boolean success = false;
			while(!q.isEmpty()){
				RoutableNode n = q.poll();
				visitedRoutable.add(n);
				
				if(debug) System.out.println("DEQUEUE:" + n);
				if(debug) System.out.println(", PREV = " + n.rnodeData.getPrev() == null ? " null" : n.rnodeData.getPrev());
				
				if(success = this.isThisOurStaticSource(n, netType, usedRoutable, debug)){
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
						n = (RoutableNode) n.rnodeData.getPrev();
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
					if(this.routethruHelper.isRouteThru(uphillNode, n.getNode())) continue;
					RoutableNode nParent = this.createRoutableNodeAndAdd(this.rrgNodeId, uphillNode, RoutableType.INTERRR, this.base_cost_fac, currNet.getType());
					
					if(!this.pruneNode(nParent, unavailableNodes, visitedRoutable)) {
						nParent.rnodeData.setPrev(n);
						q.add(nParent);
					}
				}
				watchdog--;
				if(watchdog < 0) {
					break;
				}
			}
			if(!success){
				System.out.println("FAILED to route " + netType + " pin " + sink.toString());
			}else{
				sink.setRouted(true);
			}
		}
		
		for(List<Node> nodes:sinkPathNodes.values()){
			netPIPs.addAll(RouterHelper.conPIPs(nodes));
		}
		
		currNet.setPIPs(netPIPs);
	}
	
	private boolean pruneNode(RoutableNode parent, Set<Node> unavailableNodes, Set<RoutableNode> visitedRoutable){
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
	private boolean isThisOurStaticSource(RoutableNode rnode, NetType type, Set<RoutableNode> usedRoutable, boolean debug){
		if(type == NetType.VCC){
			return this.isNodeUsableStaticSource(rnode, type);
		}else{
			if(usedRoutable != null && usedRoutable.contains(rnode))
				return true;
			return this.isNodeUsableStaticSource(rnode, type);// || usedRoutable == null? false : usedRoutable.contains(rnode));
		}
	}
	
	/**
	 * This method handles queries during the static source routing process. 
	 * It determines if the node in question can be used as a source for the current
	 * NetType.
	 * @param rnode The node in question
	 * @param type The NetType to indicate what kind of static source we need (GND/VCC)
	 * @return True if the pin is a hard source or an unused LUT output that can be repurposed as a source
	 */
	private boolean isNodeUsableStaticSource(RoutableNode rnode, NetType type){
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
	
	public Set<Node> getAllUsedNodesOfRoutedNets(){
		Set<Node> nodes = new HashSet<>();
		for(Connection c:this.sortedListOfConnection){
			if(c.nodes != null) nodes.addAll(c.nodes);
		}	
		return nodes;
	}
	
	public void reserveNet(Net n){
		if(n.hasPIPs()){
			this.reservePipsOfNet(n);
		}else{
			this.reserveConnectedNodesOfNetPins(n);
		}
	}
	
	public void initializeNetAndCons(Net n, short bbRange){
		
		Netplus np = new Netplus(inetToBeRouted, bbRange, n);
		this.nets.add(np);
		this.inetToBeRouted++;
		
		SitePinInst source = n.getSource();
		RoutableNode sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source.getConnectedNode(), RoutableType.SOURCERR, this.base_cost_fac, n.getType());
		
		for(SitePinInst sink:n.getSinkPins()){
			
			if(RouterHelper.isExternalConnectionToCout(source, sink)){
				source = n.getAlternateSource();
				if(source == null){
					String errMsg = "net alterNative source is null: " + n.toStringFull();
					throw new IllegalArgumentException(errMsg);
				}
				
				sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source.getConnectedNode(), RoutableType.SOURCERR, this.base_cost_fac, n.getType());		
			}
			
			Connection c = new Connection(this.iconToBeRouted, source, sink);	
			c.setSourceRNode(sourceRNode);
			
			//create RNode of the sink pin external wire up front 
			if(sink.getConnectedNode().toString().equals("INT_X17Y542/BYPASS_W8") || sink.getConnectedNode().toString().equals("INT_X14Y512/BYPASS_W8")){
				System.out.println("target node connected to " + sink.toString() + ", " + n.toStringFull());
			}
			RoutableNode sinkRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, sink.getConnectedNode(), RoutableType.SINKRR, this.base_cost_fac, n.getType());
			
			c.setSinkRNode(sinkRNode);
			
			this.connections.add(c);
			c.setNet(np);
			np.addCons(c);
			this.iconToBeRouted++;
		}
		if(n.getSinkPins().size() == 1)
			this.fanout1Net++;
	}
	
	public void reservePipsOfNet(Net n){
		for(PIP pip:n.getPIPs()){
			Node nodeStart = pip.getStartNode();
			this.createRoutableNodeAndAdd(this.rrgNodeId, nodeStart, RoutableType.RESERVED, Float.MAX_VALUE - 1, n.getType());
			
			Node nodeEnd = pip.getEndNode();
			this.createRoutableNodeAndAdd(this.rrgNodeId, nodeEnd, RoutableType.RESERVED, Float.MAX_VALUE - 1, n.getType());
		
			this.reservedNodes.add(nodeStart);
			this.reservedNodes.add(nodeEnd);
		}
	}
	
	public void reserveConnectedNodesOfNetPins(Net n){
		for(SitePinInst pin:n.getPins()){
			Node node = pin.getConnectedNode();
			this.createRoutableNodeAndAdd(this.rrgNodeId, node, RoutableType.RESERVED, Float.MAX_VALUE - 1, n.getType());			
			
			this.reservedNodes.add(node);
		}
	}
	
	public RoutableNode createRoutableNodeAndAdd(int globalIndex, Node node, RoutableType type, float base_cost_fac, NetType netType){
		RoutableNode rrgNode;
		if(!this.rnodesCreated.containsKey(node)){
			rrgNode = new RoutableNode(globalIndex, node, type);
			rrgNode.setBaseCost(base_cost_fac);
			this.rnodesCreated.put(rrgNode.getNode(), rrgNode);
			this.rrgNodeId++;
		}else{
			rrgNode = this.rnodesCreated.get(node);
			if(rrgNode.type == type && type == RoutableType.SINKRR && netType == NetType.WIRE) System.err.println("routing resource conflicts: sink site pin");
		}
		
		return rrgNode;
	}
	
	public void setBaseCostAndAdd(RoutableNode rrgNode, float base_cost_fac){
		rrgNode.setBaseCost(base_cost_fac);
		this.rnodesCreated.put(rrgNode.getNode(), rrgNode);
		this.rrgNodeId++;
	}
	//TODO having this list of node, the target RoutableNode of a connection will change, not the connected node to the sink pin
	public List<Routable> findInputPinFeed(Connection con){
		Site site = con.sink.getSite();
		String pinName = con.sink.getName();
		Tile tile = site.getTile();
		int wire = site.getTileWireIndexFromPinName(pinName);
		
		Node node = new Node(tile,wire);
		System.out.println(node.toString() + " " + con.sink.getConnectedNode().toString());
		return this.findInputPinFeed(node, con.getNet().getType());
	}
	
	public List<Routable> findINTNodeOfOutputPin(){
		//for CLBs, it is true that there is only one possible connected tile
		List<Routable> rns = new ArrayList<>();
		for(Netplus netp:this.sortedListOfNetplus){
			
			rns = this.findOutoutPinINTNode(netp.getNet().getSource(), netp.getNet());
			System.out.println(netp.getNet().toString() + ", " + rns.size());
			for(Routable rn:rns){
				System.out.println(rn.toString());
			}
			System.out.println();
		}
		return rns;
	}
	
	public List<Routable> findOutoutPinINTNode(SitePinInst source, Net n){
		List<Routable> partialPath = new ArrayList<>();
		Node sourceNode = source.getConnectedNode();
		RoutableNode rnode = this.createRoutableNodeAndAdd(this.rrgNodeId, sourceNode, RoutableType.SOURCERR, this.base_cost_fac, n.getType());
		rnode.rnodeData.setPrev(null);
		Queue<RoutableNode> q = new LinkedList<>();
		q.add(rnode);
		while(!q.isEmpty()){
			rnode = q.poll();
			Node tmpNode = rnode.getNode();
			if(this.isSwitchBox(tmpNode)){
				while(rnode != null){
					partialPath.add(rnode);//rip-up and re-routing should not clear this path?
					rnode = (RoutableNode) rnode.rnodeData.getPrev();
				}
				//add all INT nodes to partial path as the sources of a con
				return partialPath;
			}
			
			for(PIP pip:tmpNode.getAllDownhillPIPs()){	
				Tile tile = pip.getStartWire().getTile();
				Wire tmpWire = new Wire(tile, pip.getEndWireIndex());
				Node newNode = new Node(tmpWire.getTile(),tmpWire.getWireIndex());
				RoutableNode rnewNode = this.createRoutableNodeAndAdd(this.rrgNodeId, newNode, RoutableType.INTERRR, this.base_cost_fac, n.getType());
				rnewNode.rnodeData.setPrev(rnode);
				q.add(rnewNode);
				
				
				Wire newNodeHead = tmpWire.getStartWire();
				if(!newNodeHead.equals(tmpWire)){
					Node newHeadNode = new Node(newNodeHead.getTile(), newNodeHead.getWireIndex());
					RoutableNode rnewHeadNode = this.createRoutableNodeAndAdd(this.rrgNodeId, newHeadNode, RoutableType.INTERRR, this.base_cost_fac, n.getType());
					rnewHeadNode.rnodeData.setPrev(rnode);
					q.add(rnewHeadNode);
				}
			}
			
		}
		
		return null;
	}
	
	//TODO use this for INT-based routing
	public List<Routable> findInputPinFeed(Node node, NetType netType){
		List<Routable> partialPath = new ArrayList<>();
		
		//this should be handled well, otherwise it will impact the routing functionality, e.g. con target unreachable
		RoutableNode rnode = this.createRoutableNodeAndAdd(this.rrgNodeId, node, RoutableType.SINKRR, this.base_cost_fac, netType);
		rnode.rnodeData.setPrev(null);
		
		Queue<RoutableNode> q = new LinkedList<>();
		q.add(rnode);
		
		while(!q.isEmpty()){
			rnode = q.poll();
			
			Node tmpNode = rnode.getNode();
			if(this.isSwitchBox(tmpNode)){
				while(rnode != null){
					partialPath.add(rnode);//rip-up and re-routing should not clear this path?
					rnode = (RoutableNode) rnode.rnodeData.getPrev();
				}
				
				return partialPath;
			}
			
			for(PIP pip:tmpNode.getTile().getBackwardPIPs(tmpNode.getWire())){
				Wire tmpWire = new Wire(tmpNode.getTile(),pip.getStartWireIndex());
				Node newNode = new Node(tmpWire.getTile(),tmpWire.getWireIndex());
				RoutableNode rnewNode = this.createRoutableNodeAndAdd(this.rrgNodeId, newNode, RoutableType.INTERRR, this.base_cost_fac, netType);
				rnewNode.rnodeData.setPrev(rnode);
				q.add(rnewNode);
				
				
				Wire newNodeHead = tmpWire.getStartWire();
				if(!newNodeHead.equals(tmpWire)){
//					System.out.println("----many----");//what does this mean? it is the same case for the existing rw router
					Node newHeadNode = new Node(newNodeHead.getTile(), newNodeHead.getWireIndex());
					RoutableNode rnewHeadNode = this.createRoutableNodeAndAdd(this.rrgNodeId, newHeadNode, RoutableType.INTERRR, this.base_cost_fac, netType);
					rnewHeadNode.rnodeData.setPrev(rnode);
					q.add(rnewHeadNode);
				}
			}
			
		}
		return null;
	}
	
	//not possible to get all sitePins of a tile, a name needed to get the sitePin
	public void checkDeviceWideInputPinFeed(){
		for(Tile tile:this.design.getDevice().getAllTiles()){
			int siteLength = tile.getSites().length;
			System.out.println("Tile type " + tile.getTileTypeEnum() + ", has # sites = " + siteLength);
			int allPins = 0;
			for(Site site:tile.getSites()){
				int pinCount = site.getSitePinCount();
				System.out.println(pinCount);
				allPins += pinCount;
				for(int i = 0; i< pinCount; i++){
					Node node = site.getConnectedNode(i);
					if(node != null){
						List<Routable> path = this.findInputPinFeed(node, NetType.UNKNOWN);//check INT nodes of a single site
						System.out.println("path length = " + path.size() + " " + path.get(0).toString());
					}
				}
			}
			
		}
	}
	
	public void checkDesignWideInputPinFeed(){
		Map<TileTypeEnum, Set<Integer>> map = new HashMap<>();
		for(Connection con : this.connections){
			SitePinInst sitePinInst = con.sink;
			TileTypeEnum tileType = sitePinInst.getTile().getTileTypeEnum();
			Set<Integer> nums = new HashSet<>();
			map.put(tileType, nums);	
		}
		
		for(Connection con:this.connections){
			SitePinInst sitePinInst = con.sink;
			TileTypeEnum type = sitePinInst.getTile().getTileTypeEnum();
			Node sinkPinNode = ((RoutableNode)(con.getSinkRNode())).getNode();
			List<Routable> path = this.findInputPinFeed(sinkPinNode, con.getNet().getType());
			if(path != null){
				map.get(type).add(path.size());
				if(type.equals(TileTypeEnum.BRAM) || type.equals(TileTypeEnum.XIPHY_L)){
					System.out.println(type + " to INT tile, net = " + con.getNet().getNet().getName());
					for(Routable rn:path){
						System.out.println(rn.toString());
					}
					System.out.println();
				}
			}else{
				if(!sitePinInst.getName().contains("CIN")){
					System.out.println("input sitePinInst = " + sitePinInst.toString() + ", \t\tnull path found");
					System.out.println("\tfrom net: " + con.getNet().getNet().getName() + ", \tsource = " + con.source.toString() + "\n");
				}
			}
		}
		
		for(TileTypeEnum type:map.keySet()){
			System.out.println(type + ": " + map.get(type));
		}
		
	}
	
	public boolean isSwitchBox(Node node){
		Tile t = node.getTile();
		TileTypeEnum tt = t.getTileTypeEnum();
		if(t.getDevice().getSeries() == Series.Series7){
			return tt == TileTypeEnum.INT_L || tt == TileTypeEnum.INT_R;
		}
		return tt == TileTypeEnum.INT;
	}
	
	public void initializeRouter(float initial_pres_fac, float pres_fac_mult, float acc_fac){
		this.rnodesTouched.clear();
    	this.queue.clear();
    	
		//routing schedule
    	this.initial_pres_fac = initial_pres_fac;
    	this.pres_fac_mult = pres_fac_mult;
    	this.acc_fac = acc_fac;
		this.itry = 1;
		this.pres_fac = this.initial_pres_fac;
		
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
        System.out.printf("%9s  %11s  %12s  %12s  %15s  %11s  %7s  %8s  %17s \n", 
        		"Iteration", 
        		"Conn routed", 
        		"Run Time (s)", 
        		"Total RNodes", 
        		"RNodes Tacc (s)",
        		"Used RNodes",
        		"Illegal",
        		"Overused", 
        		"OverUsePercentage");
        System.out.printf("---------  -----------  ------------  ------------  ---------------  -----------  -------  --------  -----------------\n");
	}
	
	public int routingRuntime(){
		 long start = System.nanoTime();
		 this.route();
		 long end = System.nanoTime();
		 int timeInMilliseconds = (int)Math.round((end-start) * Math.pow(10, -6));
		 this.printTotalUsedNodes();
		 return timeInMilliseconds;
	}
	
	public void route(){//TODO main route
		this.sortNetsAndConnections();
		
//		this.routeStaticNets();	
		
		//initialize router
		this.initializeRouter(this.initial_pres_fac, this.pres_fac_mult, this.acc_fac);
				
		//do routing
		boolean validRouting;
        List<Netplus> trialNets = new ArrayList<>();
        for(Netplus net : this.sortedListOfNetplus){
        	if(net.getNet().getName().equals("tile2_2/rxByteCount_reg[4]")){
        		trialNets.add(net);
        	}
        }
        
        List<Connection> trialCons = new ArrayList<>();
        for(Connection con : this.sortedListOfConnection){
        	if(con.id == 47468){
        		trialCons.add(con);
        		/*con.pathFromSinkToSwitchBox = this.findInputPinFeed(con);
        		for(Routable rn:con.pathFromSinkToSwitchBox){
        			System.out.println(rn.toString());
        		}
        		System.out.println();*/
        	}
        }
        
        //check INT node of con.source
//        this.findINTNodeOfOutputPin();
        
		while(this.itry < this.nrOfTrials){
			this.iterationStart = System.nanoTime();
			
			this.connectionsRoutedIteration = 0;	
			validRouting = true;	
			if(this.trial) this.printInfo("iteration " + this.itry + " begins");
			
			if(!this.trial){
				for(Connection con:this.sortedListOfConnection){
					this.routingAndTimer(con);
				}
			}else{
//				for(Netplus np : trialNets){
//					for(Connection c : np.getConnection()){
					for(Connection c : trialCons){
						this.routingAndTimer(c);
					}	
//				}
			}
			
			if(this.itry == 1){
				this.nodesExpandedFirstIter = this.nodesExpanded;
				this.nodesPopedFromQueueFirstIter = this.nodesPopedFromQueue;
			}
		
			//check if routing is valid
			validRouting = this.isValidRouting();
			
			//fix illegal routing trees if any
			if(validRouting){
				this.routerTimer.rerouteIllegal.start();
				//for fixing illegalTree using nodes
				for(Connection con:this.sortedListOfConnection){
					con.newNodes();
					for(Routable rn:con.rnodes){
						con.nodes.add(rn.getNode());//wire router should check if node has been added or not, different wires belong to same node
					}
				}
				
				this.fixIllegalTree(sortedListOfConnection);
				this.routerTimer.rerouteIllegal.finish();
			}
			
			this.iterationEnd = System.nanoTime();
			//statistics
			this.routerTimer.calculateStatistics.start();
			this.staticticsInfo(this.sortedListOfConnection, 
					this.iterationStart, this.iterationEnd, 
					this.rrgNodeId, this.routerTimer.rnodesCreation.getTime());
			this.routerTimer.calculateStatistics.finish();;
			//if the routing is valid /realizable return, the routing completed successfully
			
			if (validRouting) {
				//generate and assign a list of PIPs for each Net net
				this.printInfo("\nvalid routing - no congested/illegal rnodes\n ");
				this.routeStaticNets();	
				this.routerTimer.pipsAssignment.start();
				this.pipsAssignment();
				this.routerTimer.pipsAssignment.finish();
				
				return;
			}
			
			this.routerTimer.updateCost.start();
			//Updating the cost factors
			this.updateCostFactors();
			// increase router iteration
			this.itry++;
			this.routerTimer.updateCost.finish();
		}
		
		if (this.itry == this.nrOfTrials + 1) {
			System.out.println("Routing failled after " + this.itry + " trials!");
		}
		
		return;
	}
	
	public void printTotalUsedNodes(){		
		Set<Node> used = new HashSet<>();
		for(Connection c:this.sortedListOfConnection){
			for(Node n:c.nodes){
				used.add(n);
			}
		}
		
		System.out.println("Total used Nodes: " + used.size());
	}
	
	public void routingAndTimer(Connection con){
		if(this.itry == 1){
			this.routerTimer.firstIteration.start();
			this.routeACon(con);
			this.routerTimer.firstIteration.finish();
		}else if(con.congested()){
			this.routerTimer.rerouteCongestion.start();
			this.routeACon(con);
			this.routerTimer.rerouteCongestion.finish();
		}
	}
	
	public void sortNetsAndConnections(){
		//sorted nets and connections
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfConnection.addAll(this.connections);
		Collections.sort(this.sortedListOfConnection, Comparators.FanoutBBConnection);	
		
		this.sortedListOfNetplus = new ArrayList<>();
		this.sortedListOfNetplus.addAll(this.nets);
		Collections.sort(this.sortedListOfNetplus, Comparators.FanoutNet);
	}
	
	public boolean isValidRouting(){
		if(this.debugRoutingCon) this.printInfo("check valid routing"); 
		for(RoutableNode rnode:this.rnodesCreated.values()){
			if(rnode.overUsed()){
				return false;
			}
		}
		return true;
	}
	
	/**
	 * statistics output for each router iteration
	 */
	public void staticticsInfo(List<Connection> connections, 
			long iterStart, long iterEnd,
			int globalRNodeId, long rnodesT){
		
		if(this.itry == 1){
			this.firstIterRNodes = this.rnodesCreated.size();
			this.firstRouting = (float) ((iterEnd - iterStart - rnodesT)*1e-9);
			this.firtRnodeT = (float) (this.routerTimer.rnodesCreation.getTime() * 1e-9);
		}
		this.getOverusedAndIllegalRNodesInfo(connections);
		
		int numRNodesCreated = this.rnodesCreated.size();
		int overUsed = this.overUsedRNodes.size();
		int illegal = this.illegalRNodes.size();
		double overUsePercentage = 100.0 * (double)this.overUsedRNodes.size() / numRNodesCreated;
		System.out.printf("%9d  %11d  %12.2f  %12d  %15.2f  %11d  %7d  %8d  %16.2f%% \n", 
				this.itry,
				this.connectionsRoutedIteration,
				(iterEnd - iterStart)*1e-9,
				globalRNodeId,
				rnodesT*1e-9,
				this.usedRNodes.size(),
				illegal,
				overUsed,
				overUsePercentage);
	}
	
	public void updateCostFactors(){
		if(this.debugRoutingCon) this.printInfo("update cost factors"); 
		if (this.itry == 1) {
			this.pres_fac = this.initial_pres_fac;
		} else {
			this.pres_fac *= this.pres_fac_mult;
		}
		this.updateCost(this.pres_fac, this.acc_fac);
	}
	
	private void updateCost(float pres_fac, float acc_fac) {
		for(RoutableNode rnode:this.rnodesCreated.values()){
			int overuse =rnode.getOccupancy() - Routable.capacity;
			//Present congestion penalty
			if(overuse == 0) {
				rnode.setPres_cost(1 + pres_fac);
			} else if (overuse > 0) {
				rnode.setPres_cost(1 + (overuse + 1) * pres_fac);
				rnode.setAcc_cost(rnode.getAcc_cost() + overuse * acc_fac);
			}
		}	
	}
	
	@SuppressWarnings("unchecked")
	public void getAllHopsAndManhattanD(){
		//first check if routing is valid
		int err = 0;
		this.averFanoutRNodes = 0;
		float sumChildren = 0;
		float sumRNodes = 0;
		for(RoutableNode rn:this.rnodesCreated.values()){
			
			if(rn.childrenSet){
				sumChildren += rn.children.size();
				sumRNodes++;
			}
			
			if(rn.overUsed() || rn.illegal()){
				System.err.println(rn.toString());//if the fixingIllegalTree method in GraphHelper does not change records of RNodes, err will be non-zero
				err++;
			}
		}
		this.averFanoutRNodes = sumChildren / sumRNodes;
		
		if (err == 0){
			System.out.println("\nNo errors found\n");
		}else if(err > 0){
			System.out.println("***** error " + err + " *****");
		}
		
		this.hops = 0;
		this.manhattanD = 0;
		
		Set<Routable> netRNodes = new HashSet<>();
		for(Netplus net:this.nets){	
			for(Connection c:net.getConnection()){
				netRNodes.addAll(c.rnodes);
				this.hops += c.rnodes.size() - 1;//hops for all sinks
			}
			for(Routable rnode:netRNodes){
				this.manhattanD += rnode.getManhattanD();
			}
			netRNodes.clear();
		}
	}
	
	public void getOverusedAndIllegalRNodesInfo(List<Connection> connections) {
		this.usedRNodes.clear();
		this.overUsedRNodes.clear();
		this.illegalRNodes.clear();
		for(Connection conn : connections) {
			for(Routable rnode : conn.rnodes) {
				if(rnode.used()){
					this.usedRNodes.add(rnode.hashCode());
				}
				if(rnode.overUsed()){
					this.overUsedRNodes.add(rnode.hashCode());
				}
				if(rnode.illegal()){
					this.illegalRNodes.add(rnode.hashCode());
				}
			}
		}
	}
	
	public void outOfTrialIterations(int nrOfTrials){
		if (this.itry == nrOfTrials + 1) {
			System.out.println("Routing failled after " + this.itry + " trials!");
		}
	}
	
	public Map<Netplus, Set<Routable>> getIllegalRoutingTreesAndRNodes(){
		Map<Netplus, Set<Routable>> trees = new HashMap<>();
		for(Netplus net:this.sortedListOfNetplus){
			for(Connection con:net.getConnection()){
				for(Routable rn:con.rnodes){
					if(rn.illegal()){
						if(!trees.containsKey(net)){
							Set<Routable> rnodes = new HashSet<>();
							rnodes.add(rn);
							trees.put(net, rnodes);
						}else{
							Set<Routable> rnodes = trees.get(net);
							rnodes.add(rn);
							trees.put(net, rnodes);
						}
					}
				}
			}
		}
		return trees;
	}
	public int getIllegalNumRNodes(List<Connection> cons){
		Set<Routable> illegal = new HashSet<>();
		Map<Routable, Set<String>> illegalRNandNets = new HashMap<>();
		for(Connection c:cons){
			for(Routable rn:c.rnodes){
				if(rn.illegal()){
					illegal.add(rn);
					if(this.debugRoutingCon){
						if(!illegalRNandNets.containsKey(rn.hashCode())){
							Set<String> nets = new HashSet<>();
							nets.add(c.getNet().getNet().getName());
							illegalRNandNets.put(rn, nets);
						}else{
							Set<String> tmp = illegalRNandNets.get(rn.hashCode());
							tmp.add(c.getNet().getNet().getName());
							illegalRNandNets.put(rn, tmp);
						}
					}
				}
			}
		}
		if(this.debugRoutingCon){
			for(Routable rn:illegal){
				this.printInfo("index = " + ((RoutableNode)rn).index + ": " + ((RoutableNode)rn).getNode().toString() + ", nets = " + illegalRNandNets.get(rn));
			}
		}
		return illegal.size();	
	}
	
	public void fixIllegalRoutingTrees(List<Netplus> nets){
		int numIllegalTrees = this.getIllegalRoutingTreesAndRNodes().size();
		if(numIllegalTrees > 0){
			this.printInfo("There are " + numIllegalTrees + " illegal routing trees");
		}
	}
	
	public void fixIllegalTree(List<Connection> cons) {
 		if(this.debugRoutingCon) this.printInfo("fix illegal tree"); 
		if(this.debugRoutingCon) this.printInfo("checking if there is any illegal node");	
		int numIllegal = this.getIllegalNumRNodes(cons);	
		GraphHelper graphHelper = new GraphHelper();
		if(numIllegal > 0){
			if(this.debugRoutingCon) this.printInfo("There are " + numIllegal + " illegal routing tree nodes");
			
			List<Netplus> illegalTrees = new ArrayList<>();
			for(Netplus net : this.nets) {
				boolean illegal = false;
				for(Connection con : net.getConnection()) {
					if(con.illegal()) {
						illegal = true;
					}
				}
				if(illegal) {
					illegalTrees.add(net);
				}
			}
			
			//find the illegal connections and fix illegal trees
			for(Netplus illegalTree:illegalTrees){
				if(this.debugRoutingCon) System.out.println("Net " + illegalTree.getNet().getName() + " routing tree is cyclic? ");
				for(Connection c:illegalTree.getConnection()){
					this.ripup(c);
				}
				boolean isCyclic = graphHelper.isCyclic(illegalTree);
				if(isCyclic){
					//remove cycles
					System.out.println("cycle exists");
					graphHelper.cutOffIllegalEdges(illegalTree, true);
				}else{
					if(this.debugRoutingCon) this.printInfo("fixing net: " + illegalTree.hashCode());
					graphHelper.cutOffIllegalEdges(illegalTree, false);
				}
//				
			}
		}
	}
	
	public void handleNoCyclicIllegalRoutingTree(Netplus illegalTree){
		Routable illegalRNode;
		Node illegalNode;
		while((illegalRNode = illegalTree.getIllegalRNode()) != null){
			illegalNode = illegalRNode.getNode();
			Set<Connection> illegalCons = new HashSet<>();
			for(Connection con : illegalTree.getConnection()) {
				for(Routable rnode : con.rnodes) {
					if(rnode.equals(illegalRNode)) {
						illegalCons.add(con);
					}
				}
			}
			
			//fixing the illegal trees, since there is no criticality info, use the hops info
			//Find the illegal connection with maximum number of RNodes (hops)
			Connection maxCriticalConnection = (Connection) illegalCons.toArray()[0];
			for(Connection illegalConnection : illegalCons) {
				if(illegalConnection.rnodes.size() > maxCriticalConnection.rnodes.size()) {
					maxCriticalConnection = illegalConnection;
				}
			}
			if(this.debugRoutingCon) this.printInfo("  max con" + maxCriticalConnection.id);
			
			//Get the path from the connection with maximum hops
			List<Routable> newRouteNodes = new ArrayList<>();
			
			boolean add = false;
			for(Routable newRouteNode : maxCriticalConnection.rnodes) {
				if(newRouteNode.equals(illegalRNode)) add = true;
				if(add) {
					newRouteNodes.add(newRouteNode);
				}
			}
			
			//Replace the path of each illegal connection with the path from the connection with maximum hops
			for(Connection illegalConnection : illegalCons) {
				this.ripup(illegalConnection);
				
				//Remove illegal path from routing tree
				while(!illegalConnection.rnodes.remove(illegalConnection.rnodes.size() - 1).equals(illegalRNode));
				while(!illegalConnection.nodes.remove(illegalConnection.nodes.size() - 1).equals(illegalNode));
				
				//Add new path to routing tree
				for(Routable newRouteNode : newRouteNodes) {
					illegalConnection.addRNode(newRouteNode);
					illegalConnection.addNode(newRouteNode.getNode());
				}
				
				this.add(illegalConnection);//update entry node info and pip assignment based on con.nodes
			}
			
		}
	}
	
	public void ripup(Connection con){
		RoutableNode parent = null;
		for(int i = con.rnodes.size() - 1; i >= 0; i--){
			RoutableNode rnode = (RoutableNode) con.rnodes.get(i);
			RoutableData rNodeData = rnode.rnodeData;
			
			rNodeData.removeSource(con.source);
			
			if(parent == null){
				parent = rnode;
			}else{
				rNodeData.removeParent(parent);
				parent = rnode;
			}
			// Calculation of present congestion penalty
			rnode.updatePresentCongestionPenalty(this.pres_fac);
		}
	}
	public void add(Connection con){
		RoutableNode parent = null;
		for(int i = con.rnodes.size()-1; i >= 0; i--){
			RoutableNode rnode = (RoutableNode) con.rnodes.get(i);
			RoutableData rNodeData = rnode.rnodeData;
			
			rNodeData.addSource(con.source);
			
			if(parent == null){
				parent = rnode;
			}else{
				rNodeData.addParent(parent);
				parent = rnode;
			}
			// Calculation of present congestion penalty
			rnode.updatePresentCongestionPenalty(this.pres_fac);
		}
	}
	
	public void pipsAssignment(){
		for(Netplus np:this.sortedListOfNetplus){
			Set<PIP> netPIPs = new HashSet<>();
			
			for(Connection c:np.getConnection()){
				netPIPs.addAll(RouterHelper.conPIPs(c.nodes));
			}
			np.getNet().setPIPs(netPIPs);
//			RouteStatusHelper.pipsInfoOfNetsWithUnroutedPins(np);
		}
		
		this.checkPIPsUsage();
		
//		this.checkInvalidlyRoutedNets("LUT6_2_0/O5");
//		this.printWrittenPIPs();
//		this.printConNodes(113819);
	}
	
	public void printWrittenPIPs(){
		for(Net net:this.design.getNets()){
			System.out.println(net.toStringFull());
		}
	}
	
	public void printConNodes(int id){
		for(Connection con:this.sortedListOfConnection){
			if(con.id == id){
				System.out.println("con netplus bounding box: (" + con.net.x_min_b + ", " + con.net.y_min_b 
						+ ") to (" + + con.net.x_max_b + ", " + con.net.y_max_b + ")");
				this.debugRoutingCon = true;
				this.printConRNodes(con);;
			}
		}
	}
	
	public void checkPIPsUsage(){
		Map<PIP, Integer> pipsUsage = new HashMap<>();
		for(Net net:this.design.getNets()){
			for(PIP pip:net.getPIPs()){
				if(!pipsUsage.containsKey(pip)){
					pipsUsage.put(pip, 1);
				}else{
					pipsUsage.put(pip, pipsUsage.get(pip) + 1);
				}
			}
		}
		int pipsError = 0;
		for(PIP pip:pipsUsage.keySet()){
			if(pipsUsage.get(pip) > 1){
				System.out.println("pip " + pip + " usage = " + pipsUsage.get(pip));
				pipsError++;
			}
		}
		if(pipsError > 0)
			System.out.println("PIPs overused error: " + pipsError);
		else
			System.out.println("No PIP oversage");
	}
	
	public void checkInvalidlyRoutedNets(String netname){
		boolean foundInRWrouter = false;
		for(Netplus net:this.sortedListOfNetplus){
			if(net.getNet().getName().equals(netname)){
				foundInRWrouter = true;
				System.out.println(net.getNet().toString() + ", out: " + net.getNet().getSource().getName());
				for(Connection c: net.getConnection()){
					System.out.println(((RoutableNode)c.getSinkRNode()).getNode().toString() + " -> " + c.sink.getName());
					for(PIP p:RouterHelper.conPIPs(c)){
						System.out.println("\t" + p.toString());
					}
					System.out.println();
				}
			}
		}
		
		if(!foundInRWrouter){
			System.out.println("not processded by rw router");
			Net net = this.design.getNet(netname);
			System.out.println(net.toStringFull());
			System.out.println(net.getSource().toString() + " " + net.getSinkPins().size());
		}
	}
	
	public void findAverBaseCosts(){
		Set<Float> costs = new HashSet<>();
		float aver = 0;
		float sum = 0;
		for(RoutableNode rn:this.rnodesCreated.values()){
			sum += rn.base_cost;
			costs.add(rn.base_cost);
		}
		aver = sum/this.rnodesCreated.size();
		System.out.println(aver);
	}
	
	public void findCongestion(){
		for(RoutableNode rn : this.rnodesCreated.values()){
			if(rn.overUsed()){
				System.out.println(rn.toString());
			}
		}
		Set<Connection> congestedCons = new HashSet<>();
//		Map<Netplus<Node>, Integer> congestedNets = new HashMap<>();
		for(Connection con:this.sortedListOfConnection){
			if(con.congested()){
				congestedCons.add(con);
			}
		}
		for(Connection con:congestedCons){
			System.out.println(con.toString());
			for(Routable rn : con.rnodes){
				if(rn.overUsed()) System.out.println("\t"+ rn.toString());
			}
			System.out.println();
		}
	}
	
	public boolean targetReached(Connection con){
		if(this.queue.size() > 0){
			return this.queue.peek().rnode.isTarget();
		}else{//dealing with null pointer exception
			System.out.println("queue is empty");
			System.out.println("Expanded nodes: " + this.nodesExpanded);
			System.out.println(con.getNet().getNet().getName());
			System.out.println(con.toString());
			throw new RuntimeException("Queue is empty: target unreachable?");
		}
	}

	public void routeACon(Connection con){
		this.routerTimer.prepareRouting.start();
		this.prepareForRoutingACon(con);
		this.routerTimer.prepareRouting.finish();
		
		this.routerTimer.prepareRoutingDummy.start();
		this.routerTimer.prepareRoutingDummy.finish();
		
		while(!this.targetReached(con)){
			
			RoutableNode rnode = (RoutableNode) queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			if(!rnode.childrenSet){
				Pair<Integer, Long> intPair = rnode.setChildren(con, this.rrgNodeId, this.base_cost_fac, this.rnodesCreated, 
						this.routethruHelper, this.routerTimer, this.callingOfGetNextRoutable);
				this.rrgNodeId = intPair.getFirst();
				this.callingOfGetNextRoutable = intPair.getSecond();
			}
			this.routerTimer.rnodesCreation.finish();
			
			this.routerTimer.rnodesDummy.start();
			this.routerTimer.rnodesDummy.finish();
			
			this.exploringAndExpansion(rnode, con);
		}
		
		this.finishRoutingACon(con);
		
//		this.printConRNodes(con);
	}
	
	public void printConRNodes(Connection con){
		if(this.debugRoutingCon){
			for(int i = con.rnodes.size() - 1; i >= 0; i-- ){
				Routable rn = con.rnodes.get(i);
				this.printInfo(((RoutableNode)(rn)).toString());
			}
			this.printInfo("");	
		}	
	}
	
	public void finishRoutingACon(Connection con){
		//save routing in connection class
		this.routerTimer.saveRouting.start();
		this.saveRouting(con);
		this.routerTimer.saveRouting.finish();
		
		this.routerTimer.resetPath.start();
		((RoutableNode)con.getSinkRNode()).target = false;
		// Reset path cost
		this.resetPathCost();
		this.routerTimer.resetPath.finish();
		
		this.routerTimer.addRouting.start();
		this.add(con);
		this.routerTimer.addRouting.finish();
		
		this.routerTimer.saveRoutingDummy.start();
		this.routerTimer.saveRoutingDummy.finish();
	}
	
	public void saveRouting(Connection con){
		RoutableNode rn = (RoutableNode) con.getSinkRNode();
		while (rn != null) {
			con.addRNode(rn);
			rn = (RoutableNode) rn.rnodeData.getPrev();
		}
	}

	public void resetPathCost() {
		for (RoutableData node : this.rnodesTouched) {
			node.setTouched(false);
		}
		this.rnodesTouched.clear();	
	}
	
	public void exploringAndExpansion(RoutableNode rnode, Connection con){
		this.nodesPopedFromQueue++;
		
//		if(this.debugExpansion){
//			this.printInfo("\t" + " exploring rnode " + rnode.toString());
//		}
//		if(this.debugExpansion) this.printInfo("\t starting  queue size: " + this.queue.size());
		
		for(RoutableNode childRNode:rnode.children){
			
			if(childRNode.isTarget()){		
//				if(this.debugExpansion) this.printInfo("\t\t childRNode is the target");
				this.addNodeToQueue(rnode, childRNode, con);
				this.nodesExpanded++;
				
			}else if(childRNode.type == RoutableType.INTERRR){
				//this can be done by downsizing the created rnodes
				if(childRNode.isInBoundingBoxLimit(con)){
//					if(this.debugExpansion) this.printInfo("\t\t" + " add node to the queue");
					this.addNodeToQueue(rnode, childRNode, con);
					this.nodesExpanded++;
//					if(this.debugExpansion) this.printInfo("");
				}	
			}
		}
	}
	
	private void addNodeToQueue(RoutableNode rnode, RoutableNode childRNode, Connection con) {
		this.routerTimer.addRNodeToQueueEvaluation.start();
		RoutableData data = childRNode.rnodeData;
		int countSourceUses = data.countSourceUses(con.source);
//		if(this.debugExpansion){
//			this.printInfo("\t\t childRNode " + childRNode.toString());
//		}
		
		float partial_path_cost = rnode.rnodeData.getPartialPathCost();//upstream path cost
		
//		this.routerTimer.getRouteNodeCost.start();
		float rnodeCost = this.getRouteNodeCost(childRNode, con, countSourceUses);
//		this.routerTimer.getRouteNodeCost.finish();
		
		float new_partial_path_cost = partial_path_cost + rnodeCost;//upstream path cost + cost of node under consideration
		float new_lower_bound_total_path_cost;
		float expected_distance_cost = 0;
		float expected_wire_cost;
		
		if(childRNode.type == RoutableType.INTERRR){
			
//			if(this.debugExpansion) this.printInfo("\t\t target RNode " + con.targetName + " (" + con.sink.getTile().getColumn() + "," + con.sink.getTile().getRow() + ")");
			expected_distance_cost = this.expectMahatD(childRNode, con);
			
			expected_wire_cost = expected_distance_cost / (1 + countSourceUses);
			new_lower_bound_total_path_cost = new_partial_path_cost + this.mdWeight * expected_wire_cost + this.hopWeight * (rnode.rnodeData.getLevel() + 1);
			
		}else{//lut input pin (sink)
			new_lower_bound_total_path_cost = new_partial_path_cost;
		}
		this.routerTimer.addRNodeToQueueEvaluation.finish();
		
		this.routerTimer.addRNodeDummy.start();
		this.routerTimer.addRNodeDummy.finish();
		
		this.routerTimer.addRNodeToQueuePushing.start();
		this.addRNodeToQueuePushing(childRNode, rnode, new_partial_path_cost, new_lower_bound_total_path_cost);
		this.routerTimer.addRNodeToQueuePushing.finish();
	}
	
	private void addRNodeToQueuePushing(RoutableNode childRNode, RoutableNode rnode, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
		RoutableData data = childRNode.rnodeData;
		
		if(!data.isTouched()) {
//			if(this.debugExpansion) this.printInfo("\t\t not touched");
			this.rnodesTouched.add(data);
//			if(this.debugExpansion) this.printInfo("\t\t touched node size = "+this.rnodesTouched.size());
			data.setLowerBoundTotalPathCost(new_lower_bound_total_path_cost);
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.rnodeData.getLevel()+1);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
//			if(this.debugExpansion) this.printInfo("\t\t node added, queue size = " + this.queue.size());
			
		} else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {
			//queue is sorted by lower bound total cost
//			if(this.debugExpansion) this.printInfo("\t\t touched previously");
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.rnodeData.getLevel()+1);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
//			if(this.debugExpansion) this.printInfo("\t\t node added, queue size = " + this.queue.size());
		}
	}
	
	private float getRouteNodeCost(RoutableNode rnode, Connection con, int countSourceUses) {
		
		boolean containsSource = countSourceUses != 0;
		//Present congestion cost
		float pres_cost;
		if(containsSource) {
			int overoccupation = rnode.getOccupancy() - Routable.capacity;//numUniqueSources()
			if(overoccupation < 0) {
				pres_cost = 1;
			}else{
				pres_cost = 1 + overoccupation * this.pres_fac;
			}
		}else{
			pres_cost = rnode.getPres_cost();
		}
		
		//Bias cost
		float bias_cost = 0;
		if(rnode.type == RoutableType.INTERRR) {
			Netplus net = con.getNet();
			bias_cost = 0.5f * rnode.base_cost / net.fanout * 
					(Math.abs(rnode.getCenterX() - net.x_geo) + Math.abs(rnode.getCenterY() - net.y_geo)) / net.hpwl;
		}
		
		/*if(this.debugExpansion)
			this.printInfo("\t\t rnode cost = b(n)*h(n)*p(n)/(1+sourceUsage) = " + rnode.base_cost + " * " + rnode.getAcc_cost()+ " * " + pres_cost + " / (1 + " + countSourceUses + ") + " + bias_cost);
		*/
		return rnode.base_cost * rnode.getAcc_cost() * pres_cost / (1 + countSourceUses) + bias_cost;
	}
	
	private float expectMahatD(RoutableNode childRNode, Connection con){
		float md;
		md = Math.abs(childRNode.getCenterX() - con.getSinkRNode().getCenterX()) + Math.abs(childRNode.getCenterY() - con.getSinkRNode().getCenterY());
		return md;
	}
	
	public void prepareForRoutingACon(Connection con){
		this.ripup(con);
		
		this.connectionsRouted++;
		this.connectionsRoutedIteration++;
		// Clear previous route of the connection
		con.resetConnection();
		// Clear the priority queue
		this.queue.clear();	
		
		//set the sink rrg node of con as the target
		RoutableNode sink = (RoutableNode) con.getSinkRNode();
		sink.target = true;
		
		// Add source to queue
		RoutableNode source = (RoutableNode) con.getSourceRNode();
		this.addRNodeToQueuePushing(source, null, 0, 0);
	}
	
	public float checkAverageNumWires(){
		float aver = 0;
		float sum = 0;
		for(RoutableNode rn:this.rnodesCreated.values()){
			sum += rn.getNode().getAllWiresInNode().length;
		}
		aver = sum / this.rnodesCreated.values().size();
		
		return aver;
	}
	
	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}

	public Design getDesign() {
		return this.design;
	}

	public void designInfo(){
		System.out.println("------------------------------------------------------------------------------");
		System.out.println("FPGA tiles size: " + this.design.getDevice().getColumns() + "x" + this.design.getDevice().getRows());
		System.out.println("Total nets: " + this.design.getNets().size());
		System.out.println("Num net to be routed: " + this.nets.size());
		System.out.println("    Num con to be routed: " + this.connections.size());
		System.out.println("    of which Num 1-sink net: " + this.fanout1Net);
		System.out.println("Reserved clock and static nets: " + this.iclockAndStaticNet);	
		System.out.println("Nets not needing routing: " + (this.iWireOneTypePin + this.iWirePinsUnknown));
		System.out.println("    Net with one type pins: " + this.iWireOneTypePin);
		System.out.println("    Net with unknown pins: " + this.iWirePinsUnknown);
		System.out.println("Net unknown type: " + this.iUnknownTypeNet);
		System.out.println("------------------------------------------------------------------------------");
	}
}
