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
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.router.RouteThruHelper;

public class RoutableNodeRouter{
	public Design design;
	
	public List<Netplus> nets;
	public List<Connection> connections;
	public List<Netplus> sortedListOfNetplus;
	public List<Connection> sortedListOfConnection;
	public List<Net> clkNets;
	public Map<Net, List<RoutableNode>> staticNetAndRoutingTargets;
	public int numWIRENetsToBeRouted;
	public int numConsToBeRouted;
	public int numReservedRoutableNets;
	public int numNotNeedingRoutingNets;
	public int numDriverLessAndLoadLessNets;
	public int numUnrecognizedNets;
	public int numRoutbleNets;
	
	public PriorityQueue<QueueElement> queue;
	public Collection<RoutableData> rnodesVisited;
	public Map<Node, RoutableNode> rnodesCreated;//node and rnode pair
	public Map<Node, Net> reservedNodes;
	
	public RouteThruHelper routethruHelper;
	
	public int itry;
	public float pres_fac;
	public float hist_fac;
	public RouterTimer routerTimer;
	public long iterationStart;
	public long iterationEnd;

	public Configuration config;
	
	public int rnodeId;
	public int firstIterRNodes;
	public float firstRouting;
	public float firtRnodeT;
	public float averFanoutRNodes;
	
	public int connectionsRouted;
	public long nodesPushed;
	public long nodesPushedFirstIter;
	public int connectionsRoutedIteration;
	public long nodesPoped;
	public long nodesPopedFirstIter;
	public long callingOfGetNextRoutable;
	
	public Set<Integer> overUsedRNodes;
	public Set<Integer> usedRNodes;
	public Set<Integer> illegalRNodes;//nodes that have multiple drivers in a net
	
	public long hops;
	public float manhattanD;
	
	public RoutableNodeRouter(Design design,
			Configuration config){
		this.design = design;
		DesignTools.createMissingSitePinInsts(this.design);
		
		this.queue = new PriorityQueue<>(Comparators.PRIORITY_COMPARATOR);
		this.rnodesVisited = new ArrayList<>();
		this.reservedNodes = new HashMap<>();
		this.rnodesCreated = new HashMap<>();
		
		this.config = config;
		
		this.routerTimer = new RouterTimer();
		this.rnodeId = 0;
		this.rnodeId = this.categrizingRoutingTargets();
				
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfNetplus = new ArrayList<>();
		
		this.routethruHelper = new RouteThruHelper(this.design.getDevice());
		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesPushed = 0;
		this.nodesPushedFirstIter = 0;
		this.nodesPoped = 0;
		this.nodesPopedFirstIter = 0;
		this.callingOfGetNextRoutable = 0;
		
		this.usedRNodes = new HashSet<>();
		this.overUsedRNodes = new HashSet<>();
		this.illegalRNodes = new HashSet<>();
	}
	
	public int categrizingRoutingTargets(){
		this.numWIRENetsToBeRouted = 0;
		this.numConsToBeRouted = 0;
		this.numReservedRoutableNets = 0;
		this.numNotNeedingRoutingNets = 0;
		this.numUnrecognizedNets = 0;
		this.numRoutbleNets = 0;
		this.numDriverLessAndLoadLessNets = 0;
		
		this.nets = new ArrayList<>();
		this.connections = new ArrayList<>();
		this.clkNets = new ArrayList<>();
		this.staticNetAndRoutingTargets = new HashMap<>();
		
		for(Net n:this.design.getNets()){
			if(n.isClockNet()){
				this.buildGlobalClkRoutingTargets(n);
				
			}else if(n.isStaticNet()){
				this.buildStaticNetRoutingTargets(n);
				
			}else if (n.getType().equals(NetType.WIRE)){
				if(RouterHelper.isRoutableNetWithSourceSinks(n)){
					this.buildNetConRoutingTargets(n);
					
				}else if(RouterHelper.isDriverLessOrLoadLessNet(n)){
					this.reserveNet(n);
					this.numNotNeedingRoutingNets++;
					
				}else if(RouterHelper.isInternallyRoutedNets(n)){
					this.reserveNet(n);
					this.numNotNeedingRoutingNets++;
				}
			}else {
				this.numUnrecognizedNets++;
				System.err.println("Unknown net: " + n.toString());
			}
		}
		
		return this.rnodeId;
	}
	
