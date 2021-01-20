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
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.GroupDelayType;
import com.xilinx.rapidwright.timing.NodeGroup;
import com.xilinx.rapidwright.timing.EntryNode;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingModel;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorTable;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.Pair;

public class RoutableGroupRouterWithVirtualMode{
	public Design design;
	public String dcpFileName;
	public int nrOfTrials;
	public CodePerfTracker t;
	
	public DelayEstimatorTable estimator;
	public TimingManager timingManager;
	public TimingModel timingModel;
	public TimingGraph timingGraph;
	private static final float MAX_CRITICALITY = 0.99f;
	private static final float CRITICALITY_EXPONENT = 1;
	private float MIN_REROUTE_CRITICALITY = 0.85f, REROUTE_CRITICALITY;
	private int MAX_PERCENTAGE_CRITICAL_CONNECTIONS = 3;
	private List<Connection> criticalConnections;
	public Map<TimingEdge, Connection> timingEdgeConnectionMap;
	
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
	public Collection<RoutableNodeGroup> rnodesTouched;
	public Map<Node, RoutableNodeGroup> rnodesCreated;
	
	//map for solving congestion on entry nodes that are shared among siblings
	public Map<Connection, List<EntryNode>> connectionEntryNodes;
	
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
	public NodeGroup sinkPinTG;
	
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
	public Set<EntryNode> usedEntryNodes;
	
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
	
	public RoutableGroupRouterWithVirtualMode(Design design,
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
		this.design.unrouteDesign();//TODO complete global signal routing for a complete routing flow
		DesignTools.unrouteDualOutputSitePinRouting(this.design);//this is for removing the unmatched SitePinInst - TimingVertex issue for TimingGraph 
		DesignTools.createMissingSitePinInsts(this.design);
		
		this.queue = new PriorityQueue<>(Comparators.PRIORITY_COMPARATOR);
		this.rnodesTouched = new ArrayList<>();
		
		if(timingDriven){
			this.timingManager = new TimingManager(this.design, true);//slacks calculated
			this.timingModel = this.timingManager.getTimingModel();
			this.timingGraph = this.timingManager.getTimingGraph();
			
			//original setTimingRequirement includes compute arrival time
			//TODO check. 1290ps is got from the example PipelineGneratorWithRouting
			this.timingGraph.setTimingRequirementOnly(5000);
			
			Device device = Device.getDevice("xcvu3p-ffvc1517");
			
			InterconnectInfo ictInfo = new InterconnectInfo();
	        this.estimator = new DelayEstimatorTable(device,ictInfo, hpcRun);//DelayEstimatorTable<>(device,ictInfo, (short) 10, (short) 19, 0);
		}
		
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
		//TODO move new here
		this.nets = new ArrayList<>();
		this.connections = new ArrayList<>();
		this.timingEdgeConnectionMap = new HashMap<>();
		
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfNetplus = new ArrayList<>();
		
		this.connectionEntryNodes = new HashMap<>();
		this.criticalConnections = new ArrayList<>();
		
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
		this.nets = new ArrayList<>();
		this.connections = new ArrayList<>();
		
		for(Net n:this.design.getNets()){
			/*if(n.getName().equals("ncda") || n.getName().equals("nc9e") 
					|| n.getName().equals("nca0") || n.getName().equals("ncbe")){
				System.out.println(n.toStringFull());
				System.out.println("alternative source " + n.getAlternateSource());
			}*/
			
			if(n.isClockNet()){
//				System.out.println(n.toStringFull());
				this.reserveNet(n);
				this.iclockAndStaticNet++;
				
			}else if(n.isStaticNet()){
				this.reserveNet(n);
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
					
//					this.reserveNet(n);
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
		RoutableNodeGroup sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.SOURCERR, this.timingModel, this.base_cost_fac);
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
			
			RoutableNodeGroup sinkRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, sink, RoutableType.SINKRR, this.timingModel, this.base_cost_fac);
			c.setSinkRNode(sinkRNode);
			this.connections.add(c);
			c.setNet(np);
			np.addCons(c);
			this.icon++;
			/*System.out.println(c.toString());
			System.out.println(sinkRNode.toStringEntriesAndExit());
			System.out.println();*/
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
			
			RoutableNodeGroup sourceRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, source, RoutableType.SOURCERR, this.timingModel, this.base_cost_fac);
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
				
