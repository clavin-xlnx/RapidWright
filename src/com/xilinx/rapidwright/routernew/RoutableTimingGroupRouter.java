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
	public CodePerfTracker t;
	
	public DelayEstimatorTable estimator;
	public TimingManager timingManager;
	public TimingModel timingModel;
	public TimingGraph timingGraph;
	private static final float MAX_CRITICALITY = 0.99f;
	private static final float CRITICALITY_EXPONENT = 1;
	private float MIN_REROUTE_CRITICALITY = 0.85f, REROUTE_CRITICALITY;
	private int MAX_PERCENTAGE_CRITICAL_CONNECTIONS = 3;
	private final List<Connection> criticalConnections;
	
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
	
	//map for solving congestion on entry nodes that are shared among siblings
	public Map<Connection, List<NodeWithFaninInfo>> connectionEntryNodes;
	
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
	public long nodesExpandedFirstIter;
	public int connectionsRoutedIteration;
	public long nodesPopedFromQueue;
	public long nodesPopedFromQueueFirstIter;
	
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
	        this.estimator = new DelayEstimatorTable(device,ictInfo);//DelayEstimatorTable<>(device,ictInfo, (short) 10, (short) 19, 0);
		}
		
		this.reservedNodes = new HashSet<>();
		this.rnodesCreated = new HashMap<>();
		this.dcpFileName = dcpFileName;
		this.nrOfTrials = nrOfTrials;
		this.t = t;
		
		this.connectionEntryNodes = new HashMap<>();
		this.criticalConnections = new ArrayList<>();
		
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
		
		this.rthHelper = new RouteThruHelper(this.design.getDevice());
		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesExpanded = 0;
		this.nodesExpandedFirstIter = 0;
		this.nodesPopedFromQueue = 0;
		this.nodesPopedFromQueueFirstIter = 0;
		
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
				if(RouterHelper.isRegularNetToBeRouted(n)){
					if(!timingDriven) 
						this.initializeNetAndCons(n, bbRange);
					else 
						this.initializeNetAndCons(n, bbRange, this.timingDriven);//TODO debugging
					
				}else if(RouterHelper.isOneTypePinNet(n)){
					this.reserveNet(n);
					this.iWireOneTypePin++;
					
				} else if(RouterHelper.isNoPinNets(n)){
					
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
			/*System.out.println(c.toString());
			System.out.println(sinkRNode.toStringEntriesAndExit());
			System.out.println();*/
		}
		if(n.getSinkPins().size() == 1) this.fanout1Net++;
	}
	
	public void initializeNetAndCons(Net n, short bbRange, boolean timingDriven){	
		EDIFNet edifNet = n.getLogicalNet();
		Map<Pair<SitePinInst, SitePinInst>, TimingEdge> spiAndTimingEdges = this.timingGraph.getSpiAndTimingEdges();
		Map<SitePinInst, TimingVertex> spiAndTimingVertices = this.timingGraph.getSpiAndTimingVertex();
		/*if(n.getName().equals("nca0")){
			System.out.println(n.getSource().toString() + " has a Timing Vertex " + spiAndTimingEdges.get(n.getSource()).toString());
		}*/
		
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
				
				//set TimingVertex/Edge of connection c
				if(!spiAndTimingVertices.containsKey(source)){
					System.err.println("Map<SitePinInst, TimingVertex> from TimingGraph does not contains source " 
							+ source.getName() + " of net " + n.getName() + ", # sink pin = " + n.getSinkPins().size());
				}else{
					c.setSourceTimingVertex(spiAndTimingVertices.get(source));
				}
				
				if(!spiAndTimingVertices.containsKey(sink)){
					System.err.println("Map<SitePinInst, TimingVertex> from TimingGraph does not contains sink " 
							+ sink.getName() + " of net " + n.getName() + ", # sink pin = " + n.getSinkPins().size());
				}else{
					c.setSinkTimingVertex(spiAndTimingVertices.get(sink));
				}
				
				/*if(c.getSourceTimingVertex() != null && c.getSinkTimingVertex() != null){
					c.setTimingEdge(this.timingGraph, edifNet, n);//TODO using the existing edges, in stead of creation new ones
				}else{
					System.err.println();
				}*/
				
				try{
					c.setTimingEdge(spiAndTimingEdges.get(new Pair<>(source, sink)));
				}catch(Exception e){
					System.out.println(c.toString());
					e.printStackTrace();
				}
				
				this.connections.add(c);
				c.setNet(np);
				np.addCons(c);
				this.icon++;
//				System.out.println("source spi toString = " + source.toString() +
//						", spi name = " + source.getName() + ", bel pin name = " + source.getBELPin().getName());
//				System.out.println(c.toString() + ", intra delay = " + c.timingEdge.getIntraSiteDelay());
			}
			if(n.getSinkPins().size() == 1) this.fanout1Net++;
		}else{
			this.reservePipsOfNet(n);
			this.iNullEDIFNet++;//this should not happen, already fixed, LUT6_2
			System.err.println("null EDIFNet for Net " + n.getName());
		}
	}
	
	public RoutableTimingGroup createRoutableNodeAndAdd(int index, SitePinInst sitePinInst, RoutableType type, TimingModel model, float base_cost_fac){
		RoutableTimingGroup routableTG = new RoutableTimingGroup(index, sitePinInst, type, model);
		routableTG.setBaseCost(base_cost_fac);
//		System.out.println(routableTG.getTimingGroup().getSiblings()[0].hashCode() + " " + sitePinInst.getConnectedNode().hashCode());
//		this.rnodesCreated.put(NodeWithFaninInfo.create(sitePinInst.getConnectedNode()), routableTG);
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
	
	public int routingRuntime(){
		long start = System.nanoTime();
		this.route();
		long end = System.nanoTime();
		int timeInMilliseconds = (int)Math.round((end-start) * Math.pow(10, -6));
		this.printTotalUsedNodes();
		this.getTotalNodesInResourceGraph();
		this.entryNodesSharing();
		System.out.println();
		return timeInMilliseconds;
	}
	
	public void entryNodesSharing(){
		float sum = 0;
		for(NodeWithFaninInfo n:RoutableTimingGroup.entryNodesExpanded){
			sum += n.entryHolders.size();
		}
		System.out.println("all entry nodes in cost map: " + RoutableTimingGroup.entryNodesExpanded.size());
		System.out.println("average shaing for used entry nodes: " + sum/RoutableTimingGroup.entryNodesExpanded.size());
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
		this.timingGraph.computeArrivalTimes();
		
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
			
			this.setRerouteCriticality(this.sortedListOfConnection);
			
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
			
			/*if(this.itry == 14){ 
				this.debugRoutingCon = true;
				for(Connection con:this.sortedListOfConnection){
					System.out.println(con.toStringTG());
					this.printConRNodes(con);
					System.out.println();
				}
			}
			this.debugRoutingCon = false;*/
			
			/*if(this.itry == 9){
				
				System.out.println("rnodes valid? " + this.isValidRouting());
				System.out.println("entry nodes valid? " + this.validEntryNodesRouting());
				
				for(Netplus np:this.nets){
					for(Connection con:np.getConnection()){
						System.out.println(con.toString());
						System.out.println(this.connectionEntryNodes.get(con));
						this.printConRNodes(con);
						System.out.println();
					}
				}
				
//				for(NodeWithFaninInfo entry:RoutableTimingGroup.entryNodes){
//					if(entry.isOverUsed()){
//						System.out.println("overused entry node: " + entry.toString());
//					}
////					if(entry.toString().equals("INT_X9Y102/INODE_W_1_FT1") && RoutableTimingGroup.entryNodeSources.containsKey(entry)){
////						System.out.println("node INT_X9Y102/INODE_W_1_FT1 sources = " + RoutableTimingGroup.entryNodeSources.get(entry).uniqueSize());
////					}
//				}
				System.out.println();
				
				for(NodeWithFaninInfo entry : RoutableTimingGroup.entryNodesExpanded){
					if(entry.toString().equals("INT_X18Y97/INT_INT_SDQ_7_INT_OUT0")){
						System.out.printf(entry.toString() + "\t\t sources = ");
						if(entry.isUsed()){
							System.out.println(entry.getSourcesSet().uniqueSize());
						}else{
							System.out.println();
						}
						System.out.println();
					}
				}
			}*/
			//TODO update timing and criticalities of connections
			String maxDelayString = String.format("%9s", "---");
			if(this.timingDriven) {
				this.timingManager.updateRouteDelays(this.sortedListOfConnection);
				this.timingManager.calculateArrivalRequireAndSlack();
				this.timingManager.calculateCriticality(this.sortedListOfConnection, MAX_CRITICALITY, CRITICALITY_EXPONENT);
				
				float maxDelay = (float) this.timingGraph.getMaxDelayPath().getWeight();
				maxDelayString = String.format("%9.3f", maxDelay);//TODO printed out in the statistic info
			}
			System.out.println(maxDelayString);
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
				
//				System.out.println(RoutableTimingGroup.entryNodePresHistCosts.size());
				
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
			
		}else if (con.getCriticality() > REROUTE_CRITICALITY) {
			this.routerTimer.rerouteCongestion.start();
			this.routeACon(con);
			this.routerTimer.rerouteCongestion.finish();
		}
	}
	
	public boolean conEntryNodeCongested(Connection con){
		this.routerTimer.checkOnEntryNodeCongestion.start();
		boolean congested = false;
		for(NodeWithFaninInfo n : this.connectionEntryNodes.get(con)){
			if(n != null){
				if(n.isOverUsed()){
					congested = true;
				}
			}
		}
		this.routerTimer.checkOnEntryNodeCongestion.finish();
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
		this.routerTimer.checkIfEntryNodesRoutingValid.start();
		boolean validEntryNodeRouting = true;
		for(NodeWithFaninInfo n : RoutableTimingGroup.entryNodesExpanded){
			if(n != null && n.isOverUsed()){
				validEntryNodeRouting = false;
			}
		}
		this.routerTimer.checkIfEntryNodesRoutingValid.finish();
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
//		System.out.println("iteration = " + this.itry);
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
		Set<RoutableTimingGroup> netRNodes = new HashSet<>();
		for(Netplus net:this.nets){	
			for(Connection c:net.getConnection()){
				netRNodes.addAll((Collection<? extends RoutableTimingGroup>) c.rnodes);
				this.hops += c.nodes.size() - 1;//hops counted using the number of nodes for all sinks
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
				if(isCyclic){
					for(Connection c:illegalTree.getConnection()){
						this.ripup(c);
					}
					//remove cycles
//					System.out.println(illegalTree.getNet().getName() + " cycle exists");
					graphHelper.cutOffCycles(illegalTree);//TODO clean version (update to router fields)
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
		for(NodeWithFaninInfo entry:this.connectionEntryNodes.get(con)){
			if(entry != null && entry.hasMultiFanin()){
				return true;
			}
		}
		return illegal;
	}
	
	public Node getIllegalEntryNode(Netplus illegalTree){
		
		for(Connection con:illegalTree.getConnection()){
			for(NodeWithFaninInfo entry:this.connectionEntryNodes.get(con)){
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
			List<NodeWithFaninInfo> entryNodes = new ArrayList<>();
			
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
		RoutableTimingGroup parent = null;	
		for(int i = con.rnodes.size() - 1; i >= 0; i--){
			RoutableTimingGroup rnode = (RoutableTimingGroup) con.rnodes.get(i);
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
			
//			Node thruEntryNode = rnode.getThruImmuTg().entryNode();
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
	
	public List<PIP> conPIPs(Connection con){
		List<PIP> conPIPs = new ArrayList<>();
		List<Node> conNodes = new ArrayList<>();
		
		for(int i = 0; i < con.rnodes.size() - 1; i++){		
			RoutableTimingGroup rtg = ((RoutableTimingGroup) (con.rnodes.get(i)));
			conNodes.add(rtg.getSiblingsTimingGroup().getExitNode());
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
			PIP pip = RouterHelper.findThePIPbetweenTwoNodes(nodeFormer.getAllWiresInNode(), nodeLatter);
			if(pip != null){
				conPIPs.add(pip);
			}else{
				System.err.println("Null PIP connecting node " + nodeFormer.toString() + " and node " + nodeLatter.toString());
			}
		}
		return conPIPs;
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
		if(this.debugRoutingCon && this.targetCon(con)) this.printInfo("routing for " + con.toStringTG());
		
		if(this.debugRoutingCon && this.targetCon(con)) System.out.println("target set " + con.getSinkRNode().isTarget() + ", "
				+ ((RoutableTimingGroup) con.getSinkRNode()).getSiblingsTimingGroup().hashCode());
		
		while(!this.targetReached(con)){
			
			RoutableTimingGroup rnode = (RoutableTimingGroup) queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			if(!rnode.childrenSet){
//				if(this.debugRoutingCon)System.out.println(rnode.toString() + " 's setChildren: ");
				this.rrgNodeId = rnode.setChildren(this.rrgNodeId, this.base_cost_fac, this.rnodesCreated, 
						this.reservedNodes, this.rthHelper, this.estimator);
			}
			this.routerTimer.rnodesCreation.finish();
			
			this.exploringAndExpansion(rnode, con);
		}
		
		this.finishRoutingACon(con);
		
//		this.printConRNodes(con);
	}
	
	public void printConRNodes(Connection con){
//		if(this.debugRoutingCon && this.targetCon(con)){
			this.printInfo(con.toString());
			printConEntryNodes(con);
			System.out.println(this.connectionEntryNodes.get(con));
			for(Routable rn:con.rnodes){
				this.printInfo(((RoutableTimingGroup)(rn)).toStringEntriesAndExit());
//				boolean immuTg = ((RoutableTimingGroup)(rn)).getThruImmuTg() != null;
//				if(immuTg) this.printInfo("\t" + ((RoutableTimingGroup)(rn)).getThruImmuTg().toString());//this thruImmuTg 
			}
			for(ImmutableTimingGroup tg:con.timingGroups){
				this.printInfo(tg.toString());
			}
			this.printInfo("");	
//		}
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
		
//		if(this.debugExpansion && this.targetCon(con)){
//			this.printInfo("\t" + " exploring rnode " + rnode.toString());
//		}
//		if(this.debugExpansion && this.targetCon(con)) 
//			this.printInfo("\t starting  queue size: " + this.queue.size());
		
		for(Pair<RoutableTimingGroup, ImmutableTimingGroup> childRNode : rnode.childrenImmuTG){
//			if(this.debugExpansion && this.targetCon(con)) this.printInfo("run here and check RNode " + childRNode.toString());
			RoutableTimingGroup child = childRNode.getFirst();
			ImmutableTimingGroup thruImmu = childRNode.getSecond();
			
			if(child.isTarget()){		
//				if(this.debugExpansion && this.targetCon(con)) this.printInfo("\t\t childRNode is the target");
//				if(thruImmu.entryNode() == null) System.err.println("null thruImmu at target STG");
				this.addNodeToQueue(rnode, child, thruImmu, con);
				this.nodesExpanded++;
				
			}else if(child.type == RoutableType.INTERRR){// && child.getSiblingsTimingGroup().getExitNode().getAllWiresInNode()[0].getIntentCode() != IntentCode.NODE_PINFEED){//.toString().contains("IMUX")){
				//TODO the second condition makes the router obviously slower: 2x
				if(child.isInBoundingBoxLimit(con)){
//					if(this.debugExpansion && this.targetCon(con)) this.printInfo("\t\t" + " add node to the queue");
					
//					if(child.getSiblingsTimingGroup().getSiblings().length > 1){
//						if(thruImmu.entryNode() == null) System.err.println("null thruImmu at INTERR STG");
//					}
					this.addNodeToQueue(rnode, child, thruImmu, con);
					this.nodesExpanded++;
					
//					if(this.debugExpansion && this.targetCon(con)) this.printInfo("");
				}	
			}
		}
	}
	
	private boolean hasNodePinFeed(RoutableTimingGroup child){//TODO this takes time, should be avoided
		this.routerTimer.rtgFilter.start();
		boolean hasNodePinFeed = false;
		if(child.getSiblingsTimingGroup().type() == GroupDelayType.PINFEED){//.getAllWiresInNode()[0].getIntentCode() == IntentCode.NODE_PINFEED){//*IMUX*
			hasNodePinFeed = true;
		}
		this.routerTimer.rtgFilter.finish();
		return hasNodePinFeed;
	}
	
	private boolean targetCon(Connection con){
//		return con.id == 4027 || con.id == 2244;
		return true;
	}
	
	private void addNodeToQueue(RoutableTimingGroup rnode, RoutableTimingGroup childRNode, ImmutableTimingGroup thruImmuTg, Connection con) {
//		this.routerTimer.addRNodeToQueueEvaluation.start();
		RoutableData data = childRNode.rnodeData;
		int countSourceUses = data.countSourceUses(con.source);
		
//		if(this.debugExpansion && this.targetCon(con)){
//			this.printInfo("\t\t childRNode " + childRNode.toString());
//		}
		
		float partial_path_cost = rnode.rnodeData.getPartialPathCost();//upstream path cost
		
//		this.routerTimer.getRouteNodeCost.start();
		float rnodeCost = this.getRouteNodeCost(childRNode, thruImmuTg, con, countSourceUses);
//		this.routerTimer.getRouteNodeCost.finish();
		
//		float new_partial_path_cost = partial_path_cost + (1 - con.getCriticality()) * rnodeCost + con.criticality * thruImmuTg.getDelay()/20f;//upstream path cost + cost of node under consideration
		float new_partial_path_cost = partial_path_cost + rnodeCost + 0.0001f * thruImmuTg.getDelay()/20f;//upstream path cost + cost of node under consideration
		int childLevel = rnode.rnodeData.getLevel() + 1;
		float new_lower_bound_total_path_cost;
		float expected_distance_cost = 0;
		float expected_wire_cost;
		short delay = 0;
		if(childRNode.type == RoutableType.INTERRR){
			
//			if(this.debugExpansion) this.printInfo("\t\t target RNode " + con.targetName + " (" + con.sink.getTile().getColumn() + "," + con.sink.getTile().getRow() + ")");
			expected_distance_cost = this.expectMahatD(childRNode, con);
			/*if(thruImmuTg.entryNode() != null){
				childLevel += 1;
			}*/
			
			expected_wire_cost = expected_distance_cost / (1 + countSourceUses);
//			new_lower_bound_total_path_cost = (float) (new_partial_path_cost + (1 - con.getCriticality() > 1?0.99:con.getCriticality()) * this.mdWeight * expected_wire_cost + this.hopWeight * childLevel);
			
			new_lower_bound_total_path_cost = (float) (new_partial_path_cost + (1 - con.getCriticality() > 1?0.99:con.getCriticality()) * this.mdWeight * expected_wire_cost + this.hopWeight * childLevel);
			
			
			if(this.timingDriven){
				ImmutableTimingGroup immuTG = thruImmuTg;
				
				/*if(debugTiming) {
					System.out.println("sinkPin ImmutableTimingGroup chosen with exit node: " + this.sinkPinTG.exitNode().toString());
					if(immuTG.entryNode() != null)
						System.out.printf("Get min delay from ImmuTimingGroup ( " + immuTG.entryNode().toString() + " -> " + immuTG.exitNode().toString() + " )");
					else 
						System.out.printf("Get min delay from ImmuTimingGroup exit node only ( " + immuTG.exitNode().toString() + " )");
				
					if(this.sinkPinTG.entryNode() != null) 
						System.out.println(" to ( " + this.sinkPinTG.entryNode().toString() + " -> " + this.sinkPinTG.exitNode().toString() + " )");
					else
						System.out.println(" to ( " + this.sinkPinTG.exitNode().toString() + " )");
					
					if(immuTG.exitNode().toString().equals("INT_X11Y97/IMUX_W15")){
						System.out.println(immuTG.exitNode().getAllWiresInNode()[0].getIntentCode() + ", node INT_X11Y97/IMUX_W15 downhill:");
						for(Node node : immuTG.exitNode().getAllDownhillNodes()){
							System.out.println(node.getSitePin().toString());
							System.out.printf(node.toString() + ", " + node.getAllWiresInNode()[0].getIntentCode() + ", is routethru? ");
							System.out.println(this.rthHelper.isRouteThru(immuTG.exitNode(), node));
						}
					}
				}*/
				
				if(childRNode.groupType != GroupDelayType.GLOBAL) {
					try{
						delay = this.estimator.getMinDelayToSinkPin(immuTG, this.sinkPinTG);
					}catch(Exception e){
//						System.out.println("sinkPin ImmutableTimingGroup chosen with exit node: " + this.sinkPinTG.exitNode().toString());
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
				}
				if(debugTiming) System.out.println(" delay = " + delay);
				
				new_lower_bound_total_path_cost +=  delay/20f;
			}
			
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
		
//		this.routerTimer.addRNodeToQueueEvaluation.finish();
		
//		this.routerTimer.addRNodeToQueuePushing.start();
		this.addRNodeToQueueSetting(con, childRNode, rnode, thruImmuTg, childLevel, new_partial_path_cost, new_lower_bound_total_path_cost);
//		this.routerTimer.addRNodeToQueuePushing.finish();
	}
	
	private void addRNodeToQueueSetting(Connection con, RoutableTimingGroup childRNode, RoutableTimingGroup rnode, ImmutableTimingGroup thruImmuTg, int level, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
		RoutableData data = childRNode.rnodeData;
		
		if(!data.isTouched()) {
//			if(this.debugExpansion && this.targetCon(con)) this.printInfo("\t\t not touched");
			this.rnodesTouched.add(data);
//			if(this.debugExpansion && this.targetCon(con)) this.printInfo("\t\t touched node size = "+this.rnodesTouched.size());
			data.setLowerBoundTotalPathCost(new_lower_bound_total_path_cost);
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			childRNode.setThruImmuTg(thruImmuTg);
			if(rnode != null) data.setLevel(level);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
//			if(this.debugExpansion&& this.targetCon(con) ) this.printInfo("\t\t node added, queue size = " + this.queue.size());
			
		} else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {
			//queue is sorted by lower bound total cost
//			if(this.debugExpansion && this.targetCon(con)) this.printInfo("\t\t touched previously");
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
		float acc_cost = rnode.getAcc_cost();
		//Bias cost
		/*float bias_cost = 0;
		if(rnode.type == RoutableType.INTERRR) {
			Netplus net = con.getNet();
			bias_cost = 0.5f * rnode.base_cost / net.fanout * 
					(Math.abs(rnode.getCenterX() - net.x_geo) + Math.abs(rnode.getCenterY() - net.y_geo)) / net.hpwl;
		}*/
		
		//add entry node cost to the Siblings
		NodeWithFaninInfo entry = thruImmuTg.entryNode();
		
		if(entry != null){
			pres_cost += this.getPresentCongestionCost(entry.countSourceUses(con.source), entry.getOcc(), entry.getPresCost());	
			acc_cost += entry.getAccCost();	
		}
		
		
		
//		if(this.debugExpansion && this.targetCon(con))
//			this.printInfo("\t\t rnode cost = b(n)*h(n)*p(n)/(1+sourceUsage) = " + rnode.base_cost + " * " + rnode.getAcc_cost()+ " * " + pres_cost + " / (1 + " + countSourceUses + ") + " + bias_cost);
		
		return rnode.base_cost * acc_cost * pres_cost / (1 + countSourceUses);// + bias_cost;
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
	
	private float expectMahatD(RoutableTimingGroup childRNode, Connection con){
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
}
