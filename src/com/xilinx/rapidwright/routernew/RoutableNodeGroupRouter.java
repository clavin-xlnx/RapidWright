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

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.GroupDelayType;
import com.xilinx.rapidwright.timing.NodeGroup;
import com.xilinx.rapidwright.timing.EntryNode;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorTable;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.Pair;

public class RoutableNodeGroupRouter{
	public Design design;
	public float requiredTiming;
	public DelayEstimatorTable estimator;
	public TimingManager timingManager;
	private static final float MAX_CRITICALITY = 0.99f;
	private float REROUTE_CRITICALITY;
	private List<Connection> criticalConnections;
	public Pair<Float, TimingVertex> maxDelayAndTimingVertex;
	public Map<Node, Float> nodesDelays;
	
	public List<Netplus> nets;
	public List<Connection> connections;
	public int fanout1Net;
	public int inetToBeRouted;
	public int icon;
	public int iclockAndStaticNet;
	public int iWirePinsUnknown;
	public int iWireOneTypePin;
	public int iUnknownTypeNet;
	public int iNullEDIFNet;
	
	public RouteThruHelper rthHelper;
	public Set<Node> reservedNodes;
	public PriorityQueue<QueueElement> queue;
	public Collection<RoutableData> rnodesTouched;
	public Map<Node, RoutableNodeGroup> rnodesCreated;
	
	//map for solving congestion on entry nodes that are shared among siblings
	public Map<Connection, List<EntryNode>> connectionEntryNodes;
	
	public List<Connection> sortedListOfConnection;
	public List<Netplus> sortedListOfNetplus;
	public Map<TimingEdge, Connection> timingEdgeConnectionMap;
	
	public RouterTimer routerTimer;
	public long iterationStart;
	public long iterationEnd;
	
	public int itry;
	
	public float pres_fac;
	public float hist_fac;
	public Configuration config;
	public NodeGroup sinkPinNG;
	
	public int rrgNodeId;
	public int firstIterRNodes;
	
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
	public Set<EntryNode> usedEntryNodes;
	
	public long hops;
	public float manhattanD;
	public float averFanoutRNodes;
	public float averWire;
	public float averNodePerNodeGroup;
	public float averNodeGroupPerSiblings;
	public float averNodePerSiblings;
	public float firstRouting;
	public float firtRnodeT;
	
	public boolean trial = false;
	public boolean debugRoutingCon = false;
	public boolean debugExpansion = false;
	public boolean debugTiming = false;
	public boolean printRoutingSteps = false;
	
	public RoutableNodeGroupRouter(Design design,
			Configuration config){
		this.config = config;
		this.buildData();
		
		this.design = design;
		this.design.unrouteDesign();//global signal routing needed for a complete routing flow
		DesignTools.unrouteDualOutputSitePinRouting(this.design);//this is for removing the unmatched SitePinInst - TimingVertex issue for TimingGraph 
		DesignTools.createMissingSitePinInsts(this.design);
		
		this.requiredTiming = this.getDesignTimingReq() * 1000;
		
		if(this.config.isTimingDriven()) {
			this.timingManager = new TimingManager(this.design, true);	
			Device device = Device.getDevice("xcvu3p-ffvc1517");	
		    this.estimator = new DelayEstimatorTable(device, new InterconnectInfo(), config.isHpcRun());//DelayEstimatorTable<>(device,ictInfo, (short) 10, (short) 19, 0);
			
		}
		
		this.routerTimer = new RouterTimer();
		this.fanout1Net = 0;
		this.rrgNodeId = 0;
		this.rrgNodeId = this.initializeNetsCons(config.getBbRange());
			
		this.rthHelper = new RouteThruHelper(this.design.getDevice());
		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesExpanded = 0;
		this.nodesExpandedFirstIter = 0;
		this.nodesPopedFromQueue = 0;
		this.nodesPopedFromQueueFirstIter = 0;
		this.callingOfGetNextRoutable = 0;
			
	}
	
	public float getDesignTimingReq() {
		float treq = 0;
		List<String> constraints = this.design.getXDCConstraints(ConstraintGroup.LATE);
		for(String s : constraints) {
			if(s.contains("-period")) {
				int startIndex = s.indexOf("-period");
				String reqT = s.substring(startIndex+7, startIndex+13);
				treq = Float.valueOf(reqT);
			}
		}
		return treq;
	}
	
	public void buildData(){
		this.nets = new ArrayList<>();
		this.connections = new ArrayList<>();
		this.timingEdgeConnectionMap = new HashMap<>();
		
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfNetplus = new ArrayList<>();
		
		this.connectionEntryNodes = new HashMap<>();
		this.criticalConnections = new ArrayList<>();
		this.nodesDelays = new HashMap<>();
		
		this.reservedNodes = new HashSet<>();
		this.rnodesCreated = new HashMap<>();
		
		this.queue = new PriorityQueue<>(Comparators.PRIORITY_COMPARATOR);
		this.rnodesTouched = new ArrayList<>();
		
		this.usedRNodes = new HashSet<>();
		this.overUsedRNodes = new HashSet<>();
		this.illegalRNodes = new HashSet<>();
		this.usedEntryNodes = new HashSet<>();
		
	}
	