	public void buildGlobalClkRoutingTargets(Net clk) {
		if(!config.isPartialRouting()) {
			if(RouterHelper.isRoutableNetWithSourceSinks(clk)) {
				this.clkNets.add(clk);
				this.numRoutbleNets++;
			}
		}else {
			this.reserveNet(clk);
			this.numReservedRoutableNets++;
		}
	}
	
	public void routeGlobalClkNet() {
 		if(this.clkNets.size() > 0) System.out.println("Route CLK nets");
 		for(Net clk : this.clkNets) {
			clk.unroute();
			GlobalSignalRoutingTools.clkRouting(clk, this.design.getDevice());
 		}
	}
	
	public void buildNetConRoutingTargets(Net n) {
		if(!config.isPartialRouting()){
			n.unroute();
			this.initializeNetAndCons(n, this.config.getBbRange());
		}else{
			if(n.hasPIPs()){
				// in partial routing mode, nets in the design having pips will be preserved
				// the routed net is supposed to be fully routed without conflicts
				this.reserveNet(n);
				this.numReservedRoutableNets++;
			}else{			
				this.initializeNetAndCons(n, this.config.getBbRange());
			}
		}
	}
	
	public void buildStaticNetRoutingTargets(Net staticNet){
		List<RoutableNode> sinkrns = new ArrayList<>();
		List<SitePinInst> sinks = new ArrayList<>();
		for(SitePinInst sink : staticNet.getPins()){
			if(sink.isOutPin()) continue;
			sinks.add(sink);
		}
		if(sinks.size() > 0 ) {
			if(!config.isPartialRouting()) {
				for(SitePinInst sink : sinks) {
					sinkrns.add(this.createRoutableNodeAndAdd(this.rnodeId, sink.getConnectedNode(), RoutableType.SINKRR, staticNet));
				}
				this.staticNetAndRoutingTargets.put(staticNet, sinkrns);
				this.numRoutbleNets++;
			}else {
				this.reserveNet(staticNet);
				this.numRoutbleNets++;
			}	
			
		}else {//internally routed (sinks.size = 0) or to be reserved for partial routing
			this.reserveNet(staticNet);
			this.numNotNeedingRoutingNets++;	
		}
	}
	
	public void routeStaticNets(){
		if(!config.isPartialRouting()){
			GlobalSignalRoutingTools routingTool = new GlobalSignalRoutingTools(this.design, this.rnodesCreated, this.rnodeId, this.routethruHelper);
			Set<Node> unavailbleNodes = getAllUsedNodesOfRoutedNets();
			unavailbleNodes.addAll(this.reservedNodes.keySet());
			for(Net n:this.staticNetAndRoutingTargets.keySet()){
				n.unroute();
				Map<SitePinInst, List<Node>> spiRoutedNodes = GlobalSignalRoutingTools.routeStaticNet(n, unavailbleNodes);
				this.reserveNet(n);
				for(SitePinInst spi : spiRoutedNodes.keySet()) {
					unavailbleNodes.addAll(spiRoutedNodes.get(spi));
				}
			}
			this.rnodesCreated = GlobalSignalRoutingTools.getRnodesCreated();
			this.rnodeId = GlobalSignalRoutingTools.getRnodeId();
		}else {
			for(Net n:this.staticNetAndRoutingTargets.keySet()){
				this.reserveNet(n);
			}
		}
		
	}
	
	public Set<Node> getAllUsedNodesOfRoutedNets(){
		Set<Node> nodes = new HashSet<>();
		for(Connection c:this.sortedListOfConnection){
			if(c.nodes != null) nodes.addAll(c.nodes);
		}	
		return nodes;
	}
	
	public void reserveNet(Net n){
		//reservePipsOfNet(n) means if the net has any pips, occupied nodes will be reserved 
		//and the net will not be considered as to-be-routed;
		//In case the net is partially routed, reserveConnectedNodesOfNetPins() is needed, 
		//so that connected nodes to all its pins are reserved.
		this.reservePipsOfNet(n);
		this.reserveConnectedNodesOfNetPins(n);
	}
	
