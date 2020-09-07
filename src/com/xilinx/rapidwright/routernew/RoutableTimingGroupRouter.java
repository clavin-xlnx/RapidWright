package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.ImmutableTimingGroup;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingModel;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorTable;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;

public class RoutableTimingGroupRouter{
	public Design design;
	public String dcpFileName;
	public int nrOfTrials;
	public CodePerfTracker t;
	
	public DelayEstimatorTable estimator;
	public TimingModel timingModel;
	public TimingGraph timingGraph;
	
	public List<Netplus> nets;
	public List<Connection> connections;
	public int fanout1Net;
	public int inet;
	public int icon;
	public int iNullEDIFNet;
	
	public RouteThruHelper routethruHelper;
	public Set<Node> reservedNodes;
	public PriorityQueue<QueueElement> queue;
	public Collection<RoutableData> rnodesTouched;
	public Map<Node, RoutableTimingGroup> rnodesCreated;//node and rnode pair	
	
	public List<Connection> sortedListOfConnection;
	public List<Netplus> sortedListOfNetplus;

	
	public RouterTimer routerTimer;
	public long iterationStart;
	public long iterationEnd;
	
	public int itry;
	public float pres_fac;
	public float initial_pres_fac; 
	public float pres_fac_mult; 
	public float acc_fac;
	public float base_cost_fac;
	public boolean timingDriven;
	public ImmutableTimingGroup sinkPinTG;
	
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
	public float averWire;
	public float averNode;
	
	public boolean trial = false;
	public boolean debugRoutingCon = false;
	public boolean debugExpansion = false;
	public boolean debugTiming = false;
	
	public RoutableTimingGroupRouter(Design design,
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
			boolean timingDriven){
		this.design = design;
		DesignTools.createMissingSitePinInsts(this.design);
		
		this.queue = new PriorityQueue<>(Comparators.PRIORITY_COMPARATOR);
		this.rnodesTouched = new ArrayList<>();
		
		TimingManager tm = new TimingManager(this.design, true);//slacks calculated
		this.timingModel = tm.getTimingModel();
		this.timingGraph = tm.getTimingGraph();
		
		if(timingDriven){
			Device device = Device.getDevice("xcvu3p-ffvc1517");
			InterconnectInfo ictInfo = new InterconnectInfo();
	        this.estimator = new DelayEstimatorTable(device,ictInfo, (short) 10, (short) 19, 0);
		}
		
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
		this.timingDriven = timingDriven;
		
		this.routerTimer = new RouterTimer();
		this.fanout1Net = 0;
		this.rrgNodeId = 0;
		this.rrgNodeId = this.initializeNetsCons(bbRange, this.base_cost_fac);
				
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfNetplus = new ArrayList<>();
		
		this.routethruHelper = new RouteThruHelper(this.design.getDevice());
		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesExpanded = 0;
		
		this.usedRNodes = new HashSet<>();
		this.overUsedRNodes = new HashSet<>();
		this.illegalRNodes = new HashSet<>();
	}
	
	public int initializeNetsCons(short bbRange, float base_cost_fac){
		this.inet = 0;
		this.icon = 0;
		this.iNullEDIFNet = 0;
		this.nets = new ArrayList<>();
		this.connections = new ArrayList<>();
		
		DesignTools.createMissingSitePinInsts(design);//TODO this is moved to TimingGraph for timing-driven version
		
		//source pin only for creating a timing group using a sitePinInst
		for(Net n:this.design.getNets()){
			
			if(n.isClockNet() || n.isStaticNet()){
				
				if(n.hasPIPs()){
					this.reservePipsOfNet(n);
				}else{
					this.reserveConnectedNodesOfNetPins(n);
				}
				
			}else if (n.getType().equals(NetType.WIRE)){
				
				if(this.isRegularNetToBeRouted(n)){
					if(!timingDriven) this.initializeNetAndCons(n, bbRange);
					else this.initializeNetAndCons(n, bbRange);//, timingDriven);//TODO debugging
					
				}else if(this.isOnePinTypeNetWithoutPips(n)){
					this.reserveConnectedNodesOfNetPins(n);
					
				} else if(this.isNetWithInputPinsAndPips(n)){
					this.reservePipsOfNet(n);
					
				}else{
					//internally routed within one site / nets without pins
					if(n.getPins().size() != 0) System.out.println(n.getName() + " " + n.getPins().size());
				}
			}else{
				System.out.println("UNKNOWN type net: " + n.toString());
			}
		}
		return rrgNodeId;
	}
	