	public int initializeNetsCons(short bbRange){
		this.inetToBeRouted = 0;
		this.icon = 0;
		this.iNullEDIFNet = 0;
		
		for(Net n:this.design.getNets()){
			if(n.isClockNet()){
				this.reserveNet(n);
				this.iclockAndStaticNet++;
				
			}else if(n.isStaticNet()){
				this.reserveNet(n);//TODO static Net routing
				this.iclockAndStaticNet++;
				
			}else if (n.getType().equals(NetType.WIRE)){
				this.inetToBeRouted++;
				if(RouterHelper.isRoutableNetWithSourceSinks(n)){
					if(!config.isTimingDriven()) 
						this.initializeNetAndCons(n, bbRange);
					else 
						this.initializeNetAndCons(n, bbRange, config.isTimingDriven());
					
				}else if(RouterHelper.isDriverLessOrLoadLessNet(n)){
					this.reserveNet(n);
					this.iWireOneTypePin++;
					
				} else if(RouterHelper.isInternallyRoutedNets(n)){
					this.iWirePinsUnknown++;		
				}
			}else{
				System.out.println("UNKNOWN type net: " + n.toString());
			}
		}
		
		return rrgNodeId;
	}
	
	public void reserveNet(Net n){
		if(n.hasPIPs()){
			this.reservePipsOfNet(n);
		}else{
			this.reserveConnectedNodesOfNetPins(n);
		}
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
	
	public void initializeNetAndCons(Net n, short bbRange){
		n.unroute();
		Netplus np = new Netplus(inetToBeRouted, bbRange, n);
		this.nets.add(np);
		
		SitePinInst source = n.getSource();		
		RoutableNodeGroup sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.PINFEED_O);
		
		for(SitePinInst sink:n.getSinkPins()){
			if(RouterHelper.isExternalConnectionToCout(source, sink)){
				source = n.getAlternateSource();
				if(source == null){
					String errMsg = "net alternate source is null: " + n.toStringFull();
					 throw new IllegalArgumentException(errMsg);
				}
				sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.PINFEED_O);
			}
			
			Connection c = new Connection(icon, source, sink);	
			c.setSourceRNode(sourceRNode);
			
			RoutableNodeGroup sinkRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, sink, RoutableType.PINFEED_I);
			c.setSinkRNode(sinkRNode);
			this.connections.add(c);
			c.setNet(np);
			np.addCons(c);
			this.icon++;
		}
		if(n.getSinkPins().size() == 1) this.fanout1Net++;
	}
	
	public void initializeNetAndCons(Net n, short bbRange, boolean timingDriven){	
		EDIFNet edifNet = n.getLogicalNet();
		Map<Pair<SitePinInst, SitePinInst>, List<TimingEdge>> spiAndTimingEdges = this.timingManager.getSpiAndTimingEdgesMap();		
		
		if(edifNet != null){
			n.unroute();
			Netplus np = new Netplus(inetToBeRouted, bbRange, n);
			this.nets.add(np);		
			
			SitePinInst source = n.getSource();
			
			RoutableNodeGroup sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.PINFEED_O);
			for(SitePinInst sink:n.getSinkPins()){
				if(RouterHelper.isExternalConnectionToCout(source, sink)){//|| n.getName().equals("ncda") || n.getName().equals("ncfe") || n.getName().equals("ncf8")
					source = n.getAlternateSource();
					if(source == null){
						String errMsg = "net alternative source is null: " + n.toStringFull();
						 throw new IllegalArgumentException(errMsg);
					}
					sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.PINFEED_O);
				}
				
				Connection c = new Connection(icon, source, sink);
				c.setSourceRNode(sourceRNode);
				
				RoutableNodeGroup sinkRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, sink, RoutableType.PINFEED_I);
				c.setSinkRNode(sinkRNode);
				
				//set TimingEdge of connection			
				try{
					c.setTimingEdge(spiAndTimingEdges.get(new Pair<>(source, sink)));
				}catch(Exception e){
					System.out.println(c.toString());
					e.printStackTrace();
				}
				
				this.connections.add(c);
				c.setNet(np);
				try {
					for(TimingEdge e : c.timingEdges){//TODO NPE for BRAMs
						this.timingEdgeConnectionMap.put(e, c); // for timing info
					}
				}catch(Exception e) {
					System.out.println(n.toStringFull());
					System.out.println();
				}
				np.addCons(c);
				this.icon++;
				
			}
			if(n.getSinkPins().size() == 1) this.fanout1Net++;
		}else{
			this.reservePipsOfNet(n);
			this.iNullEDIFNet++;//this should not happen, already fixed, LUT6_2
			System.err.println("null EDIFNet for Net " + n.getName());
		}
	}
	
	public RoutableNodeGroup createRoutableNodeAndAdd(int index, SitePinInst sitePinInst, RoutableType type){
		RoutableNodeGroup routableNG = new RoutableNodeGroup(index, sitePinInst, type, this.estimator);
		this.rnodesCreated.put(routableNG.getNode(), routableNG);
		this.rrgNodeId++;
		return routableNG;
	}
	
	public void getTotalNodesInResourceGraph(){
		Set<Node> totalNodes = new HashSet<>();
		
		for(RoutableNodeGroup rng:this.rnodesCreated.values()){
			for(NodeGroup nodeGroup:rng.getNodeGroupSiblings().getSiblings()){
				if(nodeGroup.entryNode() != null){
					totalNodes.add(nodeGroup.entryNode());
				}
			}
			totalNodes.add(rng.getNode());
		}
		System.out.println("total nodes involved in the rngs: " + totalNodes.size());
	}

	public void initializeRouter(){
		this.rnodesTouched.clear();
    	this.queue.clear();
    	
		//routing schedule
    	this.hist_fac = config.getAcc_fac();
    	this.pres_fac = config.getInitial_pres_fac();
		this.itry = 1;
		
		System.out.printf("-----------------------------------------------------------------------------------------------------------------------------------------------------------------------\n");
        System.out.printf("%9s  %11s  %12s  %12s  %15s  %11s  %7s  %8s  %15s  %17s  %19s  %9s\n", 
        		"Iteration", 
        		"Conn routed", 
        		"Run Time (s)", 
        		"Total RNodes", 
        		"RNodes Tacc (s)",
        		"Used RNodes",
        		"Illegal",
        		"Overused", 
        		"Used EntryNodes",
        		"OverUsedEntryNode",
        		"MultiFaninEntryNode",
        		"Max delay");
        System.out.printf("---------  -----------  ------------  ------------  ---------------  -----------  -------  --------  ---------------  -----------------  -------------------  ---------\n");
	}
	
	public int overUsedEntryNodes(){
		int overUsedEntryNodes = 0;
		for(EntryNode n : RoutableNodeGroup.entryNodesExpanded){
			if(n != null && n.isOverUsed()){
				overUsedEntryNodes++;
			}
		}
		return overUsedEntryNodes;
	}
	
	public int multiFaninEntryNodes(){
		int illegalEntryNodes = 0;
		for(EntryNode n : RoutableNodeGroup.entryNodesExpanded){
			if(n != null && n.hasMultiFanin()){
				illegalEntryNodes++;
			}
		}
		return illegalEntryNodes;
	}
	
	public void printConEntryNodes(Connection con){
		System.out.println(con.toString());
		for(Node n:this.connectionEntryNodes.get(con)){
			if(n != null){
				System.out.printf(n.toString() + ", ");
			}
		}
		System.out.println();
	}
	
	long intableCall = 0;
	long outtableCall = 0;
	long pinbounce = 0;
	long pinfeed = 0;
	public void estimateDelayOfConnections() {
		for(Netplus np : this.nets) {
			RoutableNodeGroup source = (RoutableNodeGroup) (np.getConnection().get(0).getSourceRNode());
			this.tracingSetChildren(source);
			for(Connection con : np.getConnection()) {
				NodeGroup sinknG = ((RoutableNodeGroup) con.getSinkRNode()).getNodeGroupSiblings().getSiblings()[0];

				short estConDelay = Short.MAX_VALUE;
				for(Pair<RoutableNodeGroup, NodeGroup> child : source.childrenAndThruGroup) {
					try{
						short tmpConDelay = this.estimator.getMinDelayToSinkPin(child.getSecond(), sinknG);
						tmpConDelay += child.getSecond().getDelay();
						if(tmpConDelay < estConDelay) {
							estConDelay = tmpConDelay;
						}
					}catch(Exception e) {
						System.out.println("   from " + child.getSecond() + " \n   to   " + sinknG);
					}
				}
				estConDelay += source.getDelay();
				con.setTimingEdgeDelay(estConDelay);
			}
		}
		
		intableCall = this.estimator.intableQuery;
		outtableCall = this.estimator.outOfTableQuery;
		pinbounce = this.estimator.pinbounceQuery;
		pinfeed = this.estimator.pinfeedQuery;
	}
	
	public int routingRuntime(){
		long start = System.nanoTime();
		if(config.isTimingDriven()) {
			this.estimateDelayOfConnections();
			this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireAndSlack();
			this.timingManager.calculateCriticality(this.connections, MAX_CRITICALITY, this.config.getCriticalityExp(), maxDelayAndTimingVertex.getFirst().floatValue());
		
			System.out.println(String.format("pre-routing max delay estimated: %3.2f", maxDelayAndTimingVertex.getFirst()));
		}
		this.route();
		long end = System.nanoTime();
		int timeInMilliseconds = (int)Math.round((end-start) * Math.pow(10, -6));
//		this.getNodeGroupTypeAndDelayMap();
		return timeInMilliseconds;
	}
	
	private void setRerouteCriticality(List<Connection> connections) {
    	//Limit number of critical connections
    	REROUTE_CRITICALITY = this.config.getMinRerouteCriti();
    	this.criticalConnections.clear();
    	
    	int maxNumberOfCriticalConnections = (int) (this.connections.size() * 0.01 * this.config.getRerouteCritiPercentage());
    	
    	for(Connection con : connections) {
    		if(con.getCriticality() > REROUTE_CRITICALITY) {
    			this.criticalConnections.add(con);
    		}
    	}
    	
    	if(this.criticalConnections.size() > maxNumberOfCriticalConnections) {
    		Collections.sort(this.criticalConnections, Comparators.ConnectionCriticality);
    		REROUTE_CRITICALITY = this.criticalConnections.get(maxNumberOfCriticalConnections).getCriticality();
    	}
    }
	
	public void tracingSetChildren(RoutableNodeGroup rng) {
		if(!rng.childrenSet) {
			Pair<Integer, Long> countPair = rng.setChildren(this.rrgNodeId, this.rnodesCreated, 
					this.reservedNodes, this.rthHelper, config.isTimingDriven(), this.estimator, this.callingOfGetNextRoutable);
			this.rrgNodeId = countPair.getFirst();
			this.callingOfGetNextRoutable = countPair.getSecond();
		}
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
		this.initializeRouter();
		//do routing
		boolean validRouting;
        
		while(this.itry < config.getNrOfTrials()){
			this.iterationStart = System.nanoTime();
			this.connectionsRoutedIteration = 0;	
			validRouting = true;	
			
			if(this.config.isTimingDriven()) this.setRerouteCriticality(this.sortedListOfConnection);
			
			for(Connection con : this.sortedListOfConnection){		
					this.routingAndTimer(con);
			}
			
			if(this.itry == 1){
				this.nodesExpandedFirstIter = this.nodesExpanded;
				this.nodesPopedFromQueueFirstIter = this.nodesPopedFromQueue;
			}
			
			//check if routing is valid
			if(printRoutingSteps) this.printInfo("check if routing is valid");
			validRouting = this.isValidRouting() && this.validEntryNodesRouting();
			
			//fix illegal routing trees if any
			List<Netplus> illegalTrees = null;
			if(validRouting){
				this.routerTimer.rerouteIllegal.start();
				
				if(printRoutingSteps) this.printInfo("valid routing");
				
				this.assignNodesToEachConnection();
				illegalTrees = this.fixIllegalTree(sortedListOfConnection);
				
				this.routerTimer.rerouteIllegal.finish();
			}	
			
			//update timing and criticalities of connections
			if(config.isTimingDriven()) {
				this.routerTimer.updateTiming.start();
				if(illegalTrees == null){
					this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireAndSlack();
					this.timingManager.calculateCriticality(this.sortedListOfConnection, 
							MAX_CRITICALITY, this.config.getCriticalityExp(), maxDelayAndTimingVertex.getFirst().floatValue());
					
				}else{
					this.timingManager.updateIllegalNetsDelays(illegalTrees, this.nodesDelays);
					this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireAndSlack();
					this.timingManager.calculateCriticality(this.sortedListOfConnection, 
							MAX_CRITICALITY, this.config.getCriticalityExp(), maxDelayAndTimingVertex.getFirst().floatValue());

				}
				this.routerTimer.updateTiming.finish();
			}
			
			this.iterationEnd = System.nanoTime();
			
			//statistics
			if(printRoutingSteps) this.printInfo("statistics");
			this.routerTimer.calculateStatistics.start();
			this.iterationStatistics(this.sortedListOfConnection);
			this.routerTimer.calculateStatistics.finish();
			
			if(this.config.isTimingDriven()) {
				if(this.shouldSwitchToCongestionDriven()) {
					this.config.setTimingDriven(false);
					this.REROUTE_CRITICALITY = 1f;
				}
			}
			
			//if the routing is valid, return the routing completed successfully
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
		}
		
		if (this.itry == config.getNrOfTrials() + 1) {
			System.out.println("Routing terminated after " + this.itry + " trials!");
		}
		
		return;
	}
	
	public boolean shouldSwitchToCongestionDriven() {
		if(this.itry == 5) {
			float switchThreshold = 0.95f * this.requiredTiming;
			if(maxDelayAndTimingVertex.getFirst().floatValue() > switchThreshold)
				return true;
		}
		if(this.itry >= 10) {
			float switchThreshold = 1.2f * this.requiredTiming;
			if(maxDelayAndTimingVertex.getFirst().floatValue() > switchThreshold)
				return true;
		}
		
		return false;
	}
	
	public void assignNodesToEachConnection() {
		for(Connection con:this.sortedListOfConnection){
			con.newNodes();
			int irn = 0;
			for(Routable rn:con.rnodes){
				con.addNode(rn.getNode());	
				Node entry = this.connectionEntryNodes.get(con).get(irn);
				if(entry != null){
					con.addNode(entry);	
				}
				irn++;
			}
		}
	}
	
	public void routingAndTimer(Connection con){
		if(this.itry == 1){
			this.routerTimer.firstIteration.start();
			this.routeACon(con);
			this.routerTimer.firstIteration.finish();
			
		}else if(con.congested() || this.conEntryNodeCongested(con)){			
			this.routerTimer.rerouteCongestion.start();
			this.routeACon(con);
			this.routerTimer.rerouteCongestion.finish();
			
		}
		else if (con.getCriticality() > REROUTE_CRITICALITY) {
			this.routerTimer.rerouteCriticalCon.start();
			this.routeACon(con);
			this.routerTimer.rerouteCriticalCon.finish();
		}
	}
	
	public boolean conEntryNodeCongested(Connection con){
		boolean congested = false;
		for(EntryNode n : this.connectionEntryNodes.get(con)){
			if(n != null){
				if(n.isOverUsed()){
					congested = true;
				}
			}
		}
		return congested;
	}
	
	public boolean isValidRouting(){
		for(RoutableNodeGroup rnode:this.rnodesCreated.values()){
			if(rnode.overUsed()){
				return false;
			}
		}
		return true;
	}
	
	public boolean validEntryNodesRouting(){
		boolean validEntryNodeRouting = true;
		for(EntryNode n : RoutableNodeGroup.entryNodesExpanded){
			if(n != null && n.isOverUsed()){
				validEntryNodeRouting = false;
			}
		}
		return validEntryNodeRouting;
	}
	
	/**
	 * statistics output for each router iteration
	 */
	public void iterationStatistics(List<Connection> allCons) {
		
		this.statisticsInfo(allCons, this.iterationStart, this.iterationEnd, 
							this.rrgNodeId, this.routerTimer.rnodesCreation.getTime());
	}
	public void statisticsInfo(List<Connection> connections, 
			long iterStart, long iterEnd,
			int globalRNodeId, long rnodesT){
		if(this.itry == 1){
			this.firstIterRNodes = this.rnodesCreated.size();
			this.firstRouting = (float) ((iterEnd - iterStart - rnodesT)*1e-9);
			this.firtRnodeT = (float) (this.routerTimer.rnodesCreation.getTime() * 1e-9);
		}
		this.getOverusedAndIllegalRNodesInfo(connections);
		
		int overUsed = this.overUsedRNodes.size();
		int illegal = this.illegalRNodes.size();
		System.out.printf("%9d  %11d  %12.2f  %12d  %15.2f  %11d  %7d  %8d  %15d  %17d  %19d  %9.1f\n", 
				this.itry,
				this.connectionsRoutedIteration,
				(iterEnd - iterStart)*1e-9,
				globalRNodeId,
				rnodesT*1e-9,
				this.usedRNodes.size(),
				illegal,
				overUsed,
				this.usedEntryNodes.size(),
				this.overUsedEntryNodes(),
				this.multiFaninEntryNodes(),
				config.isTimingDriven()?this.maxDelayAndTimingVertex.getFirst():null);
	}
	
	public void updateCostFactors(){
		if (this.itry == 1) {
			this.pres_fac = config.getInitial_pres_fac();
		} else {
			this.pres_fac *= config.getPres_fac_mult();
		}
		this.updateCost(this.pres_fac, this.hist_fac);
	}
	
	//entryNode over usage should also be considered here
	//similar update for every entry node
	private void updateCost(float pres_fac, float acc_fac) {
		for(RoutableNodeGroup rnode:this.rnodesCreated.values()){
			int overuse = rnode.getOccupancy() - Routable.capacity;
			//Present congestion penalty
			if(overuse == 0){
				rnode.setPres_cost(1 + pres_fac);
			} else if (overuse > 0){
				rnode.setPres_cost(1 + (overuse + 1) * pres_fac);
				rnode.setAcc_cost(rnode.getAcc_cost() + overuse * acc_fac);
			}
		}
		
		//update costs of entry nodes
		RoutableNodeGroup.updateEntryNodesCosts(pres_fac, acc_fac);
	}
	
	@SuppressWarnings("unchecked")
	public void getAllHopsAndManhattanD(){
		//first check if routing is valid
		int err = 0;
		for(RoutableNodeGroup rn:this.rnodesCreated.values()){
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
		Set<Routable> netRNodes = new HashSet<>();
		for(Netplus net:this.nets){	
			for(Connection c:net.getConnection()){
				if(c.rnodes != null) {
					netRNodes.addAll(c.rnodes);
					this.hops += c.rnodes.size() - 1;//hops counted using the number of nodes for all sinks
				}
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
		this.usedEntryNodes.clear();
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
			
			for(EntryNode entry:this.connectionEntryNodes.get(conn)){
				if(entry != null){
					this.usedEntryNodes.add(entry);//hashCode is not unique
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
	
	public List<Netplus> fixIllegalTree(List<Connection> cons){	
		List<Netplus> illegalTrees = new ArrayList<>();
		int numIllegal = this.getIllegalNumRNodes(cons);
		GraphHelper graphHelper = new GraphHelper();
		if(numIllegal > 0){		
			
			for(Netplus net : this.nets) {
				boolean illegal = false;
				for(Connection con : net.getConnection()) {
					if(con.illegal() || illegalConOnEntryNode(con)) {
						illegal = true;
					}
				}
				if(illegal) {
					illegalTrees.add(net);
					this.addNodesDelays(net);
				}
			}
			
			//find the illegal connections and fix illegal trees
			for(Netplus illegalTree : illegalTrees){		
				//for getting statistical info clean in the last iteration
				for(Connection c:illegalTree.getConnection()){
					this.ripup(c);
				}
				
				boolean isCyclic = graphHelper.isCyclic(illegalTree);
				if(isCyclic){
					//remove cycles
					graphHelper.cutOffIllegalEdges(illegalTree, true);//clean version (update to router fields)
				}else{
					graphHelper.cutOffIllegalEdges(illegalTree, false);
				}
				
			}
		}
		return illegalTrees;
	}
	
	public void addNodesDelays(Netplus n){	
		for(Connection c:n.getConnection()){
			for(Routable group : c.rnodes){
				nodesDelays.put(group.getNode(), group.getDelay());
			}
			for(Node entry:this.connectionEntryNodes.get(c)){
				nodesDelays.put(entry, 0f);
			}
		}
	}
	
	public boolean illegalConOnEntryNode(Connection con){
		boolean illegal = false;
		for(EntryNode entry:this.connectionEntryNodes.get(con)){
			if(entry != null && entry.hasMultiFanin()){
				return true;
			}
		}
		return illegal;
	}
	
	public void ripup(Connection con){
		RoutableNodeGroup parent = null;	
		for(int i = con.rnodes.size() - 1; i >= 0; i--){
			RoutableNodeGroup rnode = (RoutableNodeGroup) con.rnodes.get(i);
			RoutableData rNodeData = rnode.getRoutableData();
			
			rNodeData.removeSource(con.source);
			
			//remove sources of entry nodes
			//moving out from ripup will require one more traversal of the con.rnodes
			EntryNode thruEntryNode = this.removeEntryNodeSource(rnode, con, i);			
			if(parent == null){
				parent = rnode;
			}else{
				rNodeData.removeParent(parent);
				
				//remove parents of entry nodes
				if(thruEntryNode != null){
					this.removeEntryNodeParent(thruEntryNode, parent);
				}
				
				parent = rnode;
			}
			// Calculation of present congestion penalty
			rnode.updatePresentCongestionPenalty(this.pres_fac);
			//update present congestion penalty of entry nodes
			if(thruEntryNode != null){
				RoutableNodeGroup.updatePresentCongestionPenaltyOfEntryNode(thruEntryNode, this.pres_fac);
			}
		}
	}
	
	public void add(Connection con){
		RoutableNodeGroup parent = null;
		
		for(int i = con.rnodes.size()-1; i >= 0; i--){
			//in the order of from sourcerr to sinkrr
			RoutableNodeGroup rnode = (RoutableNodeGroup) con.rnodes.get(i);
			RoutableData rNodeData = rnode.getRoutableData();
			
			rNodeData.addSource(con.source);
			
			//add sources of entry nodes
			EntryNode thruEntryNode = this.addEntryNodeSource(rnode, con, i);			
			if(parent == null){
				parent = rnode;
			}else{
				rNodeData.addParent(parent);
				
				//add parents of entry nodes
				if(thruEntryNode != null){
					this.addEntryNodeParent(thruEntryNode, parent);
				}
				
				parent = rnode;
			}
			// Calculation of present congestion penalty
			//for an entire NodeGroupSiblings, not bothering intersection entry nodes
			rnode.updatePresentCongestionPenalty(this.pres_fac);		
			//update present cogestion of entry node
			if(thruEntryNode != null){
				RoutableNodeGroup.updatePresentCongestionPenaltyOfEntryNode(thruEntryNode, this.pres_fac);
			}		
		}
	}
	
	public EntryNode addEntryNodeSource(RoutableNodeGroup rnode, Connection con, int i){
		//thruNodeGroup will never be null for non-source resources
		if(rnode.getRoutableType() != RoutableType.PINFEED_O){
			EntryNode thruEntryNode = this.connectionEntryNodes.get(con).get(i);
			if(thruEntryNode != null){
				thruEntryNode.addSource(con.source);
				RoutableNodeGroup.entryNodesExpanded.add(thruEntryNode);
			}
			return thruEntryNode;
		}
		return null;
	}
	
	//adding parent needed for checking if there is any entry node that has multiple fanin
	public void addEntryNodeParent(EntryNode thruEntryNode, RoutableNodeGroup parent){
		if(thruEntryNode != null){	
			thruEntryNode.addParent(parent);
			RoutableNodeGroup.entryNodesExpanded.add(thruEntryNode);
		}
			
	}
	
	public EntryNode removeEntryNodeSource(RoutableNodeGroup rnode, Connection con, int i){
		if(rnode.getRoutableType() != RoutableType.PINFEED_O){//SOURCERR does not have a thruNodeGroup (null)
			EntryNode thruEntryNode = this.connectionEntryNodes.get(con).get(i);//thruNodeGroup that should not be used here,
			//because it might be changed during the routing process of other connections that use the same rnode
			if(thruEntryNode != null){
				thruEntryNode.removeSource(con.source);
				RoutableNodeGroup.entryNodesExpanded.add(thruEntryNode);
			}
			return thruEntryNode;
		}
		return null;
	}
	
	public void removeEntryNodeParent(EntryNode thruEntryNode, RoutableNodeGroup parent){
		if(thruEntryNode != null){
			thruEntryNode.removeParent(parent);
			RoutableNodeGroup.entryNodesExpanded.add(thruEntryNode);
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
	
	/**
	 * these check methods are used for debugging
	 * @param netname
	 */
	public void checkSiblingsOfInvalidlyRoutedNet(String netname){
		for(Netplus net:this.sortedListOfNetplus){
			if(net.getNet().getName().equals(netname)){
				for(Connection con:net.getConnection()){
					for(Routable rn:con.rnodes){
						Node exitNode = ((RoutableNodeGroup)rn).getNodeGroupSiblings().getExitNode();
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
					System.out.println(c.toString());
					this.printConRNodes(c);
					System.out.println(this.connectionEntryNodes.get(c));
					for(PIP p:RouterHelper.conPIPs(c.nodes)){
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
	
	public void checkNodeGroupOfNet(Connection con, String net1, String net2, NodeGroup nodeGroup){
		if(con.getNet().getNet().getName().equals(net1) || con.getNet().getNet().getName().equals(net2)){
			System.out.println(con.getNet().getNet().getName() + nodeGroup.toString());
		}
	}
	
	public void debuggingNG(Node formerExitNode, Node latterExitNode){
		System.out.println("former exit node: " + formerExitNode.toString() + ", " 
							+ "latter exit node: " + latterExitNode.toString());
		for(Node next:formerExitNode.getAllDownhillNodes()){
			System.out.println("next of former exit node: " + next.toString());
			for(Node nextNext:latterExitNode.getAllDownhillNodes()){
				System.out.println("	next of next: " + nextNext.toString());
			}
		}
	}
	
	public void findAverBaseCosts(){
		Set<Float> costs = new HashSet<>();
		float aver = 0;
		float sum = 0;
		for(RoutableNodeGroup rn:this.rnodesCreated.values()){
			sum += rn.getBase_cost();
			costs.add(rn.getBase_cost());
		}
		aver = sum/this.rnodesCreated.size();
		System.out.println(aver);
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
		while(!this.targetReached(con)){
			
			RoutableNodeGroup rnode = (RoutableNodeGroup) queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			this.tracingSetChildren(rnode);
			this.routerTimer.rnodesCreation.finish();
			
			this.exploringAndExpansion(rnode, con);
		}
		
		this.finishRoutingACon(con);
		
		if(config.isTimingDriven()) this.updateConRouteDelay(con);
//		this.printConRNodes(con);
	}
	
	public void updateConRouteDelay(Connection con){
		con.updateRouteDelay();
	}
	
	public void printConRNodes(Connection con){
		this.printInfo(con.toString());
		for(int i = con.getNodeGroups().size() - 1; i >= 0; i--) {
			this.printInfo(con.getNodeGroups().get(i).toString());
		}
		this.printInfo("");	
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
		RoutableNodeGroup rn = (RoutableNodeGroup) con.getSinkRNode();
		List<EntryNode> entryNodes;
		if(!this.connectionEntryNodes.containsKey(con)){
			entryNodes = new ArrayList<>();
		}else{
			entryNodes = this.connectionEntryNodes.get(con);
			entryNodes.clear();
		}
		while (rn != null) {
			con.addRNode(rn);
			con.addNodeGroup(rn.getThruNodeGroup());
			entryNodes.add(rn.getThruNodeGroup().entryNode());
			rn = (RoutableNodeGroup) rn.getRoutableData().getPrev();
		}
		this.connectionEntryNodes.put(con, entryNodes);
	}

	public void resetPathCost() {
		for (RoutableData node : this.rnodesTouched) {
			node.setTouched(false);
			node.setLevel(0);
		}
		this.rnodesTouched.clear();	
	}
	
	public void exploringAndExpansion(RoutableNodeGroup rnode, Connection con){
		this.nodesPopedFromQueue++;
		
		for(Pair<RoutableNodeGroup, NodeGroup> childRNode : rnode.childrenAndThruGroup){
			RoutableNodeGroup child = childRNode.getFirst();
			NodeGroup thruNodeGroup = childRNode.getSecond();
			
			if(child.getRoutableType() == RoutableType.WIRE){
				if(child.isInBoundingBoxLimit(con)){
					this.addNodeToQueue(rnode, child, thruNodeGroup, con);
					this.nodesExpanded++;
					
				}
			}else if(child.getRoutableType() == RoutableType.PINBOUNCE) {
				if(child.isInBoundingBoxLimit(con)){
					if(this.usablePINBounce(child, con.getSinkRNode())){
						this.addNodeToQueue(rnode, child, thruNodeGroup, con);
						this.nodesExpanded++;
					}
				}
			}else if(child.isTarget()){	
				this.addNodeToQueue(rnode, child, thruNodeGroup, con);
				this.nodesExpanded++;
			}
		}
	}
	
	private boolean usablePINBounce(RoutableNodeGroup childGroup, Routable sinkPinNG){
		// which part of the node should be used to define the column and row? base INT tile? ending INT tile?
		
		int columnChild = childGroup.getNode().getTile().getColumn();
		int rowChild = childGroup.getNode().getTile().getRow();
		int columnSink = sinkPinNG.getNode().getTile().getColumn();//TODO use tile coordinate of entry node (which one of the siblings?)
		int rowSink = sinkPinNG.getNode().getTile().getRow();
		if(columnChild == columnSink && Math.abs(rowChild - rowSink) <= 1){
			return true;
		}
		
		return false;
	}
	
	long callDelayEstimator = 0;
	long noCallOfDelayEstimator = 0;
	
	private void addNodeToQueue(RoutableNodeGroup rnode, RoutableNodeGroup childRNode, NodeGroup thruNodeGroup, Connection con) {
		RoutableData data = childRNode.getRoutableData();
		int countSourceUses = data.countSourceUses(con.source);		
		float partial_path_cost = rnode.getRoutableData().getPartialPathCost();//upstream path cost		
		float rnodeCost = this.getRouteNodeCost(childRNode, thruNodeGroup, con, countSourceUses);	
		float new_partial_path_cost;
		int childLevel = rnode.getRoutableData().getLevel() + 1;
		if(!config.isTimingDriven()){
			new_partial_path_cost = partial_path_cost + rnodeCost;
		}else{
			new_partial_path_cost = partial_path_cost + (1 - con.getCriticality()) * rnodeCost +  con.criticality * childRNode.getDelay()/20f;//upstream path cost + cost of node under consideration		
		}
		float new_lower_bound_total_path_cost;

		short delay = 0;
		if(!childRNode.isTarget()){
			float expected_wire_cost = this.expectMahatD(childRNode, con) / (1 + countSourceUses);
			
			if(config.isTimingDriven()){
				NodeGroup nodeGroup = thruNodeGroup;
					try{
						delay = this.estimator.getMinDelayToSinkPin(nodeGroup, this.sinkPinNG);
						callDelayEstimator++;
					}catch(Exception e){
						if(nodeGroup.entryNode() != null)
							System.out.printf("Get min delay from NodeGroup ( " + nodeGroup.entryNode().toString() + " -> " + nodeGroup.exitNode().toString() + " )");
						else 
							System.out.printf("Get min delay from NodeGroup exit node only ( " + nodeGroup.exitNode().toString() + " )");
						if(this.sinkPinNG.entryNode() != null) 
							System.out.println(" to ( " + this.sinkPinNG.entryNode().toString() + " -> " + this.sinkPinNG.exitNode().toString() + " )");
						else
							System.out.println(" to ( " + this.sinkPinNG.exitNode().toString() + " )");
						e.printStackTrace();
					}
				
				new_lower_bound_total_path_cost = (float) (new_partial_path_cost + (1 - con.getCriticality()) * config.getMdWeight() * expected_wire_cost + config.getDelayWeight() * con.getCriticality() * delay/20f);
			}else {
				new_lower_bound_total_path_cost = (float) (new_partial_path_cost + config.getMdWeight() * expected_wire_cost + config.getHopWeight() * childLevel);
			}
			
		}else{//lut input pin (sink)
			new_lower_bound_total_path_cost = new_partial_path_cost;
			noCallOfDelayEstimator++;
		}
		
		this.addRNodeToQueueSetting(con, childRNode, rnode, thruNodeGroup, childLevel, new_partial_path_cost, new_lower_bound_total_path_cost);
	}
	
	private void addRNodeToQueueSetting(Connection con, RoutableNodeGroup childRNode, RoutableNodeGroup rnode, NodeGroup thruNodeGroup, int level, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
		RoutableData data = childRNode.getRoutableData();
		
		if(!data.isTouched()) {
			this.rnodesTouched.add(data);
			data.setLowerBoundTotalPathCost(new_lower_bound_total_path_cost);
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			childRNode.setThruNodeGroup(thruNodeGroup);
			if(rnode != null) data.setLevel(level);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
			
		}else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {//this block of code needed for better results
			//queue is sorted by lower bound total cost
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			childRNode.setThruNodeGroup(thruNodeGroup);
			if(rnode != null) data.setLevel(level);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
		}
	}
	
	private float getRouteNodeCost(RoutableNodeGroup rnode, NodeGroup thruNodeGroup, Connection con, int countSourceUses) {
		//Present congestion cost
		float pres_cost = this.getPresentCongestionCost(countSourceUses, rnode.getOccupancy(), rnode.getPres_cost());
														// rnode.countSourceUses(con.source)
		float acc_cost = rnode.getAcc_cost();
		//Bias cost
		float bias_cost = 0;

		if(!rnode.isTarget()) {
			Netplus net = con.getNet();
			bias_cost = 0.5f * rnode.getBase_cost() / net.fanout * 
						(Math.abs(rnode.getX() - net.x_geo) + Math.abs(rnode.getY() - net.y_geo)) / net.hpwl;
		}
		
		//add entry node congestion penalty to the Siblings
		EntryNode entry = thruNodeGroup.entryNode();	
		if(entry != null){
			pres_cost += this.getPresentCongestionCost(entry.countSourceUses(con.source), entry.getOcc(), entry.getPresCost());	
			acc_cost += entry.getAccCost();
		}
		return rnode.getBase_cost() * acc_cost * pres_cost / (1 + countSourceUses) + bias_cost;
	}
	
	private float getPresentCongestionCost(int countSourceUses, int occupancy, float nodePresCost){
		boolean containsSource = countSourceUses!= 0;	
		//Present congestion cost
		float pres_cost;
		
		if(containsSource) {//the "node" is used by other connection(s) from the same net
			int overoccupancy = occupancy - Routable.capacity;
			pres_cost = 1 + overoccupancy * this.pres_fac;//making it less expensive in congestion cost for the current connection
		}else{
			pres_cost = nodePresCost;
		}
		
		return pres_cost;
	}
	
	private float expectMahatD(RoutableNodeGroup childRNode, Connection con){
		float md;
		md = (float) (Math.abs(childRNode.getX() - con.getSinkRNode().getX())*2 + Math.abs(childRNode.getY() - con.getSinkRNode().getY())*4);
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
		//TODO currently, the one with index 1 is selected for delay estimation
		this.sinkPinNG = ((RoutableNodeGroup)con.getSinkRNode()).getNodeGroupSiblings().getSiblings()[0];
		
		// Add source to queue
		RoutableNodeGroup source = (RoutableNodeGroup) con.getSourceRNode();
		this.addRNodeToQueueSetting(con, source, null, source.getNodeGroupSiblings().getSiblings()[0], 0, 0, 0);
	}
	
	public void checkAverageNumWires(){
		this.averWire = 0;
		this.averNodePerNodeGroup = 0;
		this.averNodeGroupPerSiblings = 0;
		this.averNodePerSiblings = 0;
		this.averFanoutRNodes = 0;
		
		float sumWire = 0;
		float sumNodes = 0;
		float sumNG = 0;
		float sumSiblings = this.rnodesCreated.values().size();
		float sumChildren = 0;
		float sumRNodes = 0;
		
		for(RoutableNodeGroup rn:this.rnodesCreated.values()){	
			if(rn.childrenSet){
				sumChildren += rn.childrenAndThruGroup.size();
				sumRNodes++;
			}
			
			NodeGroup[] nodeGroups = rn.getNodeGroupSiblings().getSiblings();
			sumNG += nodeGroups.length;
			for(NodeGroup nodeGroup:nodeGroups){
				
				Node entryNode = nodeGroup.entryNode();
				Node exitNode = nodeGroup.exitNode();
				
				if(entryNode != null){
					sumNodes += 1;
					sumWire += entryNode.getAllWiresInNode().length;//not always 1
				}
				sumNodes += 1;
				sumWire += exitNode.getAllWiresInNode().length;
			}
		}
		this.averWire = sumWire / sumNG;
		this.averNodePerNodeGroup = sumNodes / sumNG;
		this.averNodeGroupPerSiblings = sumNG / sumSiblings;
		this.averNodePerSiblings = sumNodes / sumSiblings;
		this.averFanoutRNodes = sumChildren / sumRNodes;
	}
	
	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}

	public Design getDesign() {
		return this.design;
	}
	
	public int getUsedRNodes(){
		return this.usedRNodes.size();
	}
	
	public void timingInfo(){
		if(!this.config.isTimingDriven()) {
			for(Connection c : this.sortedListOfConnection) {
				float routeDelay = 0;
				for(Routable rng : c.rnodes) {
					if(rng.getDelay() == -5) {
						rng.setDelay(this.estimator.getDelayOf(((RoutableNodeGroup) rng).getNodeGroupSiblings().getSiblings()[0]));
					}
					routeDelay += rng.getDelay();
				}
				c.setTimingEdgeDelay(routeDelay);
			}
			this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireAndSlack();
			this.timingManager.calculateCriticality(this.sortedListOfConnection, 
					MAX_CRITICALITY, this.config.getCriticalityExp(), maxDelayAndTimingVertex.getFirst());
		}
		this.timingManager.getCriticalPathInfo(this);
	}
	
	public void getNodeGroupTypeAndDelayMap(){
		Map<GroupDelayType, CountingSet<Float>> typeDelays = new HashMap<>();
		
		for(RoutableNodeGroup rng:this.rnodesCreated.values()){
			GroupDelayType type = rng.getNodeGroupSiblings().groupDelayType();
			float delay = rng.getDelay();
			if(!typeDelays.containsKey(type)){
				CountingSet<Float> delays = new CountingSet<>();
				delays.add(delay);
				typeDelays.put(type, delays);
			}else{
				CountingSet<Float> delays = typeDelays.get(type);
				delays.add(delay);
				typeDelays.put(type, delays);
			}
			
		}
		
		for(GroupDelayType type : typeDelays.keySet()){
			System.out.println(type + "  " + typeDelays.get(type).delayToString());
			System.out.println(type + " weighted average = " + getWeightedAverage(typeDelays.get(type)) + "\n");
		}
		
	}
	
	public float getWeightedAverage(CountingSet<Float> delays) {
		float average = 0;
		float sumOfProduct = 0;
		int numOfDelays = 0;
		for(Float delay : delays.getMap().keySet()) {
			sumOfProduct += delay *delays.count(delay);
			numOfDelays += delays.count(delay);
		}
		average = sumOfProduct / numOfDelays;
		return average;
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