	public void initializeNetAndCons(Net n, short bbRange){
		Netplus np = new Netplus(this.numWIRENetsToBeRouted, bbRange, n);
		this.nets.add(np);
		this.numWIRENetsToBeRouted++;
		this.numRoutbleNets++;
		
		SitePinInst source = n.getSource();
		RoutableNode sourceRNode = this.createRoutableNodeAndAdd(this.rnodeId, source.getConnectedNode(), RoutableType.SOURCERR, n);
		
		for(SitePinInst sink:n.getSinkPins()){
			
			if(RouterHelper.isExternalConnectionToCout(source, sink)){
				source = n.getAlternateSource();
				if(source == null){
					String errMsg = "net alterNative source is null: " + n.toStringFull();
					throw new IllegalArgumentException(errMsg);
				}
				
				sourceRNode = this.createRoutableNodeAndAdd(this.rnodeId, source.getConnectedNode(), RoutableType.SOURCERR, n);		
			}
			
			Connection c = new Connection(this.numConsToBeRouted, source, sink);	
			c.setSourceRNode(sourceRNode);
			
			//create RNode of the sink pin up front 
			RoutableNode sinkRNode = this.createRoutableNodeAndAdd(this.rnodeId, sink.getConnectedNode(), RoutableType.SINKRR, n);
			
			c.setSinkRNode(sinkRNode);
			
			this.connections.add(c);
			c.setNet(np);
			np.addCons(c);
			this.numConsToBeRouted++;
		}
		
	}
	
	public void reservePipsOfNet(Net n){
		Set<Node> nodesFromPIPs = new HashSet<>();
		for(PIP pip:n.getPIPs()) {
			nodesFromPIPs.add( pip.getStartNode());
			nodesFromPIPs.add(pip.getEndNode());
		}
		for(Node node:nodesFromPIPs) {
			this.addReservedNode(node, n);
		}
	}
	
	public void addReservedNode(Node node, Net toReserve) {
		Net reserved = this.reservedNodes.get(node);
		if(reserved == null) {
			this.reservedNodes.put(node, toReserve);
		}else if(!reserved.equals(toReserve)){
			System.out.println("Net " + toReserve.getName() + " has conflicting node " + node + " with net " + reserved.getName());
		}
	}
	
	public void reserveConnectedNodesOfNetPins(Net n){
		for(SitePinInst pin:n.getPins()){
			Node node = pin.getConnectedNode();
			if(node == null) {
				System.out.println("! Null node connected to pin " + pin + " of net " + n);
				continue;
			}
			
			this.addReservedNode(node, n);
		}
	}
	
	public RoutableNode createRoutableNodeAndAdd(int globalIndex, Node node, RoutableType type, Net net){
		RoutableNode rrgNode;
		NetType netType = net.getType();
		if(!this.rnodesCreated.containsKey(node)){
			//this is for initializing sources and sinks of those to-be-routed nets's connections
			rrgNode = new RoutableNode(globalIndex, node, type);
			this.rnodesCreated.put(rrgNode.getNode(), rrgNode);
			this.rnodeId++;
		}else{
			//this is for checking preserved routing resource conflicts among routed nets
			rrgNode = this.rnodesCreated.get(node);
			if(rrgNode.type == type && type == RoutableType.SINKRR && netType == NetType.WIRE)
				System.out.println("! Conflicting Sink Site Pin Connected Node: " + node);
		}
		
		return rrgNode;
	}
	
	//TODO having this list of node, the target RoutableNode of a connection will change, not the connected node to the sink pin
	public List<Routable> findInputPinFeed(Connection con){
		
		return this.findInputPinFeed((RoutableNode) con.getSinkRNode(), con.getNet().getNet());
	}
	
