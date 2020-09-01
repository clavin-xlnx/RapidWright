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
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.tests.CodePerfTracker;
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
	
	public List<Connection> sortedListOfConnection;
	public List<Netplus> sortedListOfNetplus;

	public RouteThruHelper routethruHelper;
//	public RouterHelper routerHelper;
	
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
	
	public int rrgNodeId;
	public int firstIterRNodes;
	
	public int connectionsRouted;
	public long nodesExpanded;
	public int connectionsRoutedIteration;
	
	public Set<Integer> overUsedRNodes;
	public Set<Integer> usedRNodes;
	public Set<Integer> illegalRNodes;//nodes that have multiple drivers in a net
	
	public long hops;
	public float manhattanD;
	
	public boolean trial = false;
	public boolean debugRoutingCon = false;
	public boolean debugExpansion = false;
	
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
			float base_cost_fac){
		this.design = design;
		this.queue = new PriorityQueue<>(Comparators.PRIORITY_COMPARATOR);
		this.rnodesTouched = new ArrayList<>();
		
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
		
		DesignTools.createMissingSitePinInsts(this.design);
		
		for(Net n:this.design.getNets()){
			
			if(n.isClockNet() || n.isStaticNet()){
				
				if(n.hasPIPs()){
					this.reservePipsOfNet(n);
				}else{
					this.reserveConnectedNodesOfNetPins(n);
				}
				this.iclockAndStaticNet++;
				
			}else if (n.getType().equals(NetType.WIRE)){
				
				if(RouterHelper.isRegularNetToBeRouted(n)){
					this.initializeNetAndCons(n, bbRange);
					
				}else if(RouterHelper.isOneTypePinNet(n)){
					this.reserveConnectedNodesOfNetPins(n);
					this.iWireOneTypePin++;
					
				}else if(RouterHelper.isNoPinNets(n)){
					this.iWirePinsUnknown++;
				}
			}else{
				this.iUnknownTypeNet++;
				System.err.println("UNKNOWN type net: " + n.toString());
			}
		}
		
//		this.checkDesignWideInputPinFeed();
		
		return rrgNodeId;
	}

	public void initializeNetAndCons(Net n, short bbRange){
		n.unroute();
		Netplus np = new Netplus(inetToBeRouted, bbRange, n);
		this.nets.add(np);
		this.inetToBeRouted++;
		
		SitePinInst source = n.getSource();
		RoutableNode sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.SOURCERR, this.base_cost_fac);
		
		for(SitePinInst sink:n.getSinkPins()){
			
			if(RouterHelper.isExternalConnectionToCout(source, sink)){
				source = n.getAlternateSource();
				if(source == null){
					String errMsg = "net alterNative source is null: " + n.toStringFull();
					throw new IllegalArgumentException(errMsg);
				}
				
				sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.SOURCERR, this.base_cost_fac);		
			}
			
			Connection c = new Connection(this.iconToBeRouted, source, sink);	
			c.setSourceRNode(sourceRNode);
			
			//create RNode of the sink pin external wire up front 
			RoutableNode sinkRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, sink, RoutableType.SINKRR, this.base_cost_fac);
			
			c.setSinkRNode(sinkRNode);
			
			this.connections.add(c);
			c.setNet(np);//TODO new and set its TimingEdge for timing-driven version
			np.addCons(c);
			this.iconToBeRouted++;
		}
		if(n.getSinkPins().size() == 1)
			this.fanout1Net++;
	}
	
	public void reservePipsOfNet(Net n){
		for(PIP pip:n.getPIPs()){
			Node nodeStart = pip.getStartNode();
			this.createRoutableNodeAndAdd(this.rrgNodeId, nodeStart, RoutableType.RESERVED, Float.MAX_VALUE - 1);
			
			Node nodeEnd = pip.getEndNode();
			this.createRoutableNodeAndAdd(this.rrgNodeId, nodeEnd, RoutableType.RESERVED, Float.MAX_VALUE - 1);
		}
	}
	
	public void reserveConnectedNodesOfNetPins(Net n){
		for(SitePinInst pin:n.getPins()){
			Node node = pin.getConnectedNode();
			this.createRoutableNodeAndAdd(this.rrgNodeId, node, RoutableType.RESERVED, Float.MAX_VALUE - 1);			
		}
	}
	
	public RoutableNode createRoutableNodeAndAdd(int globalIndex, Node node, RoutableType type, float base_cost_fac){
		RoutableNode rrgNode = new RoutableNode(globalIndex, node, type);
		this.setBaseCostAndAdd(rrgNode, base_cost_fac);
		return rrgNode;
	}

	public RoutableNode createRoutableNodeAndAdd(int globalIndex, SitePinInst pin, RoutableType type, float base_cost_fac){
		RoutableNode rrgNode = new RoutableNode(rrgNodeId, pin, type);
		this.setBaseCostAndAdd(rrgNode, base_cost_fac);	
		return rrgNode;
	}
	
	public void setBaseCostAndAdd(RoutableNode rrgNode, float base_cost_fac){
		rrgNode.setBaseCost(base_cost_fac);
		this.rnodesCreated.put(rrgNode.getNode(), rrgNode);
		this.rrgNodeId++;
	}
	//TODO having this list of node, the target RoutableNode of a connection will change, not the connected node to the sink pin
	public List<Routable> findInputPinFeed(Connection con){
		//TODO is the path unique?
		Site site = con.sink.getSite();
		String pinName = con.sink.getName();
		Tile tile = site.getTile();
		int wire = site.getTileWireIndexFromPinName(pinName);
		
		Node node = new Node(tile,wire);
		System.out.println(node.toString() + " " + con.sink.getConnectedNode().toString());
		return this.findInputPinFeed(node);
	}
	
	public List<Routable> findInputPinFeed(Node node){
		List<Routable> partialPath = new ArrayList<>();
		
		//TODO this should be handled well, otherwise it will impact the routing functionality, e.g. con target unreachable
		RoutableNode rnode = this.createRoutableNodeAndAdd(this.rrgNodeId, node, RoutableType.SINKRR, this.base_cost_fac);
		rnode.rnodeData.setPrev(null);
		
		Queue<RoutableNode> q = new LinkedList<>();
		q.add(rnode);
		
		while(!q.isEmpty()){
			rnode = q.poll();
			
			Node tmpNode = rnode.getNode();
			if(this.isSwitchBox(tmpNode)){
				while(rnode != null){
					partialPath.add(rnode);//TODO rip-up and re-routing should not clear this path?
					rnode = (RoutableNode) rnode.rnodeData.getPrev();
				}
				
				return partialPath;
			}
			
			for(PIP pip:tmpNode.getTile().getBackwardPIPs(tmpNode.getWire())){
				Wire tmpWire = new Wire(tmpNode.getTile(),pip.getStartWireIndex());
				Node newNode = new Node(tmpWire.getTile(),tmpWire.getWireIndex());
				RoutableNode rnewNode = this.createRoutableNodeAndAdd(this.rrgNodeId, newNode, RoutableType.INTERRR, this.base_cost_fac);
				rnewNode.rnodeData.setPrev(rnode);
				q.add(rnewNode);
				
				
				Wire newNodeHead = tmpWire.getStartWire();
				if(!newNodeHead.equals(tmpWire)){
//					System.out.println("----many----");//TODO what does this mean, it is the same case for the existing rw router
					Node newHeadNode = new Node(newNodeHead.getTile(), newNodeHead.getWireIndex());
					RoutableNode rnewHeadNode = this.createRoutableNodeAndAdd(this.rrgNodeId, newHeadNode, RoutableType.INTERRR, this.base_cost_fac);
					rnewHeadNode.rnodeData.setPrev(rnode);
					q.add(rnewHeadNode);
				}
			}
			
		}
		return null;
	}
	
	//TODO not possible to get all sitePins of a tile, a name needed to get the sitePin
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
						List<Routable> path = this.findInputPinFeed(node);//TODO check INT nodes of a single site
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
			List<Routable> path = this.findInputPinFeed(sinkPinNode);
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
		 
		 return timeInMilliseconds;
	}
	
	public void route(){
		this.sortNetsAndConnections();
		
		//initialize router
		this.initializeRouter(this.initial_pres_fac, this.pres_fac_mult, this.acc_fac);
				
		//do routing
		boolean validRouting;
        List<Netplus> trialNets = new ArrayList<>();
        for(Netplus net : this.sortedListOfNetplus){
        	if(net.getId() == 49945 || net.getNet().getName().equals("n7b5")){
        		trialNets.add(net);
        	}
        }
        
        List<Connection> trialCons = new ArrayList<>();
        for(Connection con : this.sortedListOfConnection){
        	if(con.id == 113819 ||con.id == 1216){
        		trialCons.add(con);
        		/*con.pathFromSinkToSwitchBox = this.findInputPinFeed(con);
        		for(Routable rn:con.pathFromSinkToSwitchBox){
        			System.out.println(rn.toString());
        		}
        		System.out.println();*/
        	}
        }
        
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
		
			//check if routing is valid
			validRouting = this.isValidRouting();
			
			//fix illegal routing trees if any
			if(validRouting){
				this.routerTimer.rerouteIllegal.start();
//				this.debugRoutingCon = true;//TODO fix cycles in the tree
				this.fixIllegalTree(sortedListOfConnection);
				this.routerTimer.rerouteIllegal.finish();
			}
			
			//TODO update timing and criticalities of connections
			
			this.iterationEnd = System.nanoTime();
			//statistics
			this.routerTimer.calculateStatistics.start();
			this.staticticsInfo(this.sortedListOfConnection, 
					this.iterationStart, this.iterationEnd, 
					this.rrgNodeId, this.routerTimer.rnodesCreation.getTime());
			this.routerTimer.calculateStatistics.finish();;
			//if the routing is valid /realizable return, the routing completed successfully
	
			if(this.itry == 1) this.firstIterRNodes = this.rnodesCreated.size();
	
			if (validRouting) {
				//generate and assign a list of PIPs for each Net net
				this.printInfo("\nvalid routing - no congested/illegal rnodes\n ");
							
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
//			System.out.println(this.routerTimer.rnodesCreation.toString());
		}
		
		if (this.itry == this.nrOfTrials + 1) {
			System.out.println("Routing failled after " + this.itry + " trials!");
		}
		
		return;
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
	
	/*
	 * statistics output for each router iteration
	 */
	public void staticticsInfo(List<Connection> connections, 
			long iterStart, long iterEnd,
			int globalRNodeId, long rnodesT){
		
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
			RoutableData data = rnode.rnodeData;
			int overuse = data.getOccupation() - Routable.capacity;
			//Present congestion penalty
			if(overuse == 0) {
				data.setPres_cost(1 + pres_fac);
			} else if (overuse > 0) {
				data.setPres_cost(1 + (overuse + 1) * pres_fac);
				data.setAcc_cost(data.getAcc_cost() + overuse * acc_fac);
			}
		}	
	}
	
	@SuppressWarnings("unchecked")
	public void getAllHopsAndManhattanD(){
		//first check if routing is valid
		int err = 0;
		for(RoutableNode rn:this.rnodesCreated.values()){
			if(rn.overUsed() || rn.illegal()){
				err++;
			}
		}
		if (err == 0){
			System.out.println("\nNo errors found\n");
		}else if(err > 0){
			System.out.println("***** error " + err + " *****");
		}
		
		this.hops = 0;
		this.manhattanD = 0;
		Set<RoutableNode> netRNodes = new HashSet<>();
		for(Netplus net:this.nets){	
			for(Connection c:net.getConnection()){
				netRNodes.addAll((Collection<? extends RoutableNode>) c.rnodes);
				this.hops += c.rnodes.size() - 1;//hops for all sinks
			}
			for(RoutableNode rnode:netRNodes){
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
			
			/*if(this.debugRoutingCon) {
				this.printInfo("There are " + illegalTrees.size() + " illegal trees");
				for(Netplus np:illegalTrees){
					for(Connection con:np.getConnection()){
						System.out.println(con.toString());
						this.printConRNodes(con);
					}
					this.printInfo("");
				}
			}*/
			//find the illegal connections and fix illegal trees
			for(Netplus illegalTree:illegalTrees){
				if(this.debugRoutingCon) System.out.println("Net " + illegalTree.getNet().getName() + " routing tree is cyclic? ");
				
				boolean isCyclic = graphHelper.isCyclic(illegalTree);
				if(isCyclic){
					//remove cycles
					System.out.println("is cyclic");
//					for(Connection con:illegalTree.getConnection()){
//						System.out.println(con.toString());
//						this.printConRNodes(con);
//					}
					graphHelper.cutOffCycles(illegalTree);
				}else{
					if(this.debugRoutingCon) this.printInfo("fixing net: " + illegalTree.hashCode());
					this.handleNoCyclicIllegalRoutingTree(illegalTree);
				}
//				
			}
		}
	}
	
	public void handleNoCyclicIllegalRoutingTree(Netplus illegalTree){
		Routable illegalRNode;
		while((illegalRNode = illegalTree.getIllegalRNode()) != null){
//			if(this.debugRoutingCon) System.out.println("dealing rnode: " + illegalRNode.hashCode());
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
				if(add) newRouteNodes.add(newRouteNode);
			}
			
			/*if(this.debugRoutingCon){
				System.out.println("new rnodes: " + newRouteNodes.size());
				for(Routable r:newRouteNodes){
					System.out.println(r.hashCode());
				}
				
			}*/
			
			//Replace the path of each illegal connection with the path from the connection with maximum hops
			for(Connection illegalConnection : illegalCons) {
				this.ripup(illegalConnection);
				
				//Remove illegal path from routing tree
				while(!illegalConnection.rnodes.remove(illegalConnection.rnodes.size() - 1).equals(illegalRNode));
				
				/*if(this.debugRoutingCon){
					System.out.println("Con rnodes after removing: " + illegalConnection.rnodes.size());
					for(Routable rn:illegalConnection.rnodes){
						System.out.println(rn.hashCode());
					}
				}*/
				
				//Add new path to routing tree
				for(Routable newRouteNode : newRouteNodes) {
					illegalConnection.addRNode(newRouteNode);
				}
				
				/*if(this.debugRoutingCon){
					System.out.println("Con " + illegalConnection.id + " rnodes after adding: " + illegalConnection.rnodes.size());
					for(Routable rn:illegalConnection.rnodes){
						System.out.println("rnode id = " + rn.hashCode());
					}
				}*/
				
				this.add(illegalConnection);
				/*if(this.debugRoutingCon) {
					this.printInfo(illegalConnection.toString());
					this.printConRNodes(illegalConnection);
				}*/
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
				netPIPs.addAll(RouterHelper.conPIPs(c));
			}
			np.getNet().setPIPs(netPIPs);
		}
		
		this.checkPIPsUsage();
//		this.checkInvalidlyRoutedNets("LUT6_2_0/O5");
//		this.checkNetRoutedPins();
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
				/*Netplus<Node> np = con.getNet();
				if(congestedNets.containsKey(np)){
					congestedNets.put(np, congestedNets.get(np)+1);
				}else{
					congestedNets.put(np, 1);
				}*/
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
		this.prepareForRoutingACon(con);
		if(this.debugRoutingCon) this.printInfo("routing for " + con.toString());
		
		while(!this.targetReached(con)){
			
			RoutableNode rnode = (RoutableNode) queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			if(!rnode.childrenSet){
				this.rrgNodeId = rnode.setChildren(con, this.rrgNodeId, this.base_cost_fac, this.rnodesCreated, this.routethruHelper);
			}
			this.routerTimer.rnodesCreation.finish();
			
			this.exploringAndExpansion(rnode, con);
		}
		
		this.finishRoutingACon(con);
		
		this.printConRNodes(con);
	}
	
	public void printConRNodes(Connection con){
		if(this.debugRoutingCon){
//			for(Routable rn:con.rnodes){
			for(int i = con.rnodes.size() - 1; i >= 0; i-- ){
				Routable rn = con.rnodes.get(i);
				this.printInfo(((RoutableNode)(rn)).toString());
			}
			this.printInfo("");	
		}	
	}
	
	public void finishRoutingACon(Connection con){
		//save routing in connection class
		this.saveRouting(con);
		((RoutableNode)con.getSinkRNode()).target = false;
		// Reset path cost
		this.resetPathCost();
		
		this.add(con);
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
		this.nodesExpanded++;
		
		if(this.debugExpansion){
			this.printInfo("\t" + " exploring rnode " + rnode.toString());
		}
		if(this.debugExpansion) this.printInfo("\t starting  queue size: " + this.queue.size());
		for(RoutableNode childRNode:rnode.children){
			
			if(childRNode.isTarget()){		
				if(this.debugExpansion) this.printInfo("\t\t childRNode is the target");
				this.addNodeToQueue(rnode, childRNode, con);
				
			}else if(childRNode.type.equals(RoutableType.INTERRR)){
				//this can be done by downsizing the created rnodes
				if(childRNode.isInBoundingBoxLimit(con)){
					if(this.debugExpansion) this.printInfo("\t\t" + " add node to the queue");
					this.addNodeToQueue(rnode, childRNode, con);
					if(this.debugExpansion) this.printInfo("");
				}	
			}
		}
	}
	
	private void addNodeToQueue(RoutableNode rnode, RoutableNode childRNode, Connection con) {
		RoutableData data = childRNode.rnodeData;
		int countSourceUses = data.countSourceUses(con.source);
		if(this.debugExpansion){
			this.printInfo("\t\t childRNode " + childRNode.toString());
		}
		
		float partial_path_cost = rnode.rnodeData.getPartialPathCost();//upstream path cost
		float rnodeCost = this.getRouteNodeCost(childRNode, con, countSourceUses);
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
		
		this.addRNodeToQueue(childRNode, rnode, new_partial_path_cost, new_lower_bound_total_path_cost);
		
	}
	
	private void addRNodeToQueue(RoutableNode childRNode, RoutableNode rnode, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
		RoutableData data = childRNode.rnodeData;
		
		if(!data.isTouched()) {
			if(this.debugExpansion) this.printInfo("\t\t not touched");
			this.rnodesTouched.add(data);
			if(this.debugExpansion) this.printInfo("\t\t touched node size = "+this.rnodesTouched.size());
			data.setLowerBoundTotalPathCost(new_lower_bound_total_path_cost);
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.rnodeData.getLevel()+1);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
			if(this.debugExpansion) this.printInfo("\t\t node added, queue size = " + this.queue.size());
			
		} else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {
			//queue is sorted by lower bound total cost
			if(this.debugExpansion) this.printInfo("\t\t touched previously");
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.rnodeData.getLevel()+1);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
			if(this.debugExpansion) this.printInfo("\t\t node added, queue size = " + this.queue.size());
		}
	}
	
	private float getRouteNodeCost(RoutableNode rnode, Connection con, int countSourceUses) {
		RoutableData data = rnode.rnodeData;
		
		boolean containsSource = countSourceUses != 0;
		//Present congestion cost
		float pres_cost;
		if(containsSource) {
			int overoccupation = data.numUniqueSources() - Routable.capacity;
			if(overoccupation < 0) {
				pres_cost = 1;
			}else{
				pres_cost = 1 + overoccupation * this.pres_fac;
			}
		}else{
			pres_cost = data.getPres_cost();
		}
		
		//Bias cost
		float bias_cost = 0;
		if(rnode.type == RoutableType.INTERRR) {
			Netplus net = con.getNet();
			bias_cost = 0.5f * rnode.base_cost / net.fanout * 
					(Math.abs(rnode.getCenterX() - net.x_geo) + Math.abs(rnode.getCenterY() - net.y_geo)) / net.hpwl;
		}
		
		/*if(this.debugExpansion)
			this.printInfo("\t\t rnode cost = b(n)*h(n)*p(n)/(1+sourceUsage) = " + rnode.base_cost + " * " + data.getAcc_cost()+ " * " + pres_cost + " / (1 + " + countSourceUses + ") + " + bias_cost);
		*/
		return rnode.base_cost * data.getAcc_cost() * pres_cost / (1 + countSourceUses) + bias_cost;
	}
	
	private float expectMahatD(RoutableNode childRNode, Connection con){
		float md;
		if(this.itry == 1){
			md = Math.abs(childRNode.getCenterX() - con.sink.getTile().getColumn()) + Math.abs(childRNode.getCenterY() - con.sink.getTile().getRow());
		}else{
			md = Math.abs(childRNode.getCenterX() - con.getSinkRNode().getCenterX()) + Math.abs(childRNode.getCenterY() - con.getSinkRNode().getCenterY());
		}
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
		this.addRNodeToQueue(source, null, 0, 0);
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
