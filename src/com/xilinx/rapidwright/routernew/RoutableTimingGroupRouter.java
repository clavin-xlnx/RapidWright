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
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.TimingGroup;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingModel;

public class RoutableTimingGroupRouter{
	public Design design;
	public String dcpFileName;
	public int nrOfTrials;
	public CodePerfTracker t;
	
	public List<RNetplus> nets;
	public List<RConnection> connections;
	public int fanout1Net;
	
//	public Map<Net, List<Node>> netsReservedNodes;
	public PriorityQueue<RQueueElement> queue;
	public Collection<RoutableData> rnodesTouched;
	public Map<TimingGroup, RoutableTimingGroup> rnodesCreated;//node and rnode pair
	public TimingModel timingModel;
	
	public List<RConnection> sortedListOfConnection;
	public List<RNetplus> sortedListOfNetplus;

	
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
	
	public boolean trial = true;
	public boolean debugRoutingCon = true;
	public boolean debugExpansion = false;
	
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
			float base_cost_fac){
		this.design = design;
		this.queue = new PriorityQueue<>(RComparators.PRIORITY_COMPARATOR);
		this.rnodesTouched = new ArrayList<>();
		
		TimingManager tm = new TimingManager(this.design);
		this.timingModel = tm.getTimingModel();
		
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
		this.rrgNodeId = this.initializeNetsCons(bbRange, this.rrgNodeId, this.base_cost_fac);
				
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfNetplus = new ArrayList<>();
		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesExpanded = 0;
		
		this.usedRNodes = new HashSet<>();
		this.overUsedRNodes = new HashSet<>();
		this.illegalRNodes = new HashSet<>();
	}
	
	public int initializeNetsCons(short bbRange, int rrgNodeId, float base_cost_fac){
		int inet = 0;
		int icon = 0;
		
		this.nets = new ArrayList<>();
		this.connections = new ArrayList<>();
		
		DesignTools.createMissingSitePinInsts(this.design);
		
		for(Net n:this.design.getNets()){	
			if(n.getPins().size() == 1|| (n.getSource() == null && n.getSinkPins().size() > 0)){
				for(SitePinInst pin:n.getPins()){
					/*RoutableType type;
					if(pin.isOutPin()){
						type = RoutableType.SOURCERR;
					}else{
						type = RoutableType.SINKRR;
					}*/
					RoutableTimingGroup reservedRRGNode = new RoutableTimingGroup(rrgNodeId, pin, RoutableType.RESERVED, this.timingModel);
					reservedRRGNode.setBaseCost(Float.MAX_VALUE - 1);
					this.rnodesCreated.put(reservedRRGNode.getTimingGroup(), reservedRRGNode);
					rrgNodeId++;
					
				}
			} else if(n.isClockNet() || n.isStaticNet() || n.getName().equals("clk")){
				
			} else if(n.getSource() != null && n.getSinkPins().size() > 0){
				n.unroute();
				RNetplus np = new RNetplus(inet, bbRange, n);
				this.nets.add(np);
				inet++;
				
				SitePinInst source = n.getSource();
				RoutableTimingGroup sourceRNode = new RoutableTimingGroup(rrgNodeId, source, RoutableType.SOURCERR, this.timingModel);
				sourceRNode.setBaseCost(base_cost_fac);
				this.rnodesCreated.put(sourceRNode.getTimingGroup(), sourceRNode);	
				rrgNodeId++;
				
				for(SitePinInst sink:n.getSinkPins()){
					RConnection c = new RConnection(icon, source, sink);	
					c.setSourceRNode(sourceRNode);
					
					//create RNode of the sink pin external wire up front 
					RoutableTimingGroup sinkRNode = new RoutableTimingGroup(rrgNodeId, sink, RoutableType.SINKRR, this.timingModel);
					sinkRNode.setBaseCost(base_cost_fac);
					c.setSinkRNode(sinkRNode);
					this.rnodesCreated.put(sinkRNode.getTimingGroup(), sinkRNode);
					rrgNodeId++;
					
					this.connections.add(c);
					c.setNet(np);//TODO new and set its TimingEdge for timing-driven version
					np.addCons(c);
					icon++;
				}
				if(n.getSinkPins().size() == 1)
					this.fanout1Net++;
			}	
		}
		return rrgNodeId;
	}
	
	public void findNodesConflicts(){
		Map<Node, Integer> nodesUsage = new HashMap<>();
		Map<Net, Set<Node>> netNodes = new HashMap<>();

		for(Net net:this.design.getNets()){
			
			Set<Node> nodes = new HashSet<>();
			
			if(net.hasPIPs()){
				for(PIP p: net.getPIPs()){
					Node startNode = p.getStartNode();
					if(!nodesUsage.containsKey(startNode)){
						nodesUsage.put(startNode, 1);

					}else{
						nodesUsage.put(startNode, nodesUsage.get(startNode) + 1);
					}
					nodes.add(startNode);
					
					Node endNode = p.getEndNode();
					if(!nodesUsage.containsKey(endNode)){
						nodesUsage.put(endNode, 1);

					}else{
						nodesUsage.put(endNode, nodesUsage.get(endNode) + 1);
					}
					nodes.add(endNode);
				}
			}else{
				for(SitePinInst spi:net.getPins()){
					Node node = spi.getConnectedNode();
					if(!nodesUsage.containsKey(node)){
						nodesUsage.put(node, 1);
					}else{
						nodesUsage.put(node, nodesUsage.get(node) + 1);
					}
					nodes.add(node);
				}
			}
			
			netNodes.put(net, nodes);
			
		}
		
		
		Set<Node> conflictedNodes = new HashSet<>();
		for(Node node:nodesUsage.keySet()){
			if(nodesUsage.get(node) > 1){
				conflictedNodes.add(node);
			}
		}
		
		Set<Net> conflictedNets = new HashSet<>();
		for(Net n:netNodes.keySet()){
			for(Node nn:netNodes.get(n)){
				if(conflictedNodes.contains(nn)){
					conflictedNets.add(n);
				}
			}
		}
		
		for(Net n : conflictedNets){
			System.out.println(n.toString());
		}
		
		
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
		Collections.sort(this.sortedListOfConnection, RComparators.FanoutBBConnection);	
		
		this.sortedListOfNetplus = new ArrayList<>();
		this.sortedListOfNetplus.addAll(this.nets);
		Collections.sort(this.sortedListOfNetplus, RComparators.FanoutNet);
		
		//initialize router
		this.initializeRouter(this.initial_pres_fac, this.pres_fac_mult, this.acc_fac);
				
		//do routing
		boolean validRouting;
        List<RNetplus> trialNets = new ArrayList<>();
        for(RNetplus net : this.sortedListOfNetplus){
        	if(net.getNet().getName().equals("n7a2")){
        		trialNets.add(net);
        	}
        }
        
		while(this.itry < this.nrOfTrials){
			this.iterationStart = System.nanoTime();
			
			this.connectionsRoutedIteration = 0;	
			validRouting = true;	
			if(this.trial) this.printInfo("iteration " + this.itry + " begins");
			
			if(!this.trial){
				for(RConnection con:this.sortedListOfConnection){
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
			}else{
				for(RNetplus np : trialNets){
					for(RConnection c : np.getConnection()){
						if(this.itry == 1){
							this.routerTimer.firstIteration.start();
							this.routeACon(c);
							this.routerTimer.firstIteration.finish();
						}else if(c.congested()){
							this.routerTimer.rerouteCongestion.start();
							/*this.router.debugExpansion = true;
							this.router.debugRoutingCon = true;*/
							this.routeACon(c);
							this.routerTimer.rerouteCongestion.finish();
						}
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
	public void staticticsInfo(List<RConnection> connections, 
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
		for(RNetplus net:this.nets){	
			for(RConnection c:net.getConnection()){
				netRNodes.addAll((Collection<? extends RoutableTimingGroup>) c.rnodes);
				this.hops += c.rnodes.size() - 1;//hops for all sinks
			}
			for(RoutableTimingGroup rnode:netRNodes){
				this.manhattanD += rnode.getManhattanD();
			}
			netRNodes.clear();
		}
	}
	
	public void getOverusedAndIllegalRNodesInfo(List<RConnection> connections) {
		this.usedRNodes.clear();
		this.overUsedRNodes.clear();
		this.illegalRNodes.clear();
		for(RConnection conn : connections) {
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
	
	public int getIllegalNumRNodes(List<RConnection> cons){
		Set<Integer> illegal = new HashSet<>();	
		for(RConnection c:cons){
			for(Routable rn:c.rnodes){
				if(rn.illegal()){
					illegal.add(rn.hashCode());
				}
			}
		}	
		return illegal.size();	
	}
	
	public void fixIllegalTree(List<RConnection> cons) {
//		this.printInfo("checking if there is any illegal node");	
		int numIllegal = this.getIllegalNumRNodes(cons);	
		if(numIllegal > 0){
//			this.printInfo("There are " + numIllegal + " illegal routing tree nodes");
			
			List<RNetplus> illegalTrees = new ArrayList<>();
			for(RNetplus net : this.nets) {
				boolean illegal = false;
				for(RConnection con : net.getConnection()) {
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
			for(RNetplus illegalTree : illegalTrees){
				Routable illegalRNode;
				while((illegalRNode = illegalTree.getIllegalNode()) != null){
					List<RConnection> illegalCons = new ArrayList<>();
					for(RConnection con : illegalTree.getConnection()) {
						for(Routable rnode : con.rnodes) {
							if(rnode.equals(illegalRNode)) {
								illegalCons.add(con);
							}
						}
					}
					
					//fixing the illegal trees, since there is no criticality info, use the hops info
					//Find the illegal connection with maximum number of RNodes (hops)
					RConnection maxCriticalConnection = illegalCons.get(0);
					for(RConnection illegalConnection : illegalCons) {
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
					for(RConnection illegalConnection : illegalCons) {
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
		}
	}
	
	public void ripup(RConnection con){
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
	public void add(RConnection con){
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
		for(RNetplus np:this.sortedListOfNetplus){
			Set<PIP> netPIPs = new HashSet<>();
			
			for(RConnection c:np.getConnection()){
				netPIPs.addAll(this.conPIPs(c));
			}
			np.getNet().setPIPs(netPIPs);
		}
		
		this.checkPIPsUsage();
//		this.checkNetRoutedPins();
//		this.printWrittenPIPs();
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
	
	public void checkInvalidlyRoutedNets(String netname){
		boolean foundInRWrouter = false;
		for(RNetplus net:this.sortedListOfNetplus){
			if(net.getNet().getName().equals(netname)){
				foundInRWrouter = true;
				System.out.println(net.getNet().toString());
				for(RConnection c: net.getConnection()){
					System.out.println(((RoutableTimingGroup)c.getSinkRNode()).getTimingGroup().toString() + " - " + c.sink.getName());
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
	
	public List<PIP> conPIPs(RConnection con){
		List<PIP> conPIPs = new ArrayList<>();
		
		for(int i = con.rnodes.size() -1; i > 0; i--){
			Node nodeFormer = ((RoutableTimingGroup) (con.rnodes.get(i))).getTimingGroup().getLastNode();
			Node nodeLatter = ((RoutableTimingGroup) (con.rnodes.get(i-1))).getTimingGroup().getNodes().get(0);
			
			Wire pipStartWire = this.findEndWireOfNode(nodeFormer.getAllWiresInNode(), nodeLatter.getTile());
			
			if(pipStartWire != null){
				PIP pip = new PIP(nodeLatter.getTile(), pipStartWire.getWireIndex(), nodeLatter.getWire());
				conPIPs.add(pip);
			}else{
				System.out.println("pip start wire is null");
			}			
		}
		return conPIPs;
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
		Set<RConnection> congestedCons = new HashSet<>();
//		Map<Netplus<Node>, Integer> congestedNets = new HashMap<>();
		for(RConnection con:this.sortedListOfConnection){
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
		for(RConnection con:congestedCons){
			System.out.println(con.toString());
			for(Routable rn : con.rnodes){
				if(rn.overUsed()) System.out.println("\t"+ rn.toString());
			}
			System.out.println();
		}
	}
	
	public boolean targetReached(RConnection con){
		if(this.queue.size() > 0){
			return this.queue.peek().rnode.isTarget();
		}else{//dealing with null pointer exception
			System.out.println("queue is empty");
			return false;
		}
	}

	public void routeACon(RConnection con){
		this.prepareForRoutingACon(con);
		if(this.debugRoutingCon) this.printInfo("routing for " + con.toString());
		
		while(!this.targetReached(con)){
			this.nodesExpanded++;
			
			if(this.queue.isEmpty()){
				System.out.println(this.nodesExpanded);
				System.out.println(con.getNet().getNet().getName() + " " + con.source.getName() + " " + con.sink.getName());
				throw new RuntimeException("Queue is empty: target unreachable?");
			}
			
			RoutableTimingGroup rnode = (RoutableTimingGroup) queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			if(!rnode.childrenSet){
				this.rrgNodeId = rnode.setChildren(this.rrgNodeId, this.base_cost_fac, this.rnodesCreated);
			}
			this.routerTimer.rnodesCreation.finish();
			
			this.exploringAndExpansion(rnode, con);
		}
		
		this.finishRoutingACon(con);
		
		this.printConRNodes(con);
	}
	
	public void printConRNodes(RConnection con){
		if(this.debugRoutingCon){
			for(Routable rn:con.rnodes){
				this.printInfo(((RoutableTimingGroup)(rn)).toString());
			}
			this.printInfo("");	
		}	
	}
	
	public void finishRoutingACon(RConnection con){
		//save routing in connection class
		this.saveRouting(con);
		((RoutableTimingGroup)con.getSinkRNode()).target = false;
		// Reset path cost
		this.resetPathCost();
		
		this.add(con);
	}
	
	public void saveRouting(RConnection con){
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
	
	public void exploringAndExpansion(RoutableTimingGroup rnode, RConnection con){
		this.nodesExpanded++;
		
		if(this.debugExpansion){
			this.printInfo("\t" + " exploring rnode " + rnode.toString());
		}
		if(this.debugExpansion) this.printInfo("\t starting  queue size: " + this.queue.size());
		for(RoutableTimingGroup childRNode:rnode.children){
			
			if(childRNode.isTarget()){		
				if(this.debugExpansion) this.printInfo("\t\t childRNode is the target");
				this.addNodeToQueue(rnode, childRNode, con);
				
			}else if(childRNode.type.equals(RoutableType.INTERRR)){
				//traverse INT tiles only, otherwise, the router would take CLB sites as next hop candidates
				//this can be done by downsizing the created rnodes
				if(childRNode.isInBoundingBoxLimit(con)){
					if(this.debugExpansion) this.printInfo("\t\t" + " add node to the queue");
					this.addNodeToQueue(rnode, childRNode, con);
					if(this.debugExpansion) this.printInfo("");
				}	
			}
		}
	}
	
	private void addNodeToQueue(RoutableTimingGroup rnode, RoutableTimingGroup childRNode, RConnection con) {
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
			this.queue.add(new RQueueElement(childRNode, new_lower_bound_total_path_cost));
			if(this.debugExpansion) this.printInfo("\t\t node added, queue size = " + this.queue.size());
			
		} else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {
			//queue is sorted by lower bound total cost
			if(this.debugExpansion) this.printInfo("\t\t touched previously");
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.rnodeData.getLevel()+1);
			this.queue.add(new RQueueElement(childRNode, new_lower_bound_total_path_cost));
		}
	}
	
	private float getRouteNodeCost(RoutableTimingGroup rnode, RConnection con, int countSourceUses) {
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
			RNetplus net = con.getNet();
			bias_cost = 0.5f * rnode.base_cost / net.fanout * 
					(Math.abs(rnode.getCenterX() - net.x_geo) + Math.abs(rnode.getCenterY() - net.y_geo)) / net.hpwl;
		}
		
		/*if(this.debugExpansion)
			this.printInfo("\t\t rnode cost = b(n)*h(n)*p(n)/(1+sourceUsage) = " + rnode.base_cost + " * " + data.getAcc_cost()+ " * " + pres_cost + " / (1 + " + countSourceUses + ") + " + bias_cost);
		*/
		return rnode.base_cost * data.getAcc_cost() * pres_cost / (1 + countSourceUses) + bias_cost;
	}
	
	private float expectMahatD(RoutableTimingGroup childRNode, RConnection con){
		float md;
		if(this.itry == 1){
			md = Math.abs(childRNode.getCenterX() - con.sink.getTile().getColumn()) + Math.abs(childRNode.getCenterY() - con.sink.getTile().getRow());
		}else{
			md = Math.abs(childRNode.getCenterX() - con.getSinkRNode().getCenterX()) + Math.abs(childRNode.getCenterY() - con.getSinkRNode().getCenterY());
		}
		return md;
	}
	
	public void prepareForRoutingACon(RConnection con){
		this.ripup(con);
		
		this.connectionsRouted++;
		this.connectionsRoutedIteration++;
		// Clear previous route of the connection
		con.resetConnection();
		// Clear the priority queue
		this.queue.clear();	
		
		//set the sink rrg node of con as the target
		RoutableTimingGroup sink = (RoutableTimingGroup) con.getSinkRNode();
		sink.target = true;
		
		// Add source to queue
		RoutableTimingGroup source = (RoutableTimingGroup) con.getSourceRNode();
		this.addRNodeToQueue(source, null, 0, 0);
	}
	
	public float checkAverageNumWires(){
		float aver = 0;
		float sum = 0;
		for(RoutableTimingGroup rn:this.rnodesCreated.values()){
			for(Node node:rn.getTimingGroup().getNodes()){
				sum += node.getAllUphillNodes().size();
			}
		}
		aver = sum / this.rnodesCreated.values().size();
		
		return aver;
	}
	
	public float checkAverageNumNodes(){
		float aver = 0;
		float sum = 0;
		for(RoutableTimingGroup rn:this.rnodesCreated.values()){
			sum += rn.getTimingGroup().getNodes().size();
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
		System.out.println("Num con to be routed: " + this.connections.size());
		System.out.println("Num net to be routed: " + this.nets.size());
		System.out.println("Num 1-sink net: " + this.fanout1Net);
		System.out.println("------------------------------------------------------------------------------");
	}
}