	public List<Routable> findINTNodeDrivenByOutputPin(){
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
		RoutableNode rnode = this.createRoutableNodeAndAdd(this.rnodeId, sourceNode, RoutableType.SOURCERR, n);
		rnode.getRoutableData().setPrev(null);
		Queue<RoutableNode> q = new LinkedList<>();
		q.add(rnode);
		while(!q.isEmpty()){
			rnode = q.poll();
			Node tmpNode = rnode.getNode();
			if(this.isSwitchBox(tmpNode)){
				while(rnode != null){
					partialPath.add(rnode);//rip-up and re-routing should not clear this path?
					rnode = (RoutableNode) rnode.getRoutableData().getPrev();
				}
				//add all INT nodes to partial path as the sources of a con
				return partialPath;
			}
			
			for(PIP pip:tmpNode.getAllDownhillPIPs()){	
				Tile tile = pip.getStartWire().getTile();
				Wire tmpWire = new Wire(tile, pip.getEndWireIndex());
				Node newNode = new Node(tmpWire.getTile(),tmpWire.getWireIndex());
				RoutableNode rnewNode = this.createRoutableNodeAndAdd(this.rnodeId, newNode, RoutableType.INTERRR, n);
				rnewNode.getRoutableData().setPrev(rnode);
				q.add(rnewNode);
				
				Wire newNodeHead = tmpWire.getStartWire();
				if(!newNodeHead.equals(tmpWire)){
					Node newHeadNode = new Node(newNodeHead.getTile(), newNodeHead.getWireIndex());
					RoutableNode rnewHeadNode = this.createRoutableNodeAndAdd(this.rnodeId, newHeadNode, RoutableType.INTERRR, n);
					rnewHeadNode.getRoutableData().setPrev(rnode);
					q.add(rnewHeadNode);
				}
			}
			
		}
		
		return null;
	}
	