				RoutableNodeGroup sinkRNode = this.createRoutableNodeAndAdd(this.rrgNodeId, sink, RoutableType.SINKRR, this.timingModel, this.base_cost_fac);
				c.setSinkRNode(sinkRNode);
				
				//set TimingVertex/Edge of connection				
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
	
	public RoutableNodeGroup createRoutableNodeAndAdd(int index, SitePinInst sitePinInst, RoutableType type, TimingModel model, float base_cost_fac){
		RoutableNodeGroup routableTG = new RoutableNodeGroup(index, sitePinInst, type, this.estimator);
		this.rnodesCreated.put(routableTG.getNodeGroupSiblings().getSiblings()[0].exitNode(), routableTG);
		this.rrgNodeId++;
		return routableTG;
	}
	
	public void getTotalNodesInResourceGraph(){
		Set<Node> totalNodes = new HashSet<>();
		
		for(RoutableNodeGroup rtg:this.rnodesCreated.values()){
			for(NodeGroup immuTg:rtg.getNodeGroupSiblings().getSiblings()){
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
		
		System.out.printf("--------------------------------------------------------------------------------------------------------------------------------------- --------------------\n");
        System.out.printf("%9s  %11s  %12s  %12s  %15s  %11s  %7s  %8s  %15s  %17s  %19s \n", 
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
        		"MultiFaninEntryNode");
        System.out.printf("---------  -----------  ------------  ------------  ---------------  -----------  -------  --------  ---------------  -----------------  -------------------\n");
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
	
	public int routingRuntime(){
		long start = System.nanoTime();
		this.route();
		long end = System.nanoTime();
		int timeInMilliseconds = (int)Math.round((end-start) * Math.pow(10, -6));
		this.printTotalUsedNodes();
		this.getTotalNodesInResourceGraph();
//		this.entryNodesSharing();//4.8
		System.out.println();
		return timeInMilliseconds;
	}
	
	public void entryNodesSharing(){
		float sum = 0;
		for(EntryNode n:RoutableNodeGroup.entryNodesExpanded){
//			sum += n.entryHolders.size();
		}
		System.out.println("all entry nodes in cost map: " + RoutableNodeGroup.entryNodesExpanded.size());
		System.out.println("average shaing for used entry nodes: " + sum/RoutableNodeGroup.entryNodesExpanded.size());
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
        	if(net.getNet().getName().equals("n689") || net.getNet().getName().equals("nabc")){
        		trialNets.add(net);
//        		System.out.println(net.getNet().getName());
        	}
        }
        List<Connection> trialCons = new ArrayList<>();
        for(Netplus netp:trialNets){
        	for(Connection c:netp.getConnection()){
        		trialCons.add(c);
        	}
        }
        /*for(Connection con : this.sortedListOfConnection){
        	if(con.id == 1216){
        		trialCons.add(con);
        	}
        }*/
        
		while(this.itry < this.nrOfTrials){
			this.iterationStart = System.nanoTime();
			//TODO TIMING GRAPH HERE
			this.connectionsRoutedIteration = 0;	
			validRouting = true;
			
//			this.setRerouteCriticality(this.sortedListOfConnection);
			
			if(this.trial) this.printInfo("iteration " + this.itry + " begins");
			
			if(!this.trial){
				for(Connection con:this.sortedListOfConnection){
					this.routingAndTimer(con);
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
			
			if(this.itry == 1){
				this.nodesExpandedFirstIter = this.nodesExpanded;
				this.nodesPopedFromQueueFirstIter = this.nodesPopedFromQueue;
			}
			
			//check if routing is valid
			validRouting = this.isValidRouting() && this.validEntryNodesRouting();
			
			//fix illegal routing trees if any
			if(validRouting){
				this.routerTimer.rerouteIllegal.start();
//				System.out.println("entry nodes used: " + RoutableTimingGroup.entryNodeSources.size());
				
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
				
				this.fixIllegalTree(sortedListOfConnection);
				this.routerTimer.rerouteIllegal.finish();
			}
			
			if(this.timingDriven) {
//				String maxDelayString = String.format("%9s", "---");
//				this.timingManager.updateRouteDelays(this.sortedListOfConnection);
//				float maxDelay = this.timingManager.calculateArrivalRequireAndSlack();
//				this.timingManager.calculateCriticality(this.sortedListOfConnection, 
//						MAX_CRITICALITY, CRITICALITY_EXPONENT, maxDelay);
//				
////				float maxDelay = (float) this.timingGraph.getMaxDelayPath().getWeight();
////				maxDelayString = String.format("%9.3f", maxDelay);//TODO printed out in the statistic info
//				System.out.println(maxDelayString);
			}
			
			this.iterationEnd = System.nanoTime();
			//statistics
			this.routerTimer.calculateStatistics.start();
			if(!this.trial){
				this.staticticsInfo(this.sortedListOfConnection, 
								this.iterationStart, 
								this.iterationEnd, 
								this.rrgNodeId, 
								this.routerTimer.rnodesCreation.getTime());
			}else{
				this.staticticsInfo(trialCons, 
				this.iterationStart, 
				this.iterationEnd, 
				this.rrgNodeId, 
				this.routerTimer.rnodesCreation.getTime());
			}
			this.routerTimer.calculateStatistics.finish();
			//if the routing is valid /realizable return, the routing completed successfully
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
	
	public void printTotalUsedNodes(){
		Set<Node> usedNodes = new HashSet<>();
		for(Connection c:this.sortedListOfConnection){
			for(Node n:c.nodes){
				usedNodes.add(n);
			}
		}
		System.out.println("Total used Nodes: " + usedNodes.size());
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
	}
	
	public boolean conEntryNodeCongested(Connection con){
//		this.routerTimer.checkOnEntryNodeCongestion.start();//0.02s first of 487
		boolean congested = false;
		for(EntryNode n : this.connectionEntryNodes.get(con)){
			if(n != null){
				if(n.isOverUsed()){
					congested = true;
				}
			}
		}
//		this.routerTimer.checkOnEntryNodeCongestion.finish();
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
//		this.routerTimer.checkIfEntryNodesRoutingValid.start();//0.03s first of 487
		boolean validEntryNodeRouting = true;
		for(EntryNode n : RoutableNodeGroup.entryNodesExpanded){
			if(n != null && n.isOverUsed()){
				validEntryNodeRouting = false;
			}
		}
//		this.routerTimer.checkIfEntryNodesRoutingValid.finish();
		return validEntryNodeRouting;
	}
	
	/*
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
		
//		int numRNodesCreated = this.rnodesCreated.size();
		int overUsed = this.overUsedRNodes.size();
		int illegal = this.illegalRNodes.size();
//		double overUsePercentage = 100.0 * (double)this.overUsedRNodes.size() / numRNodesCreated;
		System.out.printf("%9d  %11d  %12.2f  %12d  %15.2f  %11d  %7d  %8d  %15d  %17d  %19d\n", 
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
				this.multiFaninEntryNodes());
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
//		System.out.println("iteration = " + this.itry);
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
				netRNodes.addAll(c.rnodes);
				this.hops += c.nodes.size() - 1;//hops counted using the number of nodes for all sinks
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
					this.usedEntryNodes.add(entry);//TODO hashCode is not unique
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
	
	public void fixIllegalTree(List<Connection> cons){
//		this.printInfo("checking if there is any illegal node");	
		int numIllegal = this.getIllegalNumRNodes(cons);
		GraphHelper graphHelper = new GraphHelper();
		if(numIllegal > 0){
//			this.printInfo("There are " + numIllegal + " illegal routing tree nodes");
			
			List<Netplus> illegalTrees = new ArrayList<>();
			for(Netplus net : this.nets) {
				boolean illegal = false;
				for(Connection con : net.getConnection()) {
					if(con.illegal() || illegalConOnEntryNode(con)) {
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
				for(Connection c:illegalTree.getConnection()){
					this.ripup(c);
				}
				if(isCyclic){
					//remove cycles
//					System.out.println(illegalTree.getNet().getName() + " cycle exists");
					graphHelper.cutOffIllegalEdges(illegalTree, true);// clean version (update to router fields)
				}else{
//					System.out.println(illegalTree.getNet().getName() + " no cycles illegal tree");
					this.handleNoCyclicIllegalRoutingTree(illegalTree);
					this.handleNoCyclicEntryNodeIllegalTree(illegalTree);
				}
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
	
	public Node getIllegalEntryNode(Netplus illegalTree){
		
		for(Connection con:illegalTree.getConnection()){
			for(EntryNode entry:this.connectionEntryNodes.get(con)){
				if(entry != null && entry.hasMultiFanin()){
					return entry;
				}
			}
		}
		return null;
	}
	
	public Set<Connection> getIllegalCons(Netplus illegalTree, Node illegalNode){
		Set<Connection> illegalCons = new HashSet<>();
		for(Connection con : illegalTree.getConnection()) {
			for(Node node : this.connectionEntryNodes.get(con)) {
				if(node!= null && node.equals(illegalNode)) {
					illegalCons.add(con);					
				}
			}
		}
		return illegalCons;
	}
	
	public Connection getCriticalCon(Set<Connection> illegalCons){
		Connection maxCriticalConnection = (Connection) illegalCons.toArray()[0];
		for(Connection illegalConnection : illegalCons) {
			if(illegalConnection.nodes.size() > maxCriticalConnection.nodes.size()) {
				maxCriticalConnection = illegalConnection;
			}
		}
		return maxCriticalConnection;
	}
	
	public List<Node> getNodesFromIllegalNodeToSink(Connection maxCriticalConnection, Node illegalNode){
		List<Node> newNodes = new ArrayList<>();
		boolean add = false;
		for(Node newRouteNode : maxCriticalConnection.nodes) {
			if(newRouteNode.equals(illegalNode)) add = true;
			if(add){
				newNodes.add(newRouteNode);
			}
		}
		return newNodes;
	}
	
	public void fixIllegalCons(Set<Connection> illegalCons, Node illegalNode, List<Node> nodesFromIllegalToSink){
		for(Connection illegalConnection : illegalCons) {
			this.ripup(illegalConnection);
			
			//Remove illegal path from routing tree
			while(!illegalConnection.nodes.remove(illegalConnection.nodes.size() - 1).equals(illegalNode));
			
			//Add new path to routing tree
			
			for(Node newRouteNode : nodesFromIllegalToSink) {
				illegalConnection.addNode(newRouteNode);				
			}
			
//			this.add(illegalConnection);
		}
	}
	
	public void handleNoCyclicEntryNodeIllegalTree(Netplus illegalTree){
		Node illegalNode;
		while((illegalNode = this.getIllegalEntryNode(illegalTree)) != null){				
			
			Set<Connection> illegalCons = this.getIllegalCons(illegalTree, illegalNode);
			
			//fixing the illegal trees, since there is no criticality info, use the hops info
			//Find the illegal connection with maximum number of RNodes (hops)
			Connection maxCriticalConnection = this.getCriticalCon(illegalCons);
			
			//Get the path from the connection with maximum hops
			//get the entry nodes from the critical connection
			List<Node> nodesFromIllegalToSink = this.getNodesFromIllegalNodeToSink(maxCriticalConnection, illegalNode);
			
			//Replace the path of each illegal connection with the path from the connection with maximum hops
			this.fixIllegalCons(illegalCons, illegalNode, nodesFromIllegalToSink);		
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
			
			//Get the path from the connection with maximum hops
			List<Routable> newRouteNodes = new ArrayList<>();
			//get the entry nodes from the critical connection
			List<EntryNode> entryNodes = new ArrayList<>();
			
			boolean add = false;
			int ir = 0;
			for(Routable newRouteNode : maxCriticalConnection.rnodes) {
				if(newRouteNode.equals(illegalRNode)) add = true;
				if(add){
					newRouteNodes.add(newRouteNode);
					entryNodes.add(this.connectionEntryNodes.get(maxCriticalConnection).get(ir));//this is for the statistic info update
					
				}
				ir++;
			}
			
			//Replace the path of each illegal connection with the path from the connection with maximum hops
			for(Connection illegalConnection : illegalCons) {
				this.ripup(illegalConnection);
				
				//Remove illegal path from routing tree
				while(!illegalConnection.rnodes.remove(illegalConnection.rnodes.size() - 1).equals(illegalRNode)){
					this.connectionEntryNodes.get(illegalConnection).remove(this.connectionEntryNodes.get(illegalConnection).size() - 1);
				}
				this.connectionEntryNodes.get(illegalConnection).remove(this.connectionEntryNodes.get(illegalConnection).size() - 1);
				
				while(!illegalConnection.nodes.remove(illegalConnection.nodes.size() - 1).equals(illegalNode));
				
				//Add new path to routing tree
				int ien = 0;
				for(Routable newRouteNode : newRouteNodes) {
					illegalConnection.addRNode(newRouteNode);
					
					illegalConnection.addNode(newRouteNode.getNode());
					Node entry = entryNodes.get(ien);
					if(entry != null) illegalConnection.addNode(entry);
					this.connectionEntryNodes.get(illegalConnection).add(entryNodes.get(ien));
					ien++;
				}
				
				this.add(illegalConnection);//to update rnodes and entry nodes status, i.e. sources and parents
			}
			
		}
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
			//for an entire SiblingsTimingGroup, not bothering intersection entry nodes
			rnode.updatePresentCongestionPenalty(this.pres_fac);		
			//update present cogestion of entry node
			if(thruEntryNode != null){
				RoutableNodeGroup.updatePresentCongestionPenaltyOfEntryNode(thruEntryNode, this.pres_fac);
			}		
		}
	}
	
	public EntryNode addEntryNodeSource(RoutableNodeGroup rnode, Connection con, int i){
		//thruImmuTg will never be null for non-source resources
		if(rnode.getRoutableType() != RoutableType.SOURCERR){
			
//			Node thruEntryNode = rnode.getThruImmuTg().entryNode();
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
		if(rnode.getRoutableType() != RoutableType.SOURCERR){//SOURCERR does not have a thruImmuTg (null)
			EntryNode thruEntryNode = this.connectionEntryNodes.get(con).get(i);//thruImmuTg that should not be used here,
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
//		this.debugRoutingCon = true;
//		this.printWrittenPIPs();
//		System.out.println("net opr[51]: ");
//		this.checkPIPsOfInvalidlyRoutedNet("opr[51]");
//			
//		System.out.println("\nn62b: ");
//		this.checkPIPsOfInvalidlyRoutedNet("n62b");
		
//		System.out.println("\nnet opr[1]: ");
//		this.checkPIPsOfInvalidlyRoutedNet("opr[1]");
//		
//		System.out.println("\nnet opr[46]: ");
//		this.checkPIPsOfInvalidlyRoutedNet("opr[46]");
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
	
	public List<PIP> conPIPs(Connection con){
		List<PIP> conPIPs = new ArrayList<>();
		List<Node> conNodes = new ArrayList<>();
		
		for(int i = 0; i < con.rnodes.size() - 1; i++){		
			RoutableNodeGroup rtg = ((RoutableNodeGroup) (con.rnodes.get(i)));
			conNodes.add(rtg.getNodeGroupSiblings().getExitNode());
			Node entry = this.connectionEntryNodes.get(con).get(i);
			if(entry != null){
				conNodes.add(entry);
			}		
		}
		Node sourcePinNode = con.source.getConnectedNode();
		conNodes.add(sourcePinNode);
		
		for(int i = conNodes.size() - 1; i > 0; i--){
			Node nodeFormer = conNodes.get(i);
			Node nodeLatter = conNodes.get(i - 1);
			PIP pip = RouterHelper.findThePIPbetweenTwoNodes(nodeFormer, nodeLatter);
			if(pip != null){
				conPIPs.add(pip);
			}else{
				System.err.println("Null PIP connecting node " + nodeFormer.toString() + " and node " + nodeLatter.toString());
			}
		}
		return conPIPs;
	}
	
	public void checkImmuTgOfNet(Connection con, String net1, String net2, NodeGroup immu){
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
		for(RoutableNodeGroup rn:this.rnodesCreated.values()){
			sum += rn.getBase_cost();
			costs.add(rn.getBase_cost());
		}
		aver = sum/this.rnodesCreated.size();
		System.out.println(aver);
	}
	
	public void findCongestion(){
		for(RoutableNodeGroup rn : this.rnodesCreated.values()){
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
			
		while(!this.targetReached(con)){
			
			RoutableNodeGroup rnode = (RoutableNodeGroup) queue.poll().rnode;
			
			
			if(!rnode.childrenSet){
				this.routerTimer.rnodesCreation.start();
				Pair<Integer, Long> countPair = rnode.setChildren(this.rrgNodeId, this.rnodesCreated, 
						this.reservedNodes, this.rthHelper, this.timingDriven, this.estimator, this.callingOfGetNextRoutable);
				this.rrgNodeId = countPair.getFirst();
				this.callingOfGetNextRoutable = countPair.getSecond();
				this.routerTimer.rnodesCreation.finish();
			}
			
			this.exploringAndExpansion(rnode, con);
		}
		
		this.finishRoutingACon(con);
		
//		this.printConRNodes(con);
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
		for (RoutableNodeGroup node : this.rnodesTouched) {
			node.getRoutableData().setTouched(false);
			// TODO virtualMode be true
			node.virtualMode = true;
		}
		this.rnodesTouched.clear();	
	}
	
	public void exploringAndExpansion(RoutableNodeGroup rnode, Connection con){
		this.nodesPopedFromQueue++;
		
		
		for(Pair<RoutableNodeGroup, NodeGroup> childRNode : rnode.childrenAndThruGroup){
			RoutableNodeGroup child = childRNode.getFirst();
			NodeGroup thruImmu = childRNode.getSecond();
			
			if(child.isTarget()){		
				this.addNodeToQueue(rnode, child, thruImmu, con);
				this.nodesExpanded++;
				
			}else if(child.getRoutableType() == RoutableType.INTERRR){
				if(child.isInBoundingBoxLimit(con)){
					
					if(!child.virtualMode){
						this.addNodeToQueue(rnode, child, thruImmu, con);
					}else{
						//TODO copying costs from the parent
						this.addRNodeToQueuePushing(con, child, rnode, thruImmu, rnode.getRoutableData().getLevel() + 1, rnode.getRoutableData().getPartialPathCost(), rnode.getRoutableData().getLowerBoundTotalPathCost());
						child.virtualMode = false;
					}
					
					
					this.nodesExpanded++;
					
//					if(this.debugExpansion && this.targetCon(con)) this.printInfo("");
				}	
			}
		}
	}
	
	
	
	private boolean hasNodePinFeed(RoutableNodeGroup child){//TODO this takes time, should be avoided
		boolean hasNodePinFeed = false;
		if(child.getNodeGroupSiblings().groupDelayType() == GroupDelayType.PINFEED){//.getAllWiresInNode()[0].getIntentCode() == IntentCode.NODE_PINFEED){//*IMUX*
			hasNodePinFeed = true;
		}
		return hasNodePinFeed;
	}
	
	private boolean targetCon(Connection con){
		return true;
	}
	
	private void addNodeToQueue(RoutableNodeGroup rnode, RoutableNodeGroup childRNode, NodeGroup thruImmuTg, Connection con) {
		RoutableData data = childRNode.getRoutableData();
		int countSourceUses = data.countSourceUses(con.source);
		
//		if(this.debugExpansion && this.targetCon(con)){
//			this.printInfo("\t\t childRNode " + childRNode.toString());
//		}
		
		float partial_path_cost = rnode.getRoutableData().getPartialPathCost();//upstream path cost
		
//		this.routerTimer.getRouteNodeCost.start();
		float rnodeCost = this.getRouteNodeCost(childRNode, thruImmuTg, con, countSourceUses);
//		this.routerTimer.getRouteNodeCost.finish();
		
		float new_partial_path_cost = partial_path_cost + rnodeCost;
		int childLevel = rnode.getRoutableData().getLevel() + 1;
		float new_lower_bound_total_path_cost;
		float expected_distance_cost = 0;
		float expected_wire_cost;
		if(childRNode.getRoutableType() == RoutableType.INTERRR){
			
//			if(this.debugExpansion) this.printInfo("\t\t target RNode " + con.targetName + " (" + con.sink.getTile().getColumn() + "," + con.sink.getTile().getRow() + ")");
			expected_distance_cost = this.expectMahatD(childRNode, con);
			
			expected_wire_cost = expected_distance_cost / (1 + countSourceUses);
			new_lower_bound_total_path_cost = (float) (new_partial_path_cost + this.mdWeight * expected_wire_cost + this.hopWeight * childLevel);
			
		}else{//lut input pin (sink)
			new_lower_bound_total_path_cost = new_partial_path_cost;
		}
		
//		if(this.debugExpansion && this.targetCon(con)){
//			System.out.println("\t\t partial_path_cost = " + partial_path_cost 
//								+ ", \n \t\t rnodeCost = " + rnodeCost
//								+ ", \n \t\t countSourceUses = " + countSourceUses
//								+ ", \n \t\t expected_distance_cost = " + expected_distance_cost
//								+ ", \n \t\t new_lower_bound_total_path_cost = " + new_lower_bound_total_path_cost);
//		}
		
		this.addRNodeToQueuePushing(con, childRNode, rnode, thruImmuTg, childLevel, new_partial_path_cost, new_lower_bound_total_path_cost);	
	}
	
	private void addRNodeToQueuePushing(Connection con, RoutableNodeGroup childRNode, RoutableNodeGroup rnode, NodeGroup thruImmuTg, int level, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
		RoutableData data = childRNode.getRoutableData();
		
		//TODO ---- TO DEAL WITH VIRTUAL RNODES, --------
		if(!data.isTouched()) {//visited or not
//			if(this.debugExpansion && this.targetCon(con)) this.printInfo("\t\t not touched");
			this.rnodesTouched.add(childRNode);
//			if(this.debugExpansion && this.targetCon(con)) this.printInfo("\t\t touched node size = "+this.rnodesTouched.size());
			data.setLowerBoundTotalPathCost(new_lower_bound_total_path_cost);
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			childRNode.setThruNodeGroup(thruImmuTg);
			if(rnode != null) data.setLevel(level);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
//			if(this.debugExpansion&& this.targetCon(con) ) this.printInfo("\t\t node added, queue size = " + this.queue.size());
			
		} else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {
			//queue is sorted by lower bound total cost
//			if(this.debugExpansion && this.targetCon(con)) this.printInfo("\t\t touched previously");
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			childRNode.setThruNodeGroup(thruImmuTg);
			if(rnode != null) data.setLevel(level);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
		}//TODO check if this block is really needed
		
	}
	
	private float getRouteNodeCost(RoutableNodeGroup rnode, NodeGroup thruImmuTg, Connection con, int countSourceUses) {
		//Present congestion cost
		float pres_cost = this.getPresentCongestionCost(countSourceUses, rnode.getOccupancy(), rnode.getPres_cost());
		float acc_cost = rnode.getAcc_cost();
		
		//add entry node cost to the Siblings
		EntryNode entry = thruImmuTg.entryNode();
		
		if(entry != null){
			pres_cost += this.getPresentCongestionCost(entry.countSourceUses(con.source), entry.getOcc(), entry.getPresCost());	
			acc_cost += entry.getAccCost();	
		}
		
		//Bias cost
		float bias_cost = 0;
		if(rnode.getRoutableType() == RoutableType.INTERRR) {
			Netplus net = con.getNet();
			bias_cost = 0.5f * rnode.getBase_cost() / net.fanout * 
					(Math.abs(rnode.getX() - net.x_geo) + Math.abs(rnode.getY() - net.y_geo)) / net.hpwl;
		}
		
//		if(this.debugExpansion && this.targetCon(con))
//			this.printInfo("\t\t rnode cost = b(n)*h(n)*p(n)/(1+sourceUsage) = " + rnode.base_cost + " * " + rnode.getAcc_cost()+ " * " + pres_cost + " / (1 + " + countSourceUses + ") + " + bias_cost);
		
		return rnode.getBase_cost() * acc_cost * pres_cost / (1 + countSourceUses) + bias_cost;
	}
	
	private float getPresentCongestionCost(int countSourceUses, int occupancy, float nodePresCost){
		boolean containsSource = countSourceUses!= 0;	
		//Present congestion cost
		float pres_cost;
		
		if(containsSource) {
			int overoccupancy = 0;
			overoccupancy = occupancy - Routable.capacity;
			if(overoccupancy < 0) {
				pres_cost = 1;
			}else{
				pres_cost = 1 + overoccupancy * this.pres_fac;
			}
		}else{
			pres_cost = nodePresCost;
		}
		
		return pres_cost;
	}
	
	private float expectMahatD(RoutableNodeGroup childRNode, Connection con){
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
		this.sinkPinTG = ((RoutableNodeGroup)con.getSinkRNode()).getNodeGroupSiblings().getSiblings()[1];
		
		// Add source to queue
		RoutableNodeGroup source = (RoutableNodeGroup) con.getSourceRNode();
		this.addRNodeToQueuePushing(con, source, null, source.getNodeGroupSiblings().getSiblings()[0], 0, 0, 0);
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
		
		for(RoutableNodeGroup rn:this.rnodesCreated.values()){	
			if(rn.childrenSet){
				sumChildren += rn.childrenAndThruGroup.size();
				sumRNodes++;
			}
			
			NodeGroup[] timingGroups = rn.getNodeGroupSiblings().getSiblings();
			sumTG += timingGroups.length;
			for(NodeGroup tg:timingGroups){
				
				Node entryNode = tg.entryNode();
				Node exitNode = tg.exitNode();
				
				if(entryNode != null){
					sumNodes += 1;
					sumWire += entryNode.getAllWiresInNode().length;//not always 1
				}
				//exit always exists
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

	public int getUsedRNodes() {
		
		return this.usedRNodes.size();
	}
}
