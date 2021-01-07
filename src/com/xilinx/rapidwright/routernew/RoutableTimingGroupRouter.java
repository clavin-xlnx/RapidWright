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
import com.xilinx.rapidwright.timing.ImmutableTimingGroup;
import com.xilinx.rapidwright.timing.NodeWithFaninInfo;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingModel;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorTable;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.Pair;

public class RoutableTimingGroupRouter{
	public Design design;
	public String dcpFileName;
	public int nrOfTrials;
	
	public DelayEstimatorTable estimator;
	public TimingManager timingManager;
	public TimingModel timingModel;
	public TimingGraph timingGraph;
	private static final float MAX_CRITICALITY = 0.99f;
	private static final float CRITICALITY_EXPONENT = 1;
	private float MIN_REROUTE_CRITICALITY = 0.85f, REROUTE_CRITICALITY;
	private int MAX_PERCENTAGE_CRITICAL_CONNECTIONS = 3;
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
	public Map<NodeWithFaninInfo, RoutableTimingGroup> rnodesCreated;
//	public Set<ImmutableTimingGroup> rnodesExpanded = new HashSet<>();
	
	//map for solving congestion on entry nodes that are shared among siblings
	public Map<Connection, List<NodeWithFaninInfo>> connectionEntryNodes;
	
	public List<Connection> sortedListOfConnection;
	public List<Netplus> sortedListOfNetplus;
	public Map<TimingEdge, Connection> timingEdgeConnectionMap;
	
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
	public long nodesExpandedFirstIter;
	public int connectionsRoutedIteration;
	public long nodesPopedFromQueue;
	public long nodesPopedFromQueueFirstIter;
	public long callingOfGetNextRoutable;
	
	public Set<Integer> overUsedRNodes;
	public Set<Integer> usedRNodes;
	public Set<Integer> illegalRNodes;//nodes that have multiple drivers in a net
	public Set<NodeWithFaninInfo> usedEntryNodes;
	
	public long hops;
	public float manhattanD;
	public float averFanoutRNodes;
	public float averWire;
	public float averNodePerImmuTg;
	public float averImmuTgPerSiblings;
	public float averNodePerSiblings;
	public float firstRouting;
	public float firtRnodeT;
	