	//TODO use this for INT-based routing
	public List<Routable> findInputPinFeed(Routable rnode, Net net){
		List<Routable> partialPath = new ArrayList<>();
		
		//this should be handled well, otherwise it will impact the routing functionality, e.g. con target unreachable
		rnode.getRoutableData().setPrev(null);
		
		Queue<Routable> q = new LinkedList<>();
		q.add(rnode);
		
		while(!q.isEmpty()){
			rnode = q.poll();
			
			Node tmpNode = rnode.getNode();
			if(this.isSwitchBox(tmpNode)){
				while(rnode != null){
					partialPath.add(rnode);//rip-up and re-routing should not clear this path?
					rnode = rnode.getRoutableData().getPrev();
				}
				
				return partialPath;
			}
			
			for(PIP pip:tmpNode.getTile().getBackwardPIPs(tmpNode.getWire())){
				Wire tmpWire = new Wire(tmpNode.getTile(),pip.getStartWireIndex());
				Node newNode = new Node(tmpWire.getTile(),tmpWire.getWireIndex());
				Routable rnewNode = this.createRoutableNodeAndAdd(this.rnodeId, newNode, RoutableType.INTERRR, net);
				rnewNode.getRoutableData().setPrev(rnode);
				q.add(rnewNode);
				
				
				Wire newNodeHead = tmpWire.getStartWire();
				if(!newNodeHead.equals(tmpWire)){
//					System.out.println("----many----");//what does this mean? it is the same case for the existing rw router
					Node newHeadNode = new Node(newNodeHead.getTile(), newNodeHead.getWireIndex());
					Routable rnewHeadNode = this.createRoutableNodeAndAdd(this.rnodeId, newHeadNode, RoutableType.INTERRR, net);
					rnewHeadNode.getRoutableData().setPrev(rnode);
					q.add(rnewHeadNode);
				}
			}
			
		}
		return null;
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
			List<Routable> path = this.findInputPinFeed((RoutableNode) con.getSinkRNode(), con.getNet().getNet());
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
	
	public void initializeRouting(){
		this.rnodesVisited.clear();
    	this.queue.clear(); 	
		this.itry = 1;
    	this.hist_fac = config.getAcc_fac();
    	this.pres_fac = config.getInitial_pres_fac();
		
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
	
	public int doRouting(){
		long start = System.nanoTime();
		this.routeGlobalClkNet();
		this.routeConnections();
		this.routeStaticNets();
		this.routerTimer.pipsAssignment.start();
		this.pipsAssignment();
		this.routerTimer.pipsAssignment.finish();
		long end = System.nanoTime();
		int timeInMilliseconds = (int)Math.round((end-start) * Math.pow(10, -6));
		
		return timeInMilliseconds;
	}
	
	public void routeConnections(){
		this.sortNetsAndConnections();
		
		//initialize router
		this.initializeRouting();
				
		//do routing
		boolean validRouting;
        
		while(this.itry < config.getNrOfTrials()){
			this.iterationStart = System.nanoTime();			
			this.connectionsRoutedIteration = 0;	
			
			validRouting = true;
			for(Connection con : this.sortedListOfConnection) {
				this.routingScenarios(con);
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
			this.staticticsInfo(this.sortedListOfConnection, this.iterationStart, this.iterationEnd, this.rnodeId, this.routerTimer.rnodesCreation.getTime());
			this.routerTimer.calculateStatistics.finish();
			if(this.itry == 1){
				this.nodesPushedFirstIter = this.nodesPushed;
				this.nodesPopedFirstIter = this.nodesPoped;
			}
			//the routing completed successfully
			if (validRouting) {			
				return;
			}
			
			this.routerTimer.updateCost.start();
			//Updating the cost factors
			this.updateCostFactors();
			// increase router iteration
			this.itry++;
			this.routerTimer.updateCost.finish();
		}
		
		if (this.itry == config.getNrOfTrials() + 1) {
			System.out.println("Routing failled after " + this.itry + " trials!");
		}
		
		return;
	}
	
	public void routingScenarios(Connection con){
		if(this.itry == 1){
			this.routerTimer.firstIteration.start();
			this.routeCon(con);
			this.routerTimer.firstIteration.finish();
		}else if(con.congested()){
			this.routerTimer.rerouteCongestion.start();
			this.routeCon(con);
			this.routerTimer.rerouteCongestion.finish();
		}else if(!con.sink.isRouted()) {
			this.routeCon(con);
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
		if (this.itry == 1) {
			this.pres_fac = config.getInitial_pres_fac();
		} else {
			this.pres_fac *= config.getPres_fac_mult();
		}
		this.updateCost(this.pres_fac, this.hist_fac);
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
	
	public void getAllHopsAndManhattanD(){
		//first check if routing is valid
		int err = 0;
		this.averFanoutRNodes = 0;
		float sumChildren = 0;
		float sumRNodes = 0;
		for(Routable rn:this.rnodesCreated.values()){
			
			if(rn.isChildrenSet()){
				sumChildren += rn.getChildren().size();
				sumRNodes++;
			}
			
			if(rn.overUsed() || rn.illegal()){
				System.err.println(rn.toString());
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
				if(c.rnodes != null) netRNodes.addAll(c.rnodes);
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
	
	public int getIllegalNumRNodes(List<Connection> cons){
		Set<Routable> illegal = new HashSet<>();
		for(Connection c:cons){
			for(Routable rn:c.rnodes){
				if(rn.illegal()){
					illegal.add(rn);
				}
			}
		}
		return illegal.size();	
	}
	
	public void fixIllegalTree(List<Connection> cons) {
 		int numIllegal = this.getIllegalNumRNodes(cons);	
		GraphHelper graphHelper = new GraphHelper();
		if(numIllegal > 0){
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
				for(Connection c:illegalTree.getConnection()){
					this.ripup(c);
				}
				boolean isCyclic = graphHelper.isCyclic(illegalTree);
				if(isCyclic){
					//remove cycles
					graphHelper.cutOffIllegalEdges(illegalTree, true);
				}else{
					graphHelper.cutOffIllegalEdges(illegalTree, false);
				}				
			}
		}
	}
	
	public void ripup(Connection con){
		Routable parent = null;
		for(int i = con.rnodes.size() - 1; i >= 0; i--){
			Routable rnode = con.rnodes.get(i);
			RoutableData rNodeData = rnode.getRoutableData();
			
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
	
	public void addUsageInfoUpdateCongestion(Connection con){
		Routable parent = null;
		for(int i = con.rnodes.size()-1; i >= 0; i--){
			Routable rnode = con.rnodes.get(i);
			RoutableData rNodeData = rnode.getRoutableData();
			
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
		}
		
		this.checkPIPsUsage();
		
	}
	
	public void checkPIPsUsage(){
		Map<PIP, List<Net>> pipsUsage = new HashMap<>();
		for(Net net:this.design.getNets()){
			for(PIP pip:net.getPIPs()){
				List<Net> users = pipsUsage.get(pip);
				if(users == null) 
					users = new ArrayList<>();
				users.add(net);
				pipsUsage.put(pip, users);
				
			}
		}
		int pipsError = 0;
		for(PIP pip:pipsUsage.keySet()){
			if(pipsUsage.get(pip).size() > 1){
				System.out.println("pip " + pip + " users = " + pipsUsage.get(pip));
				pipsError++;
			}
		}
		if(pipsError > 0)
			System.out.println("PIPs overused error: " + pipsError);
		else
			System.out.println("No PIP oversage");
	}
	
	public boolean targetReached(Connection con){
		return this.queue.peek().rnode.isTarget();
	}
	
	public void routeCon(Connection con){
		this.prepareForRoutingACon(con);
		
		boolean successCon = false;
		while(!this.queue.isEmpty()){	
			if(!this.targetReached(con)) {
				RoutableNode rnode = (RoutableNode) this.queue.poll().rnode;
				this.nodesPoped++;
				
				if(!rnode.isChildrenSet()){
					this.routerTimer.rnodesCreation.start();
					Pair<Integer, Long> intPair = rnode.setChildren(con, this.rnodeId, this.rnodesCreated, 
							this.routethruHelper, this.callingOfGetNextRoutable, this.reservedNodes.keySet());
					this.rnodeId = intPair.getFirst();
					this.callingOfGetNextRoutable = intPair.getSecond();
					this.routerTimer.rnodesCreation.finish();
				}
				
				this.exploringAndExpansion(rnode, con);
			}else {
				successCon = true;
				break;
			}
		}
		
		if(successCon) {
			this.finishRoutingACon(con);
			con.sink.setRouted(true);
		}else {
			con.sink.setRouted(false);
			con.getNet().extendBoundingBox();
		}
	}
	
	public void printConRNodes(Connection con){
		for(int i = con.rnodes.size() - 1; i >= 0; i-- ){
			Routable rn = con.rnodes.get(i);
			this.printInfo(((RoutableNode)(rn)).toString());
		}
		this.printInfo("");	
			
	}
	
	public void finishRoutingACon(Connection con){
		//save routing in connection class
		this.saveRouting(con);
		
		con.getSinkRNode().setTarget(false);
		// Reset path cost
		this.resetExpansionRecords();
		
		this.addUsageInfoUpdateCongestion(con);
	}
	/**
	 * Tracing back from the sink routable to the source of the target connection
	 * Storing the path of the connection
	 * @param con: The connection that is being routed
	 */
	public void saveRouting(Connection con){
		Routable rn = con.getSinkRNode();
		while (rn != null) {
			con.addRNode(rn);
			rn = rn.getRoutableData().getPrev();
		}
	}
	
	public void resetExpansionRecords() {
		for (RoutableData node : this.rnodesVisited) {
			node.setTouched(false);
		}
		this.rnodesVisited.clear();	
	}
	/**
	 * Exploring children of the routable for routing a connection
	 * pushing the child into the queue if is the target or is within the routing bounding box
	 * @param rnode: The routable popped out from the queue
	 * @param con: The connection that is being routed
	 */
	public void exploringAndExpansion(Routable rnode, Connection con){
		for(Routable childRNode:rnode.getChildren()){
			
			if(childRNode.isTarget()){		
				this.evaluateCostPushing(rnode, childRNode, con);
				this.nodesPushed++;
				
			}else if(childRNode.getRoutableType() == RoutableType.INTERRR) {
				if(childRNode.isInBoundingBoxLimit(con)){
					this.evaluateCostPushing(rnode, childRNode, con);
					this.nodesPushed++;
				}
			}
		}
	}
	/**
	 * Pushing childRNode into the queue after cost evaluation
	 * @param rnode: The parent routale of childRNode
	 * @param childRNode: Current routable that is being evaluated
	 * @param con: Current target connection
	 */
	private void evaluateCostPushing(Routable rnode, Routable childRNode, Connection con) {
		RoutableData data = childRNode.getRoutableData();
		int countSourceUses = data.countSourceUses(con.source);
		
		float partial_path_cost = rnode.getRoutableData().getPartialPathCost();//upstream path cost	
		float rnodeCost = this.getRoutableCost(childRNode, con, countSourceUses);
		
		float new_partial_path_cost = partial_path_cost + rnodeCost;//upstream path cost + cost of node under consideration
		float new_lower_bound_total_path_cost;
		
		if(childRNode.getRoutableType() == RoutableType.INTERRR){
			float expected_distance_cost = this.expectManhatD(childRNode, con);	
			float expected_wire_cost = expected_distance_cost / (1 + countSourceUses);
			new_lower_bound_total_path_cost = new_partial_path_cost + config.getMdWeight() * expected_wire_cost + config.getHopWeight() * (rnode.getRoutableData().getLevel() + 1);
			
		}else{//sink
			new_lower_bound_total_path_cost = new_partial_path_cost;
		}
		
		this.pushing(childRNode, rnode, new_partial_path_cost, new_lower_bound_total_path_cost);
	}
	/**
	 * Setting the costs of current ChildRNode and pushing it into queue
	 * @param childRNode: Current routable that is being evaluated
	 * @param rnode: The parent routale of this childRNode
	 * @param new_partial_path_cost: The upstream path cost from this current childRNode to the source, inclusive
	 * @param new_lower_bound_total_path_cost: Total path cost of the childRNode
	 */
	private void pushing(Routable childRNode, Routable rnode, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
		RoutableData data = childRNode.getRoutableData();
		
		if(!data.isTouched()) {
			this.rnodesVisited.add(data);
			data.setLowerBoundTotalPathCost(new_lower_bound_total_path_cost);
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.getRoutableData().getLevel()+1);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
			
		} else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.getRoutableData().getLevel()+1);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
		}
	}
	/**
	 * This is to get the congestion cost and bias cost of the current routable
	 * @param rnode: The current routable that is being evaluated
	 * @param con: The current target connection
	 * @param countSourceUses: The number of nets that are using rnode,
	 * Note: a net is represented by the connection's source
	 * @return Biased cost of rnode
	 */
	private float getRoutableCost(Routable rnode, Connection con, int countSourceUses) {	
		boolean containsSource = countSourceUses != 0;
		//Present congestion cost
		float pres_cost;
		if(containsSource) {
			int overoccupation = rnode.getOccupancy() - Routable.capacity;	
			pres_cost = 1 + overoccupation * this.pres_fac;
		}else{
			pres_cost = rnode.getPres_cost();
		}
		
		//Bias cost
		float bias_cost = 0;
		if(rnode.getRoutableType() == RoutableType.INTERRR) {
			Netplus net = con.getNet();
			bias_cost = 0.5f * rnode.getBase_cost() / net.fanout * 
					(Math.abs(rnode.getX() - net.x_geo) + Math.abs(rnode.getY() - net.y_geo)) / net.hpwl;
		}
		
		return rnode.getBase_cost() * rnode.getAcc_cost() * pres_cost / (1 + countSourceUses) + bias_cost;
	}
	
	private float expectManhatD(Routable childRNode, Connection con){
		return Math.abs(childRNode.getX() - con.getSinkRNode().getX()) + Math.abs(childRNode.getY() - con.getSinkRNode().getY());
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
		con.getSinkRNode().setTarget(true);
		
		// Add source to queue
		this.pushing(con.getSourceRNode(), null, 0, 0);
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
	
	public int getNumSitePinOfStaticNets() {
		int totalSitePins = 0;
		for(Net n:this.staticNetAndRoutingTargets.keySet()) {
			totalSitePins += this.staticNetAndRoutingTargets.get(n).size();
		}
		return totalSitePins;
	}
	
	public void designInfo(){
		System.out.println("------------------------------------------------------------------------------");
		System.out.println("FPGA size: " + this.design.getDevice().getColumns() + "x" + this.design.getDevice().getRows());
		System.out.println("Total nets: " + this.design.getNets().size());
		System.out.println("Routable nets: " + this.numRoutbleNets);
		System.out.println("  Preserved routble nets: " + this.numReservedRoutableNets);
		System.out.println("  Nets to be routed: " + (this.nets.size() +  this.staticNetAndRoutingTargets.size() + this.clkNets.size()));
		System.out.println("    GLOBAL_CLOCK: " + this.clkNets.size());
		System.out.println("    Static nets: " + this.staticNetAndRoutingTargets.size());
		System.out.println("    WIRE: " + this.nets.size());
		int clkPins = 0;
		for(Net clk : this.clkNets) {
			clkPins += clk.getSinkPins().size();
		}
		System.out.println("  All site pins to be routed: " + (this.connections.size() + this.getNumSitePinOfStaticNets() + clkPins));	
		System.out.println("    Connections to be routed: " + this.connections.size());
		System.out.println("    Static net pins: " + this.getNumSitePinOfStaticNets());
		System.out.println("    Clock pins: " + clkPins);
		System.out.println("Nets not needing routing: " + this.numNotNeedingRoutingNets);
		if(this.numUnrecognizedNets != 0)
			System.out.println("Nets unrecognized: " + this.numUnrecognizedNets);
		System.out.println("------------------------------------------------------------------------------");
	}
}