	public void reservePipsOfNet(Net n){
		for(PIP pip:n.getPIPs()){
			Node nodeStart = pip.getStartNode();
			this.addReservedNode(nodeStart);
			Node nodeEnd = pip.getEndNode();
			this.addReservedNode(nodeEnd);
		}
	}
	
	public void reserveConnectedNodesOfNetPins(Net n){
		for(SitePinInst pin:n.getPins()){
			Node node = pin.getConnectedNode();
			this.addReservedNode(node);
		}
	}
	
	public void addReservedNode(Node node){
		this.reservedNodes.add(node);
	}
	
	public boolean isRegularNetToBeRouted(Net n){
		return n.getSource() != null && n.getSinkPins().size() > 0;
	}
	public void initializeNetAndCons(Net n, short bbRange){
		n.unroute();
		Netplus np = new Netplus(inet, bbRange, n);
		this.nets.add(np);
		inet++;
		
		SitePinInst source = n.getSource();
		RoutableTimingGroup sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.SOURCERR, this.timingModel, this.base_cost_fac);
		for(SitePinInst sink:n.getSinkPins()){
			if(RouterHelper.isExternalConnectionToCout(source, sink)){
				source = n.getAlternateSource();
				if(source == null){
					String errMsg = "net alterNative source is null: " + n.toStringFull();
					 throw new IllegalArgumentException(errMsg);
				}
				sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.SOURCERR, this.timingModel, this.base_cost_fac);
			}
			
			Connection c = new Connection(icon, source, sink);	
			c.setSourceRNode(sourceRNode);
			