	public boolean trial = false;
	public boolean debugRoutingCon = false;
	public boolean debugExpansion = false;
	public boolean debugTiming = false;
	public boolean printRoutingSteps = false;
	
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
			boolean timingDriven,
			boolean hpcRun){
		
		this.buildData();
		
		this.design = design;
		this.design.unrouteDesign();//global signal routing needed for a complete routing flow
		DesignTools.unrouteDualOutputSitePinRouting(this.design);//this is for removing the unmatched SitePinInst - TimingVertex issue for TimingGraph 
		DesignTools.createMissingSitePinInsts(this.design);
		
		if(timingDriven){
			this.timingManager = new TimingManager(this.design, true);//slacks calculated
			this.timingModel = this.timingManager.getTimingModel();
			this.timingGraph = this.timingManager.getTimingGraph();
			
			Device device = Device.getDevice("xcvu3p-ffvc1517");
			
			InterconnectInfo ictInfo = new InterconnectInfo();
	        this.estimator = new DelayEstimatorTable(device,ictInfo, hpcRun);//DelayEstimatorTable<>(device,ictInfo, (short) 10, (short) 19, 0);
		}
		
		
		this.dcpFileName = dcpFileName;
		this.nrOfTrials = nrOfTrials;
		
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
			
		this.rthHelper = new RouteThruHelper(this.design.getDevice());
		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesExpanded = 0;
		this.nodesExpandedFirstIter = 0;
		this.nodesPopedFromQueue = 0;
		this.nodesPopedFromQueueFirstIter = 0;
		this.callingOfGetNextRoutable = 0;
			
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
	
	public int initializeNetsCons(short bbRange, float base_cost_fac){
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
					if(!timingDriven) 
						this.initializeNetAndCons(n, bbRange);
					else 
						this.initializeNetAndCons(n, bbRange, this.timingDriven);
					
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
		RoutableTimingGroup sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.SOURCERR, this.timingModel, this.base_cost_fac);
		for(SitePinInst sink:n.getSinkPins()){
			if(RouterHelper.isExternalConnectionToCout(source, sink)){
				source = n.getAlternateSource();
				if(source == null){
					String errMsg = "net alternate source is null: " + n.toStringFull();
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
			this.icon++;
		}
		if(n.getSinkPins().size() == 1) this.fanout1Net++;
	}
	
	public void initializeNetAndCons(Net n, short bbRange, boolean timingDriven){	
		EDIFNet edifNet = n.getLogicalNet();
		Map<Pair<SitePinInst, SitePinInst>, List<TimingEdge>> spiAndTimingEdges = this.timingGraph.getSpiAndTimingEdges();		
		
		if(edifNet != null){
			n.unroute();
			Netplus np = new Netplus(inetToBeRouted, bbRange, n);
			this.nets.add(np);		
			
			SitePinInst source = n.getSource();
			
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
				
				//set TimingEdge of connection			
				try{
					c.setTimingEdge(spiAndTimingEdges.get(new Pair<>(source, sink)));
				}catch(Exception e){
					System.out.println(c.toString());
					e.printStackTrace();
				}
				
				this.connections.add(c);
				c.setNet(np);
				for(TimingEdge e : c.timingEdges){
					this.timingEdgeConnectionMap.put(e, c); // for timing info
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
	
	public RoutableTimingGroup createRoutableNodeAndAdd(int index, SitePinInst sitePinInst, RoutableType type, TimingModel model, float base_cost_fac){
		RoutableTimingGroup routableTG = new RoutableTimingGroup(index, sitePinInst, type, this.estimator);
		routableTG.setBaseCost(base_cost_fac);
		this.rnodesCreated.put(routableTG.getSiblingsTimingGroup().getSiblings()[0].exitNode(), routableTG);
		this.rrgNodeId++;
		return routableTG;
	}
	
	public void getTotalNodesInResourceGraph(){
		Set<Node> totalNodes = new HashSet<>();
		
		for(RoutableTimingGroup rtg:this.rnodesCreated.values()){
			for(ImmutableTimingGroup immuTg:rtg.getSiblingsTimingGroup().getSiblings()){
				if(immuTg.entryNode() != null){
					totalNodes.add(immuTg.entryNode());
				}
			}
			totalNodes.add(rtg.getNode());
		}
		System.out.println("total nodes involved in the rtgs: " + totalNodes.size());
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
		for(NodeWithFaninInfo n : RoutableTimingGroup.entryNodesExpanded){
			if(n != null && n.isOverUsed()){
				overUsedEntryNodes++;
			}
		}
		return overUsedEntryNodes;
	}
	
	public int multiFaninEntryNodes(){
		int illegalEntryNodes = 0;
		for(NodeWithFaninInfo n : RoutableTimingGroup.entryNodesExpanded){
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
			RoutableTimingGroup source = (RoutableTimingGroup) (np.getConnection().get(0).getSourceRNode());
			this.tracingSetChildren(source);
			for(Connection con : np.getConnection()) {
				ImmutableTimingGroup sinkTG = ((RoutableTimingGroup) con.getSinkRNode()).getSiblingsTimingGroup().getSiblings()[1];

				short estConDelay = Short.MAX_VALUE;
				for(Pair<RoutableTimingGroup, ImmutableTimingGroup> child : source.childrenImmuTG) {
					try{
						short tmpConDelay = this.estimator.getMinDelayToSinkPin(child.getSecond(), sinkTG);
						tmpConDelay += child.getSecond().getDelay();
						if(tmpConDelay < estConDelay) {
							estConDelay = tmpConDelay;
						}
					}catch(Exception e) {
						System.out.println("   from " + child.getSecond() + " \n   to   " + sinkTG);
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
		if(this.timingDriven) {
			this.estimateDelayOfConnections();
			this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireAndSlack();
			this.timingManager.calculateCriticality(this.connections, MAX_CRITICALITY, CRITICALITY_EXPONENT, maxDelayAndTimingVertex.getFirst());
		
			this.timingManager.getCriticalPathInfo(this);
		
			System.out.println(String.format("pre-routing max delay using estimation: %3.2f", maxDelayAndTimingVertex.getFirst()));
		}
		this.route();
		long end = System.nanoTime();
		int timeInMilliseconds = (int)Math.round((end-start) * Math.pow(10, -6));
		this.getTotalNodesInResourceGraph();
		if(this.timingDriven) this.getNodeGroupTypeAndDelayMap();
//		this.entryNodesSharing();//4.8
		System.out.println();
		return timeInMilliseconds;
	}
	
	private void setRerouteCriticality(List<Connection> connections) {
    	//Limit number of critical connections
    	REROUTE_CRITICALITY = MIN_REROUTE_CRITICALITY;
    	this.criticalConnections.clear();
    	
    	int maxNumberOfCriticalConnections = (int) (this.connections.size() * 0.01 * MAX_PERCENTAGE_CRITICAL_CONNECTIONS);
    	
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
	
	public void tracingSetChildren(RoutableTimingGroup rtg) {
		if(!rtg.childrenSet) {
			Pair<Integer, Long> countPair = rtg.setChildren(this.rrgNodeId, this.base_cost_fac, this.rnodesCreated, 
					this.reservedNodes, this.rthHelper, this.timingDriven, this.estimator, this.routerTimer, this.callingOfGetNextRoutable);
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
		this.initializeRouter(this.initial_pres_fac, this.pres_fac_mult, this.acc_fac);
		//do routing
		boolean validRouting;
		List<Netplus> trialNets = new ArrayList<>();
        for(Netplus net : this.sortedListOfNetplus){
        	if(net.getNet().getName().equals("n1916")){
        		trialNets.add(net);
        	}
        }
        List<Connection> trialCons = new ArrayList<>();
        for(Netplus netp:trialNets){
        	for(Connection c:netp.getConnection()){
        		trialCons.add(c);
        	}
        }
        
		while(this.itry < this.nrOfTrials){
			this.iterationStart = System.nanoTime();
			this.connectionsRoutedIteration = 0;	
			validRouting = true;
			
			if(printRoutingSteps) this.printInfo("set reroute criticality");
			this.setRerouteCriticality(this.sortedListOfConnection);
			
			if(this.trial) this.printInfo("iteration " + this.itry + " begins");
			
			if(printRoutingSteps) this.printInfo("do routing");
			if(!this.trial){
				for(Connection con:this.sortedListOfConnection){
					this.routingAndTimer(con);
				}
			}else{
				for(Connection con : trialCons){		
						this.routingAndTimer(con);
						this.printConRNodes(con);
					}
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
			if(this.timingDriven) {
				this.routerTimer.updateTiming.start();
				if(illegalTrees == null){
					this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireAndSlack();
					this.timingManager.calculateCriticality(this.sortedListOfConnection, 
							MAX_CRITICALITY, CRITICALITY_EXPONENT, maxDelayAndTimingVertex.getFirst());
					
				}else{
					this.timingManager.updateIllegalNetsDelays(illegalTrees, this.nodesDelays);
					this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireAndSlack();
					this.timingManager.calculateCriticality(this.sortedListOfConnection, 
							MAX_CRITICALITY, CRITICALITY_EXPONENT, maxDelayAndTimingVertex.getFirst());

				}
				this.routerTimer.updateTiming.finish();
			}
			
			this.iterationEnd = System.nanoTime();
			
			//statistics
			if(printRoutingSteps) this.printInfo("statistics");
			this.routerTimer.calculateStatistics.start();
			this.iterationStatistics(trialCons, this.sortedListOfConnection);
			this.routerTimer.calculateStatistics.finish();
			
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
		
		if (this.itry == this.nrOfTrials + 1) {
			System.out.println("Routing terminated after " + this.itry + " trials!");
		}
		
		return;
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
			this.routerTimer.rerouteCongestion.start();
			this.routeACon(con);
			this.routerTimer.rerouteCongestion.finish();
		}
	}
	
	public boolean conEntryNodeCongested(Connection con){
		boolean congested = false;
		for(NodeWithFaninInfo n : this.connectionEntryNodes.get(con)){
			if(n != null){
				if(n.isOverUsed()){
					congested = true;
				}
			}
		}
		return congested;
	}
	
	public boolean isValidRouting(){
		for(RoutableTimingGroup rnode:this.rnodesCreated.values()){
			if(rnode.overUsed()){
				return false;
			}
		}
		return true;
	}
	
	public boolean validEntryNodesRouting(){
		boolean validEntryNodeRouting = true;
		for(NodeWithFaninInfo n : RoutableTimingGroup.entryNodesExpanded){
			if(n != null && n.isOverUsed()){
				validEntryNodeRouting = false;
			}
		}
		return validEntryNodeRouting;
	}
	
	/**
	 * statistics output for each router iteration
	 */
	public void iterationStatistics(List<Connection> trialCons, List<Connection> allCons) {
		if(!this.trial){
			this.statisticsInfo(allCons, this.iterationStart, this.iterationEnd, 
							this.rrgNodeId, this.routerTimer.rnodesCreation.getTime());
		}else{
			this.statisticsInfo(trialCons, this.iterationStart, this.iterationEnd, 
					this.rrgNodeId, this.routerTimer.rnodesCreation.getTime());
		}
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
				this.timingDriven?this.maxDelayAndTimingVertex.getFirst():null);
	}
	
	public void updateCostFactors(){
		if (this.itry == 1) {
			this.pres_fac = this.initial_pres_fac;
		} else {
			this.pres_fac *= this.pres_fac_mult;
		}
		this.updateCost(this.pres_fac, this.acc_fac);
	}
	
	//entryNode over usage should also be considered here
	//similar update for every entry node
	private void updateCost(float pres_fac, float acc_fac) {
		for(RoutableTimingGroup rnode:this.rnodesCreated.values()){
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
		RoutableTimingGroup.updateEntryNodesCosts(pres_fac, acc_fac);
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
		Set<Routable> netRNodes = new HashSet<>();
		for(Netplus net:this.nets){	
			for(Connection c:net.getConnection()){
				if(c.rnodes != null) {
					netRNodes.addAll(c.rnodes);
					this.hops += c.nodes.size() - 1;//hops counted using the number of nodes for all sinks
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
			
			for(NodeWithFaninInfo entry:this.connectionEntryNodes.get(conn)){
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
					System.out.println(illegalTree.getNet().getName() + " cycle exists");
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
		for(NodeWithFaninInfo entry:this.connectionEntryNodes.get(con)){
			if(entry != null && entry.hasMultiFanin()){
				return true;
			}
		}
		return illegal;
	}
	
	public void ripup(Connection con){
		RoutableTimingGroup parent = null;	
		for(int i = con.rnodes.size() - 1; i >= 0; i--){
			RoutableTimingGroup rnode = (RoutableTimingGroup) con.rnodes.get(i);
			if(rnode == null){
				System.out.println(con.rnodes.size() + ", " + i);
				System.out.println();
			}
			RoutableData rNodeData = rnode.rnodeData;
			
			rNodeData.removeSource(con.source);
			
			//remove sources of entry nodes
			//moving out from ripup will require one more traversal of the con.rnodes
			NodeWithFaninInfo thruEntryNode = this.removeEntryNodeSource(rnode, con, i);			
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
				RoutableTimingGroup.updatePresentCongestionPenaltyOfEntryNode(thruEntryNode, this.pres_fac);
			}
		}
	}
	
	public void add(Connection con){
		RoutableTimingGroup parent = null;
		
		for(int i = con.rnodes.size()-1; i >= 0; i--){
			//in the order of from sourcerr to sinkrr
			RoutableTimingGroup rnode = (RoutableTimingGroup) con.rnodes.get(i);
			RoutableData rNodeData = rnode.rnodeData;
			
			rNodeData.addSource(con.source);
			
			//add sources of entry nodes
			NodeWithFaninInfo thruEntryNode = this.addEntryNodeSource(rnode, con, i);			
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
			//for an entire SiblingsTimingGroup, not bothering intersection entry nodes
			rnode.updatePresentCongestionPenalty(this.pres_fac);		
			//update present cogestion of entry node
			if(thruEntryNode != null){
				RoutableTimingGroup.updatePresentCongestionPenaltyOfEntryNode(thruEntryNode, this.pres_fac);
			}		
		}
	}
	
	public NodeWithFaninInfo addEntryNodeSource(RoutableTimingGroup rnode, Connection con, int i){
		//thruImmuTg will never be null for non-source resources
		if(rnode.type != RoutableType.SOURCERR){
			NodeWithFaninInfo thruEntryNode = this.connectionEntryNodes.get(con).get(i);
			if(thruEntryNode != null){
				thruEntryNode.addSource(con.source);
				RoutableTimingGroup.entryNodesExpanded.add(thruEntryNode);
			}
			return thruEntryNode;
		}
		return null;
	}
	
	//adding parent needed for checking if there is any entry node that has multiple fanin
	public void addEntryNodeParent(NodeWithFaninInfo thruEntryNode, RoutableTimingGroup parent){
		if(thruEntryNode != null){	
			thruEntryNode.addParent(parent);
			RoutableTimingGroup.entryNodesExpanded.add(thruEntryNode);
		}
			
	}
	
	public NodeWithFaninInfo removeEntryNodeSource(RoutableTimingGroup rnode, Connection con, int i){
		if(rnode.type != RoutableType.SOURCERR){//SOURCERR does not have a thruImmuTg (null)
			NodeWithFaninInfo thruEntryNode = this.connectionEntryNodes.get(con).get(i);//thruImmuTg that should not be used here,
			//because it might be changed during the routing process of other connections that use the same rnode
			if(thruEntryNode != null){
				thruEntryNode.removeSource(con.source);
				RoutableTimingGroup.entryNodesExpanded.add(thruEntryNode);
			}
			return thruEntryNode;
		}
		return null;
	}
	
	public void removeEntryNodeParent(NodeWithFaninInfo thruEntryNode, RoutableTimingGroup parent){
		if(thruEntryNode != null){
			thruEntryNode.removeParent(parent);
			RoutableTimingGroup.entryNodesExpanded.add(thruEntryNode);
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
	
	public void checkImmuTgOfNet(Connection con, String net1, String net2, ImmutableTimingGroup immu){
		if(con.getNet().getNet().getName().equals(net1) || con.getNet().getNet().getName().equals(net2)){
			System.out.println(con.getNet().getNet().getName() + immu.toString());
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
	
	public void findAverBaseCosts(){
		Set<Float> costs = new HashSet<>();
		float aver = 0;
		float sum = 0;
		for(RoutableTimingGroup rn:this.rnodesCreated.values()){
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
			
			RoutableTimingGroup rnode = (RoutableTimingGroup) queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			this.tracingSetChildren(rnode);
			this.routerTimer.rnodesCreation.finish();
			
			this.exploringAndExpansion(rnode, con);
		}
		
		this.finishRoutingACon(con);
		
		if(this.timingDriven) this.updateConRouteDelay(con);
//		this.printConRNodes(con);
	}
	
	public void updateConRouteDelay(Connection con){
		con.updateRouteDelay();
	}
	
	public void printConRNodes(Connection con){
		this.printInfo(con.toString());
		for(int i = con.timingGroups.size() - 1; i >= 0; i--) {
			this.printInfo(con.timingGroups.get(i).toString());
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
		RoutableTimingGroup rn = (RoutableTimingGroup) con.getSinkRNode();
		List<NodeWithFaninInfo> entryNodes;
		if(!this.connectionEntryNodes.containsKey(con)){
			entryNodes = new ArrayList<>();
		}else{
			entryNodes = this.connectionEntryNodes.get(con);
			entryNodes.clear();
		}
		while (rn != null) {
			con.addRNode(rn);
			con.addTimingGroup(rn.getThruImmuTg());
			entryNodes.add(rn.getThruImmuTg().entryNode());
			rn = (RoutableTimingGroup) rn.rnodeData.getPrev();
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
	
	public void exploringAndExpansion(RoutableTimingGroup rnode, Connection con){
		this.nodesPopedFromQueue++;
		
		for(Pair<RoutableTimingGroup, ImmutableTimingGroup> childRNode : rnode.childrenImmuTG){
			RoutableTimingGroup child = childRNode.getFirst();
			ImmutableTimingGroup thruImmu = childRNode.getSecond();
			
			if(child.type == RoutableType.INTERRR){
				if(child.isInBoundingBoxLimit(con)){
					//PIN_BOUNCE
					if(thruImmu.delayType() == GroupDelayType.PIN_BOUNCE){
						if(!this.usablePINBounce(child, con.getSinkRNode())){
							continue;
						}
					}
					this.addNodeToQueue(rnode, child, thruImmu, con);
					this.nodesExpanded++;
					
				}
			}else if(child.isTarget()){	
				this.addNodeToQueue(rnode, child, thruImmu, con);
				this.nodesExpanded++;
				
			}
		}
	}
	
	private boolean usablePINBounce(RoutableTimingGroup childGroup, Routable sinkPinTG){
		// which part of the node should be used to define the column and row? base INT tile? ending INT tile?
		
		int columnChild = childGroup.getNode().getTile().getColumn();
		int rowChild = childGroup.getNode().getTile().getRow();
		int columnSink = sinkPinTG.getNode().getTile().getColumn();//TODO use tile coordinate of entry node (which one of the siblings?)
		int rowSink = sinkPinTG.getNode().getTile().getRow();
		if(columnChild == columnSink && Math.abs(rowChild - rowSink) <= 1){
			return true;
		}
		
		return false;
	}
	long callDelayEstimator = 0;
	long noCallOfDelayEstimator = 0;
	private void addNodeToQueue(RoutableTimingGroup rnode, RoutableTimingGroup childRNode, ImmutableTimingGroup thruImmuTg, Connection con) {
		RoutableData data = childRNode.rnodeData;
		int countSourceUses = data.countSourceUses(con.source);		
		float partial_path_cost = rnode.rnodeData.getPartialPathCost();//upstream path cost		
		float rnodeCost = this.getRouteNodeCost(childRNode, thruImmuTg, con, countSourceUses);	
		float new_partial_path_cost;
		int childLevel = rnode.rnodeData.getLevel() + 1;
		if(!this.timingDriven){
			new_partial_path_cost = partial_path_cost + rnodeCost;
		}else{
			new_partial_path_cost = partial_path_cost + (1 - con.getCriticality()) * rnodeCost +  con.criticality * childRNode.getDelay()/20f;//upstream path cost + cost of node under consideration		
		}
		float new_lower_bound_total_path_cost;
		float expected_distance_cost = 0;
		float expected_wire_cost;
		short delay = 0;
		if(childRNode.type == RoutableType.INTERRR){
			expected_distance_cost = this.expectMahatD(childRNode, con);
			expected_wire_cost = expected_distance_cost / (1 + countSourceUses);
			
			if(!this.timingDriven){
				new_lower_bound_total_path_cost = (float) (new_partial_path_cost + this.mdWeight * expected_wire_cost + this.hopWeight * childLevel);
			}else{
				ImmutableTimingGroup immuTG = thruImmuTg;
					try{
						delay = this.estimator.getMinDelayToSinkPin(immuTG, this.sinkPinTG);
						callDelayEstimator++;
					}catch(Exception e){
						if(immuTG.entryNode() != null)
							System.out.printf("Get min delay from ImmuTimingGroup ( " + immuTG.entryNode().toString() + " -> " + immuTG.exitNode().toString() + " )");
						else 
							System.out.printf("Get min delay from ImmuTimingGroup exit node only ( " + immuTG.exitNode().toString() + " )");
						if(this.sinkPinTG.entryNode() != null) 
							System.out.println(" to ( " + this.sinkPinTG.entryNode().toString() + " -> " + this.sinkPinTG.exitNode().toString() + " )");
						else
							System.out.println(" to ( " + this.sinkPinTG.exitNode().toString() + " )");
						e.printStackTrace();
					}
				
				new_lower_bound_total_path_cost = (float) (new_partial_path_cost + (1 - con.getCriticality()) * this.mdWeight * expected_wire_cost + this.hopWeight * con.getCriticality() * delay/20f);//TODO sharing factor for delay?
			}
			
		}else{//lut input pin (sink)
			new_lower_bound_total_path_cost = new_partial_path_cost;
			noCallOfDelayEstimator++;
		}	
		/*if(this.debugExpansion){
			System.out.println("\t\t partial_path_cost = " + partial_path_cost 
								+ ", \n \t\t rnodeCost = " + rnodeCost
								+ ", \n \t\t countSourceUses = " + countSourceUses
								+ ", \n \t\t expected_distance_cost = " + expected_distance_cost
								+ ", \n \t\t new_lower_bound_total_path_cost = " + new_lower_bound_total_path_cost);
		}*/
		
		this.addRNodeToQueueSetting(con, childRNode, rnode, thruImmuTg, childLevel, new_partial_path_cost, new_lower_bound_total_path_cost);
	}
	
	private void addRNodeToQueueSetting(Connection con, RoutableTimingGroup childRNode, RoutableTimingGroup rnode, ImmutableTimingGroup thruImmuTg, int level, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
		RoutableData data = childRNode.rnodeData;
		
		if(!data.isTouched()) {
			this.rnodesTouched.add(data);
			data.setLowerBoundTotalPathCost(new_lower_bound_total_path_cost);
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			childRNode.setThruImmuTg(thruImmuTg);
			if(rnode != null) data.setLevel(level);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
			
		}else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {//this block of code needed for better results
			//queue is sorted by lower bound total cost
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			childRNode.setThruImmuTg(thruImmuTg);
			if(rnode != null) data.setLevel(level);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
		}
	}
	
	private float getRouteNodeCost(RoutableTimingGroup rnode, ImmutableTimingGroup thruImmuTg, Connection con, int countSourceUses) {
		//Present congestion cost
		float pres_cost = this.getPresentCongestionCost(countSourceUses, rnode.getOccupancy(), rnode.getPres_cost());
														// rnode.countSourceUses(con.source)
		float acc_cost = rnode.getAcc_cost();
		//Bias cost
		float bias_cost = 0;
		if(rnode.type == RoutableType.INTERRR) {
			Netplus net = con.getNet();
			bias_cost = 0.5f * rnode.getBase_cost() / net.fanout * 
					(Math.abs(rnode.getX() - net.x_geo) + Math.abs(rnode.getY() - net.y_geo)) / net.hpwl;
		}
		
		//add entry node congestion penalty to the Siblings
		NodeWithFaninInfo entry = thruImmuTg.entryNode();	
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
	
	private float expectMahatD(RoutableTimingGroup childRNode, Connection con){
		float md;
		md = Math.abs(childRNode.getX() - con.getSinkRNode().getX()) + Math.abs(childRNode.getY() - con.getSinkRNode().getY());
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
		this.sinkPinTG = ((RoutableTimingGroup)con.getSinkRNode()).getSiblingsTimingGroup().getSiblings()[1];
		
		// Add source to queue
		RoutableTimingGroup source = (RoutableTimingGroup) con.getSourceRNode();
		this.addRNodeToQueueSetting(con, source, null, source.getSiblingsTimingGroup().getSiblings()[0], 0, 0, 0);
	}
	
	public void checkAverageNumWires(){
		this.averWire = 0;
		this.averNodePerImmuTg = 0;
		this.averImmuTgPerSiblings = 0;
		this.averNodePerSiblings = 0;
		this.averFanoutRNodes = 0;
		
		float sumWire = 0;
		float sumNodes = 0;
		float sumTG = 0;
		float sumSiblings = this.rnodesCreated.values().size();
		float sumChildren = 0;
		float sumRNodes = 0;
		
		for(RoutableTimingGroup rn:this.rnodesCreated.values()){	
			if(rn.childrenSet){
				sumChildren += rn.childrenImmuTG.size();
				sumRNodes++;
			}
			
			ImmutableTimingGroup[] timingGroups = rn.getSiblingsTimingGroup().getSiblings();
			sumTG += timingGroups.length;
			for(ImmutableTimingGroup tg:timingGroups){
				
				Node entryNode = tg.entryNode();
				Node exitNode = tg.exitNode();
				
				if(entryNode != null){
					sumNodes += 1;
					sumWire += entryNode.getAllWiresInNode().length;//not always 1
				}
				sumNodes += 1;
				sumWire += exitNode.getAllWiresInNode().length;
			}
		}
		this.averWire = sumWire / sumTG;
		this.averNodePerImmuTg = sumNodes / sumTG;
		this.averImmuTgPerSiblings = sumTG / sumSiblings;
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
		this.timingManager.getCriticalPathInfo(this);
	}
	
	public void getNodeGroupTypeAndDelayMap(){
		Map<GroupDelayType, CountingSet<Float>> typeDelays = new HashMap<>();
		
		for(RoutableTimingGroup rtg:this.rnodesCreated.values()){
			GroupDelayType type = rtg.getSiblingsTimingGroup().type();
			float delay = rtg.getDelay();
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
			System.out.println(type + "  " + typeDelays.get(type));
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