			RoutableTimingGroup sinkRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, sink, RoutableType.SINKRR, this.timingModel, this.base_cost_fac);
			c.setSinkRNode(sinkRNode);
			this.connections.add(c);
			c.setNet(np);
			np.addCons(c);
			icon++;
		}
		if(n.getSinkPins().size() == 1) this.fanout1Net++;
	}
	
	public void initializeNetAndCons(Net n, short bbRange, boolean timingDriven){	
		EDIFNet edifNet = n.getLogicalNet();
		Map<SitePinInst, TimingVertex> spiAndTV = this.timingGraph.getSpiAndTimingVertex();
		if(edifNet != null){
			n.unroute();
			Netplus np = new Netplus(inet, bbRange, n);
			this.nets.add(np);
			inet++;			
			
			SitePinInst source = n.getSource();
			//TODO remove
			/*if(source.getName().contains("MUX")){
				
				if(n.getAlternateSource() == null){
					System.out.println(n.getName() + ", source " + source.toString() + ", null alternative source");
				}else{
					source = n.getAlternateSource();
					System.out.println(n.getName() + ", source " + source.toString());
				}
			}*/
			////
			RoutableTimingGroup sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.SOURCERR, this.timingModel, this.base_cost_fac);
			for(SitePinInst sink:n.getSinkPins()){
				if(RouterHelper.isExternalConnectionToCout(source, sink)){//|| n.getName().equals("ncda") || n.getName().equals("ncfe") || n.getName().equals("ncf8")
					source = n.getAlternateSource();
					if(source == null){
						String errMsg = "net alternative source is null: " + n.toStringFull();
						 throw new IllegalArgumentException(errMsg);
					}
					sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.SOURCERR, this.timingModel, this.base_cost_fac);
				}
				
				Connection c = new Connection(icon, source, sink);
				c.setSourceRNode(sourceRNode);
				
				RoutableTimingGroup sinkRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, sink, RoutableType.SINKRR, this.timingModel, this.base_cost_fac);
				c.setSinkRNode(sinkRNode);
				
				//set TimingVertex/Edge of connection c
				if(!spiAndTV.containsKey(source)){
					System.err.println("Map<SitePinInst, TimingVertex> from TimingGraph does not contains source " 
							+ source.getName() + " of net " + n.getName() + ", # sink pin = " + n.getSinkPins().size());
				}else{
					c.setSourceTimingVertex(spiAndTV.get(source));
				}
				
				if(!spiAndTV.containsKey(sink)){
					System.err.println("Map<SitePinInst, TimingVertex> from TimingGraph does not contains sink " 
							+ sink.getName() + " of net " + n.getName() + ", # sink pin = " + n.getSinkPins().size());
				}else{
					c.setSinkTimingVertex(spiAndTV.get(sink));
				}
				
				if(c.getSourceTimingVertex() != null && c.getSinkTimingVertex() != null){
					c.setTimingEdge(this.timingGraph, edifNet, n);//TODO bug fixing
				}else{
					System.err.println();
				}
				
				this.connections.add(c);
				c.setNet(np);
				np.addCons(c);
				icon++;
			}
			if(n.getSinkPins().size() == 1) this.fanout1Net++;
		}else{
			this.reservePipsOfNet(n);
			this.iNullEDIFNet++;//TODO should this happen?
		}
	}
	
	public boolean isOnePinTypeNetWithoutPips(Net n){
		return (n.getSource() != null && n.getPins().size() == 1) || (n.getSource() == null && n.getSinkPins().size() > 0 && n.hasPIPs() == false);
	}
	
	public boolean isNetWithInputPinsAndPips(Net n){
		return n.getSource() == null && n.getSinkPins().size() > 0 && n.hasPIPs() == true;
	}
	
	public RoutableTimingGroup createRoutableNodeAndAdd(int index, SitePinInst sitePinInst, RoutableType type, TimingModel model, float base_cost_fac){
		RoutableTimingGroup routableTG = new RoutableTimingGroup(index, sitePinInst, type, model);
		routableTG.setBaseCost(base_cost_fac);
//		System.out.println(routableTG.getTimingGroup().getSiblings()[0].hashCode() + " " + sitePinInst.getConnectedNode().hashCode());
		this.rnodesCreated.put(sitePinInst.getConnectedNode(), routableTG);
		this.rrgNodeId++;
		return routableTG;
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
		
		//sorted nets and connections
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfConnection.addAll(this.connections);
		Collections.sort(this.sortedListOfConnection, Comparators.FanoutBBConnection);	
		
		this.sortedListOfNetplus = new ArrayList<>();
		this.sortedListOfNetplus.addAll(this.nets);
		Collections.sort(this.sortedListOfNetplus, Comparators.FanoutNet);
		
		//initialize router
		this.initializeRouter(this.initial_pres_fac, this.pres_fac_mult, this.acc_fac);
				
		//do routing
		boolean validRouting;
		List<Netplus> trialNets = new ArrayList<>();
        for(Netplus net : this.sortedListOfNetplus){
        	if(net.getNet().getName().equals("n689")){
        		trialNets.add(net);
//        		System.out.println(net.getNet().getName());
        	}
        }
        List<Connection> trialCons = new ArrayList<>();
        for(Connection con : this.sortedListOfConnection){
        	if(con.id == 1216){
        		trialCons.add(con);
        	}
        }
        
		while(this.itry < this.nrOfTrials){
			this.iterationStart = System.nanoTime();
			//TODO TIMING GRAPH HERE
			this.connectionsRoutedIteration = 0;	
			validRouting = true;	
			if(this.trial) this.printInfo("iteration " + this.itry + " begins");
			
			if(!this.trial){
				for(Connection con:this.sortedListOfConnection){
					this.routingAndTimer(con);//TODO estimator called here
				}
			}else{
				for(Netplus np : trialNets){
					for(Connection con : np.getConnection()){
//				for(Connection con : trialCons){
//					System.out.println(( (RoutableTimingGroup)(con.getSinkRNode()) ).getTimingGroup().getSiblings().size());
//					System.out.println(( (RoutableTimingGroup)(con.getSinkRNode()) ).getTimingGroup().getSiblings().get(0).getNodes().size());
						this.routingAndTimer(con);
					}	
				}
			}
		
			//check if routing is valid
			validRouting = this.isValidRouting();
			
			//fix illegal routing trees if any
			if(validRouting){
				this.routerTimer.rerouteIllegal.start();
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
	
	public boolean isValidRouting(){
		for(RoutableTimingGroup rnode:this.rnodesCreated.values()){
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
		if (this.itry == 1) {
			this.pres_fac = this.initial_pres_fac;
		} else {
			this.pres_fac *= this.pres_fac_mult;
		}
		this.updateCost(this.pres_fac, this.acc_fac);
	}
	
	private void updateCost(float pres_fac, float acc_fac) {
		for(RoutableTimingGroup rnode:this.rnodesCreated.values()){
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
		for(RoutableTimingGroup rn:this.rnodesCreated.values()){
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
		Set<RoutableTimingGroup> netRNodes = new HashSet<>();
		for(Netplus net:this.nets){	
			for(Connection c:net.getConnection()){
				netRNodes.addAll((Collection<? extends RoutableTimingGroup>) c.rnodes);
				this.hops += c.rnodes.size() - 1;//hops for all sinks
			}
			for(RoutableTimingGroup rnode:netRNodes){
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
		Set<Integer> illegal = new HashSet<>();	
		for(Connection c:cons){
			for(Routable rn:c.rnodes){
				if(rn.illegal()){
					illegal.add(rn.hashCode());
				}
			}
		}	
		return illegal.size();	
	}
	
	public void fixIllegalTree(List<Connection> cons) {
//		this.printInfo("checking if there is any illegal node");	
		int numIllegal = this.getIllegalNumRNodes(cons);
		GraphHelper graphHelper = new GraphHelper();
		if(numIllegal > 0){
//			this.printInfo("There are " + numIllegal + " illegal routing tree nodes");
			
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
			
//			this.printInfo("There are " + illegalTrees.size() + " illegal trees");
			//find the illegal connections and fix illegal trees
			for(Netplus illegalTree : illegalTrees){
				boolean isCyclic = graphHelper.isCyclic(illegalTree);
				if(isCyclic){
					//remove cycles
					graphHelper.cutOffCycles(illegalTree);
				}else{
					this.handleNoCyclicIllegalRoutingTree(illegalTree);
				}
			}
		}
	}
	
	public void handleNoCyclicIllegalRoutingTree(Netplus illegalTree){
		Routable illegalRNode;
		while((illegalRNode = illegalTree.getIllegalRNode()) != null){
			List<Connection> illegalCons = new ArrayList<>();
			for(Connection con : illegalTree.getConnection()) {
				for(Routable rnode : con.rnodes) {
					if(rnode.equals(illegalRNode)) {
						illegalCons.add(con);
					}
				}
			}
			
			//fixing the illegal trees, since there is no criticality info, use the hops info
			//Find the illegal connection with maximum number of RNodes (hops)
			Connection maxCriticalConnection = illegalCons.get(0);
			for(Connection illegalConnection : illegalCons) {
				if(illegalConnection.rnodes.size() > maxCriticalConnection.rnodes.size()) {
					maxCriticalConnection = illegalConnection;
				}
			}
			
			//Get the path from the connection with maximum hops
			List<Routable> newRouteNodes = new ArrayList<>();
			boolean add = false;
			for(Routable newRouteNode : maxCriticalConnection.rnodes) {
				if(newRouteNode.equals(illegalRNode)) add = true;
				if(add) newRouteNodes.add(newRouteNode);
			}
			
			//Replace the path of each illegal connection with the path from the connection with maximum hops
			for(Connection illegalConnection : illegalCons) {
				this.ripup(illegalConnection);
				
				//Remove illegal path from routing tree
				while(!illegalConnection.rnodes.remove(illegalConnection.rnodes.size() - 1).equals(illegalRNode));
				
				//Add new path to routing tree
				for(Routable newRouteNode : newRouteNodes) {
					illegalConnection.addRNode(newRouteNode);
				}
				
				this.add(illegalConnection);
			}
			
		}
	}
	
	public void ripup(Connection con){
		RoutableTimingGroup parent = null;
		for(int i = con.rnodes.size() - 1; i >= 0; i--){
			RoutableTimingGroup rnode = (RoutableTimingGroup) con.rnodes.get(i);
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
		RoutableTimingGroup parent = null;
		for(int i = con.rnodes.size()-1; i >= 0; i--){
			RoutableTimingGroup rnode = (RoutableTimingGroup) con.rnodes.get(i);
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
		/*for(Netplus np:this.sortedListOfNetplus){
			Set<PIP> netPIPs = new HashSet<>();
			
			for(Connection c:np.getConnection()){
				netPIPs.addAll(this.conPIPs(c));
			}
			np.getNet().setPIPs(netPIPs);
		}
		
		this.checkPIPsUsage();*/
//		this.checkNetRoutedPins();
//		this.printWrittenPIPs();
		System.out.println("net n887: ");
		this.checkPIPsOfInvalidlyRoutedNet("n887");
		
		System.out.println("\nnet LUT6_2_0_16/O5: ");
		this.checkPIPsOfInvalidlyRoutedNet("LUT6_2_16/O5");
		
		System.out.println("\nn2fd: ");
		this.checkPIPsOfInvalidlyRoutedNet("n2fd");
		
		System.out.println("\nnet LUT6_2_0_14/O5: ");
		this.checkPIPsOfInvalidlyRoutedNet("LUT6_2_14/O5");
	}
	
	public void printWrittenPIPs(){
		for(Net net:this.design.getNets()){
			System.out.println(net.toStringFull());
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
	
	public void checkSiblingsOfInvalidlyRoutedNet(String netname){
		for(Netplus net:this.sortedListOfNetplus){
			if(net.getNet().getName().equals(netname)){
				for(Connection con:net.getConnection()){
					for(Routable rn:con.rnodes){
						Node exitNode = ((RoutableTimingGroup)rn).getSiblingsTimingGroup().getExitNode();
						System.out.println(exitNode.toString());
					}
				}
			}
		}
	}
	
	public void checkPIPsOfInvalidlyRoutedNet(String netname){
		boolean foundInRWrouter = false;
		for(Netplus net:this.sortedListOfNetplus){
			if(net.getNet().getName().equals(netname)){
				foundInRWrouter = true;
				
				for(Connection c: net.getConnection()){
//					System.out.println(((RoutableTimingGroup)c.getSinkRNode()).getSiblingsTimingGroup().toString() + " - " + c.sink.getName());
					for(PIP p:this.conPIPs(c)){
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
	
	public List<PIP> conPIPs(Connection con){
		List<PIP> conPIPs = new ArrayList<>();
/*		System.out.println(con.toString());
		for(int i = 0; i < con.rnodes.size(); i++){
			System.out.println(con.rnodes.get(i).toString());
		}*/
		List<Node> conNodes = new ArrayList<>();
		
		for(int i = 0; i < con.rnodes.size() - 1; i++){
			
			RoutableTimingGroup rtgFormer = ((RoutableTimingGroup) (con.rnodes.get(i + 1)));
			RoutableTimingGroup rtgLatter = ((RoutableTimingGroup) (con.rnodes.get(i)));
			
			//TODO bug fixing
			/*System.out.println(rtgFormer.type + ", " + rtgFormer.toString());
			System.out.println(rtgLatter.type + ", " + rtgLatter.toString());
			System.out.println("rtg latter size: " + rtgLatter.getSiblingsTimingGroup().getSiblings().length);*/
			ImmutableTimingGroup immu = null;
			if(rtgLatter.getSiblingsTimingGroup().getSiblings().length > 1){
				immu = this.findImmutableTimingGroup(rtgFormer, rtgLatter);
			}else{
				immu = rtgLatter.getSiblingsTimingGroup().getSiblings()[0];
			}
			
			this.checkImmuTGofNet(con, "n887", "LUT6_2_16/O5", immu);
			this.checkImmuTGofNet(con, "n2fd", "LUT6_2_14/O5", immu);
			
			/*if(immu == null){
				this.debuggingITG(rtgFormer.getSiblingsTimingGroup().getExitNode(), rtgLatter.getSiblingsTimingGroup().getExitNode());
			}*/
			
			conNodes.add(immu.exitNode());
			if(immu.entryNode() != null){
				conNodes.add(immu.entryNode());
			}
			
		}
		Node sourcePinNode = con.source.getConnectedNode();
		conNodes.add(sourcePinNode);
		
		for(int i = conNodes.size() - 1; i > 0; i--){
			Node nodeFormer = conNodes.get(i);
			Node nodeLatter = conNodes.get(i - 1);
			PIP pip = RouterHelper.findThePIPbetweenTwoNodes(nodeFormer.getAllWiresInNode(), nodeLatter);
			if(pip != null){
				conPIPs.add(pip);
			}else{
				System.err.println("Null PIP connecting node " + nodeFormer.toString() + " and node " + nodeLatter.toString());
			}
		}
		return conPIPs;
	}
	
	public void checkImmuTGofNet(Connection con, String net1, String net2, ImmutableTimingGroup immu){
		if(con.getNet().getNet().getName().equals(net1) || con.getNet().getNet().getName().equals(net2)){
			System.out.println(con.getNet().getNet().getName() + " Immu exit node " + immu.exitNode().toString() + ", entry node " + immu.entryNode().toString());
		}
	}
	
	public void debuggingITG(Node formerExitNode, Node latterExitNode){
		System.out.println("former exit node: " + formerExitNode.toString() + ", " 
							+ "latter exit node: " + latterExitNode.toString());
		for(Node next:formerExitNode.getAllDownhillNodes()){
			System.out.println("next of former exit node: " + next.toString());
			for(Node nextNext:latterExitNode.getAllDownhillNodes()){
				System.out.println("	next of next: " + nextNext.toString());
			}
		}
	}
	
	public Wire findEndWireOfNode(Wire[] wires, Tile tile){
		Wire w = null;
		for(Wire wire:wires){
			if(wire.getTile().equals(tile)){
				w = wire;
				break;
			}
		}
		return w;
	}
	
	public void findAverBaseCosts(){
		Set<Float> costs = new HashSet<>();
		float aver = 0;
		float sum = 0;
		for(RoutableTimingGroup rn:this.rnodesCreated.values()){
			sum += rn.base_cost;
			costs.add(rn.base_cost);
		}
		aver = sum/this.rnodesCreated.size();
		System.out.println(aver);
	}
	
	public void findCongestion(){
		for(RoutableTimingGroup rn : this.rnodesCreated.values()){
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
			System.out.println("net: " + con.getNet().getNet().getName());
			System.out.println("con: " + con.toString());
			throw new RuntimeException("Queue is empty: target unreachable?");
		}
	}

	public void routeACon(Connection con){
		this.prepareForRoutingACon(con);
		if(this.debugRoutingCon) this.printInfo("routing for " + con.toStringTG());
		
		if(this.debugRoutingCon) System.out.println("target set " + con.getSinkRNode().isTarget() + ", "
				+ ((RoutableTimingGroup) con.getSinkRNode()).getSiblingsTimingGroup().hashCode());
		
		while(!this.targetReached(con)){
			
			RoutableTimingGroup rnode = (RoutableTimingGroup) queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			if(!rnode.childrenSet){
//				System.out.println(this.rrgNodeId);
				this.rrgNodeId = rnode.setChildren(this.rrgNodeId, this.base_cost_fac, this.rnodesCreated, this.reservedNodes, this.routethruHelper);
			}
			this.routerTimer.rnodesCreation.finish();
			
			this.exploringAndExpansion(rnode, con);
		}
		
		this.finishRoutingACon(con);
		
		this.printConRNodes(con);
	}
	
	public void printConRNodes(Connection con){
		if(this.debugRoutingCon){
			for(Routable rn:con.rnodes){
				this.printInfo(((RoutableTimingGroup)(rn)).toString());
			}
			this.printInfo("");	
		}	
	}
	
	public void finishRoutingACon(Connection con){
		//save routing in connection class
		this.saveRouting(con);
		con.getSinkRNode().setTarget(false);
		// Reset path cost
		this.resetPathCost();
		
		this.add(con);
	}
	
	public void saveRouting(Connection con){
		RoutableTimingGroup rn = (RoutableTimingGroup) con.getSinkRNode();
		while (rn != null) {
			con.addRNode(rn);
			rn = (RoutableTimingGroup) rn.rnodeData.getPrev();
		}
	}

	public void resetPathCost() {
		for (RoutableData node : this.rnodesTouched) {
			node.setTouched(false);
			node.setLevel(0);
		}
		this.rnodesTouched.clear();	
	}
	
	public void exploringAndExpansion(RoutableTimingGroup rnode, Connection con){
		this.nodesExpanded++;
		
		if(this.debugExpansion){
			this.printInfo("\t" + " exploring rnode " + rnode.toString());
		}
		if(this.debugExpansion) this.printInfo("\t starting  queue size: " + this.queue.size());
		for(RoutableTimingGroup childRNode:rnode.children){
			if(this.debugExpansion) this.printInfo("run here and check RNode " + childRNode.toString());
			if(childRNode.isTarget()){		
				if(this.debugExpansion) this.printInfo("\t\t childRNode is the target");
				this.addNodeToQueue(rnode, childRNode, con);
				
			}else if(childRNode.type.equals(RoutableType.INTERRR)){
				if(childRNode.isInBoundingBoxLimit(con)){
					if(this.debugExpansion) this.printInfo("\t\t" + " add node to the queue");
					this.addNodeToQueue(rnode, childRNode, con);
					if(this.debugExpansion) this.printInfo("");
				}	
			}
		}
	}
	
	private void addNodeToQueue(RoutableTimingGroup rnode, RoutableTimingGroup childRNode, Connection con) {
		RoutableData data = childRNode.rnodeData;
		int countSourceUses = data.countSourceUses(con.source);
		if(this.debugExpansion){
			this.printInfo("\t\t childRNode " + childRNode.toString());
		}
		//TODO timing info
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
			if(this.timingDriven){
				ImmutableTimingGroup immuTG = null;
				if(childRNode.getSiblingsTimingGroup().getSiblings().length > 1){
					immuTG = this.findImmutableTimingGroup(rnode, childRNode);
				}else{
					immuTG = childRNode.getSiblingsTimingGroup().getSiblings()[0];
				}
				
				System.out.println("sinkPin ImmutableTimingGroup chosen with exit node: " + this.sinkPinTG.exitNode().toString());
				if(immuTG.entryNode() != null) 
					System.out.printf("Get min delay from ImmuTimingGroup ( " + immuTG.entryNode().toString() + " -> " + immuTG.exitNode().toString() + " )");
				else 
					System.out.printf("Get min delay from ImmuTimingGroup exit node only ( " + immuTG.exitNode().toString() + " )");
				
				if(this.sinkPinTG.entryNode() != null) 
					System.out.println(" to ( " + this.sinkPinTG.entryNode().toString() + " -> " + this.sinkPinTG.exitNode().toString() + " )");
				else
					System.out.println(" to ( " + this.sinkPinTG.exitNode().toString() + " )");
				
				short delay = this.estimator.getMinDelayToSinkPin(immuTG, this.sinkPinTG);
				System.out.println(" delay = " + delay);
				
				new_lower_bound_total_path_cost += delay;
			}
			
		}else{//lut input pin (sink)
			new_lower_bound_total_path_cost = new_partial_path_cost;
		}
		
		this.addRNodeToQueue(childRNode, rnode, new_partial_path_cost, new_lower_bound_total_path_cost);
		
	}
	
	private void addRNodeToQueue(RoutableTimingGroup childRNode, RoutableTimingGroup rnode, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
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
		}
	}
	
	private ImmutableTimingGroup findImmutableTimingGroup(RoutableTimingGroup rtgFirst, RoutableTimingGroup rtgSecond){
		Node exitofFirst = rtgFirst.getSiblingsTimingGroup().getExitNode();
		ImmutableTimingGroup[] immus = rtgSecond.getSiblingsTimingGroup().getSiblings();
		int length = immus.length;
		for(int i = 0; i < length; i++){
			Node entryOfSecond = immus[i].entryNode();
			if(this.twoNodesAbutted(exitofFirst, entryOfSecond)){
				return immus[i];
			}
		}
		System.err.println("Null ImmutableTimingGroup found connecting two SiblingsTimingGroup");
		return null;
	}
	
	private boolean twoNodesAbutted(Node parent, Node nodeToCheck){
		for(Node n:parent.getAllDownhillNodes()){
			if(n.equals(nodeToCheck))
				return true;
		}
		return false;
	}
	
	private float getRouteNodeCost(RoutableTimingGroup rnode, Connection con, int countSourceUses) {
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
	
	private float expectMahatD(RoutableTimingGroup childRNode, Connection con){
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
		con.getSinkRNode().setTarget(true);
		this.sinkPinTG = ((RoutableTimingGroup)con.getSinkRNode()).getSiblingsTimingGroup().getSiblings()[1];//TODO Choose one for the estimator
		
		// Add source to queue
		RoutableTimingGroup source = (RoutableTimingGroup) con.getSourceRNode();
		this.addRNodeToQueue(source, null, 0, 0);
	}
	
	public void checkAverageNumWires(){
		this.averWire = 0;
		this.averNode = 0;
		
		float sumWire = 0;
		float sumNodes = 0;
		float numTG = 0;
		
		for(RoutableTimingGroup rn:this.rnodesCreated.values()){
			ImmutableTimingGroup[] timingGroups = rn.getSiblingsTimingGroup().getSiblings();//TODO needed
			numTG += timingGroups.length;
			for(ImmutableTimingGroup tg:timingGroups){
				
				Node entryNode = tg.entryNode();
				Node exitNode = tg.exitNode();
				
				if(entryNode != null){
					sumNodes += 1;
					sumWire += entryNode.getAllWiresInNode().length;//not always 1
					/*if(entryNode.getAllWiresInNode().length != 1){
						System.out.println("ImmutabletTimingGroup, exit node:" + tg.exitNode().toString() + ", delay type: " + tg.delayType());
						System.out.println("entry node: " + entryNode.toString() + ", wires: " + entryNode.getAllWiresInNode().length);
						for(Wire wire:entryNode.getAllWiresInNode()){
							System.out.println("  wire tile: " + wire.getTile().getName() + ", name: " + wire.getWireName());
						}
						System.out.println("exit node: " + exitNode.toString() + ", wires: " + exitNode.getAllWiresInNode().length);
						for(Wire wire:exitNode.getAllWiresInNode()){
							System.out.println("  wire tile: " + wire.getTile().getName() + ", name: " + wire.getWireName());
						}
						System.out.println();
					}*/
				}
				//exit always exists
				sumNodes += 1;
				sumWire += exitNode.getAllWiresInNode().length;
			}
		}
		this.averWire = sumWire / numTG;
		this.averNode = sumNodes / numTG;
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
		System.out.println("Num con to be routed: " + this.connections.size());
		System.out.println("Num net to be routed: " + this.nets.size());
		System.out.println("Num 1-sink net: " + this.fanout1Net);
		System.out.println("Null EDIFNet: " + this.iNullEDIFNet);
		System.out.println("------------------------------------------------------------------------------");
	}
}
