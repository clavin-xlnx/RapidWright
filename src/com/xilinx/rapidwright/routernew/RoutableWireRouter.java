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

public class RoutableWireRouter{
	public Design design;
	public String dcpFileName;
	public int nrOfTrials;
	public CodePerfTracker t;
	
	public List<Netplus> nets;
	public List<Connection> connections;
	public int fanout1Net;
	
	public PriorityQueue<QueueElement> queue;
	public Collection<RoutableData> rnodesTouched;
	public Map<Wire, RoutableWire> rnodesCreated;
	public Set<Wire> reserved;
	
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
	
	public float mdWeight;
	public float hopWeight;
	public float averFanoutRNodes;
	
	public int rrgNodeId;
	public int firstIterRNodes;
	public float firstIterRouting;
	
	public int connectionsRouted;
	public long nodesExpanded;
	public long nodesExpandedFirstIter;
	public int connectionsRoutedIteration;
	public long nodesPopedFromQueue;
	public long nodesPopedFromQueueFirstIter;
	
	public Set<Integer> overUsedRNodes;
	public Set<Integer> usedRNodes;
	public Set<Integer> illegalRNodes;//nodes that have multiple drivers in a net
	
	public long hops;
	public float manhattanD;
	
	public boolean trial = false;
	public boolean debugRoutingCon = false;
	public boolean debugExpansion = false;
	public float firtRnodeT;
	
	public RoutableWireRouter(Design design,
			String dcpFileName,
			int nrOfTrials,
			CodePerfTracker t,
			short bbRange,
			float mdWeight,
			float hopWeight,
			float initial_pres_fac, 
			float pres_fac_mult, 
			float acc_fac){
		this.design = design;
		this.queue = new PriorityQueue<>(Comparators.PRIORITY_COMPARATOR);
		this.rnodesTouched = new ArrayList<>();
		
		this.rnodesCreated = new HashMap<>();
		this.reserved = new HashSet<>();
		this.dcpFileName = dcpFileName;
		this.nrOfTrials = nrOfTrials;
		this.t = t;
		
		this.initial_pres_fac = initial_pres_fac;
		this.pres_fac_mult = pres_fac_mult;
		this.acc_fac = acc_fac;
		this.mdWeight = mdWeight;
		this.hopWeight = hopWeight;
		
		this.routerTimer = new RouterTimer();
		this.fanout1Net = 0;
		this.rrgNodeId = 0;
		this.rrgNodeId = this.initializeNetsCons(bbRange, this.rrgNodeId);
				
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfNetplus = new ArrayList<>();
		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesExpanded = 0;
		
		this.usedRNodes = new HashSet<>();
		this.overUsedRNodes = new HashSet<>();
		this.illegalRNodes = new HashSet<>();
	}
	
	public int initializeNetsCons(short bbRange, int rrgNodeId){
		int inet = 0;
		int icon = 0;
		
		this.nets = new ArrayList<>();
		this.connections = new ArrayList<>();
		
		DesignTools.createMissingSitePinInsts(this.design);
		
		for(Net n:this.design.getNets()){	
			if(n.getPins().size() == 1 || (n.getSource() == null && n.getSinkPins().size() > 0)){
				for(SitePinInst pin:n.getPins()){
					Wire w = new Wire(pin.getTile(), pin.getConnectedWireIndex());
					this.reserved.add(w);
				}
			} else if(n.isClockNet() || n.isStaticNet()){
				
				for(PIP pip:n.getPIPs()){
					Node nodeStart = pip.getStartNode();
					for(Wire wire : nodeStart.getAllWiresInNode()){
						this.reserved.add(wire);
					}
					Node nodeEnd = pip.getEndNode();
					for(Wire wire : nodeEnd.getAllWiresInNode()){
						this.reserved.add(wire);
					}
				}
				
			} else if(n.getSource() != null && n.getSinkPins().size() > 0){
				n.unroute();
				Netplus np = new Netplus(inet, bbRange, n);
				this.nets.add(np);
				inet++;
				
				SitePinInst source = n.getSource();
				RoutableWire sourceRNode = new RoutableWire(rrgNodeId, source, RoutableType.SOURCERR);
				this.rnodesCreated.put(sourceRNode.wire, sourceRNode);	
				rrgNodeId++;
				
				for(SitePinInst sink:n.getSinkPins()){
					Connection c = new Connection(icon, source, sink);	
					c.setSourceRNode(sourceRNode);
					
					//create RNode of the sink pin external wire up front 
					RoutableWire sinkRNode = new RoutableWire(rrgNodeId, sink, RoutableType.SINKRR);
					c.setSinkRNode(sinkRNode);
					this.rnodesCreated.put(sinkRNode.wire, sinkRNode);
					rrgNodeId++;
					
					this.connections.add(c);
					c.setNet(np);//TODO new and set its TimingEdge for timing-driven version
					np.addCons(c);
					icon++;
				}
				if(n.getFanOut() == 1)
					this.fanout1Net++;
			}	
		}
		return rrgNodeId;
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
		this.routerTimer.pipsAssignment.start();
		this.pipsAssignment();
		this.routerTimer.pipsAssignment.finish();
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
        	if(net.getNet().getName().equals("opr[15]")){
        		trialNets.add(net);
        	}
        }
        
		while(this.itry < this.nrOfTrials){
			this.iterationStart = System.nanoTime();
			
			this.connectionsRoutedIteration = 0;	
			validRouting = true;	
			if(this.trial) this.printInfo("iteration " + this.itry + " begins");
			
			if(!this.trial){
				for(Connection con:this.sortedListOfConnection){
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
				for(Netplus np : trialNets){
					for(Connection c : np.getConnection()){
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
			
			if(this.itry == 1){
				this.nodesExpandedFirstIter = this.nodesExpanded;
				this.nodesPopedFromQueueFirstIter = this.nodesPopedFromQueue;
			}
		
			//check if routing is valid
			validRouting = this.isValidRouting();
			
			//fix illegal routing trees if any
			if(validRouting){
				this.routerTimer.rerouteIllegal.start();
				System.out.println("valid routing, assign nodes to connections:");
				//for fixing illegalTree using nodes
				for(Connection con:this.sortedListOfConnection){
					con.newNodes();
//					System.out.println(con.toString());
					for(Routable rn:con.rnodes){
						Node n = rn.getNode();
						if(!con.nodes.contains(n)){
							con.nodes.add(n);//wire router should check if node has been added or not, different wires may belong to same node
						}
					}
				}
				System.out.println("starting fix illegal routing tree:");
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
	
			if (validRouting) {	
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
		for(RoutableWire rnode:this.rnodesCreated.values()){
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
		
		if(this.itry == 1){
			this.firstIterRNodes = this.rnodesCreated.size();
			this.firstIterRouting = (float)((iterEnd - iterStart - rnodesT)*1e-9);
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
			this.pres_fac = this.initial_pres_fac;
		} else {
			this.pres_fac *= this.pres_fac_mult;
		}
		this.updateCost(this.pres_fac, this.acc_fac);
	}
	
	private void updateCost(float pres_fac, float acc_fac) {
		for(RoutableWire rnode:this.rnodesCreated.values()){
			int overuse = rnode.getOccupancy() - Routable.capacity;
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
		for(RoutableWire rn:this.rnodesCreated.values()){
			if(rn.childrenSet){
				sumChildren += rn.children.size();
				sumRNodes++;
			}
			if(rn.overUsed() || rn.illegal()){
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
	
	public void ripup(Connection con){
		RoutableWire parent = null;
		for(int i = con.rnodes.size() - 1; i >= 0; i--){
			RoutableWire rnode = (RoutableWire) con.rnodes.get(i);
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
		RoutableWire parent = null;
		for(int i = con.rnodes.size()-1; i >= 0; i--){
			RoutableWire rnode = (RoutableWire) con.rnodes.get(i);
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
				netPIPs.addAll(this.conPIPs(c));
			}
			np.getNet().setPIPs(netPIPs);
		}
//		this.checkInvalidlyRoutedNets("n199");
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
		for(Netplus net:this.sortedListOfNetplus){
			if(net.getNet().getName().equals(netname)){
				foundInRWrouter = true;
				System.out.println(net.getNet().toString());
				for(Connection c: net.getConnection()){
					
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
	
	//written pips of connections based on wire
	public List<PIP> conPIPs(Connection con){
		List<PIP> conPIPs = new ArrayList<>();
		List<Node> nodes = new ArrayList<>();
		
		for(int i = 0; i < con.rnodes.size(); i++){
			Node node = ((RoutableWire) (con.rnodes.get(i))).wire.getNode();
			if(!nodes.contains(node)){
				nodes.add(node);
			}
		}
		
		for(int i = nodes.size() -1; i > 0; i--){
			Node nodeFormer = nodes.get(i);
			Node nodeLatter = nodes.get(i - 1);
			
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
		for(RoutableWire rn:this.rnodesCreated.values()){
			sum += rn.base_cost;
			costs.add(rn.base_cost);
		}
		aver = sum/this.rnodesCreated.size();
		System.out.println(aver);
	}
	
	public void findCongestion(){
		for(RoutableWire rn : this.rnodesCreated.values()){
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
			return false;
		}
	}

	public void routeACon(Connection con){
		this.prepareForRoutingACon(con);
		
		while(!this.targetReached(con)){
			
			if(this.queue.isEmpty()){
				System.out.println(this.nodesExpanded);
				System.out.println(con.getNet().getNet().getName() + " " 
									+ ((RoutableWire)con.getSourceRNode()).wire.toString() + " " 
									+ ((RoutableWire)con.getSinkRNode()).wire.toString());
				throw new RuntimeException("Queue is empty: target unreachable?");
			}
			
			RoutableWire rnode = (RoutableWire) queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			if(!rnode.childrenSet){
				this.rrgNodeId = rnode.setChildren(this.rrgNodeId, this.rnodesCreated, this.reserved);
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
				this.printInfo(((RoutableWire)(rn)).toString());
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
		RoutableWire rn = (RoutableWire) con.getSinkRNode();
		while (rn != null) {
			con.addRNode(rn);
			rn = (RoutableWire) rn.rnodeData.getPrev();
		}
	}

	public void resetPathCost() {
		for (RoutableData node : this.rnodesTouched) {
			node.setTouched(false);
		}
		this.rnodesTouched.clear();	
	}
	
	public void exploringAndExpansion(RoutableWire rnode, Connection con){
		this.nodesPopedFromQueue++;
		
		if(this.debugExpansion){
			this.printInfo("\t" + " exploring rnode " + rnode.wire.toString());
		}
		if(this.debugExpansion) this.printInfo("\t starting  queue size: " + this.queue.size());
		for(Routable childRNode:rnode.getChildren()){
			
			if(childRNode.isTarget()){		
				if(this.debugExpansion) this.printInfo("\t\t childRNode is the target");
				this.addNodeToQueue(rnode, childRNode, con);
				this.nodesExpanded++;
			}else if(childRNode.getRoutableType().equals(RoutableType.INTERRR)){
				//traverse INT tiles only, otherwise, the router would take CLB sites as next hop candidates
				//this can be done by downsizing the created rnodes
				if(childRNode.isInBoundingBoxLimit(con)){
					if(this.debugExpansion) this.printInfo("\t\t" + " add node to the queue");
					this.addNodeToQueue(rnode, childRNode, con);
					this.nodesExpanded++;
					if(this.debugExpansion) this.printInfo("");
				}	
			}
		}
	}
	
	private void addNodeToQueue(Routable rnode, Routable childRNode, Connection con) {
		RoutableData data = childRNode.getRoutableData();
		int countSourceUses = data.countSourceUses(con.source);
		
		float partial_path_cost = rnode.getRoutableData().getPartialPathCost();//upstream path cost
		float rnodeCost = this.getRouteNodeCost(childRNode, con, countSourceUses);
		float new_partial_path_cost = partial_path_cost + rnodeCost;//upstream path cost + cost of node under consideration
		float new_lower_bound_total_path_cost;
		float expected_distance_cost = 0;
		float expected_wire_cost;
		
		if(childRNode.getRoutableType() == RoutableType.INTERRR){
			expected_distance_cost = this.expectMahatD(childRNode, con);
			
			expected_wire_cost = expected_distance_cost / (1 + countSourceUses);
			new_lower_bound_total_path_cost = new_partial_path_cost + this.mdWeight * expected_wire_cost + this.hopWeight * (rnode.getRoutableData().getLevel() + 1);
			
		}else{//lut input pin (sink)
			if(this.debugExpansion) this.printInfo("\t\t target RNode " + ((RoutableWire)con.getSinkRNode()).wire.toString() + " (" + con.sink.getTile().getColumn() + "," + con.sink.getTile().getRow() + ")");
			new_lower_bound_total_path_cost = new_partial_path_cost;
		}
		
		this.addRNodeToQueue(childRNode, rnode, new_partial_path_cost, new_lower_bound_total_path_cost);
		
	}
	
	private void addRNodeToQueue(Routable childRNode, Routable rnode, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
		RoutableData data = childRNode.getRoutableData();
		
		if(!data.isTouched()) {
			if(this.debugExpansion) this.printInfo("\t\t not touched");
			this.rnodesTouched.add(data);
			if(this.debugExpansion) this.printInfo("\t\t touched node size = "+this.rnodesTouched.size());
			data.setLowerBoundTotalPathCost(new_lower_bound_total_path_cost);
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.getRoutableData().getLevel()+1);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
			if(this.debugExpansion) this.printInfo("\t\t node added, queue size = " + this.queue.size());
			
		} else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {
			//queue is sorted by lower bound total cost
			if(this.debugExpansion) this.printInfo("\t\t touched previously");
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.getRoutableData().getLevel()+1);
			this.queue.add(new QueueElement(childRNode, new_lower_bound_total_path_cost));
		}
	}
	
	private float getRouteNodeCost(Routable rnode, Connection con, int countSourceUses) {	
		boolean containsSource = countSourceUses != 0;
		//Present congestion cost
		float pres_cost;
		if(containsSource) {
			int overoccupation = rnode.getOccupancy() - Routable.capacity;
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
		if(rnode.getRoutableType() == RoutableType.INTERRR) {
			Netplus net = con.getNet();
			bias_cost = 0.5f * rnode.getBase_cost() / net.fanout * 
					(Math.abs(rnode.getX() - net.x_geo) + Math.abs(rnode.getY() - net.y_geo)) / net.hpwl;
		}
		
		return rnode.getBase_cost() * rnode.getAcc_cost() * pres_cost / (1 + countSourceUses) + bias_cost;
	}
	
	private float expectMahatD(Routable childRNode, Connection con){
		
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
		
		con.getSinkRNode().setTarget(true);
			
		// Add source to queue
		RoutableWire source = (RoutableWire) con.getSourceRNode();
		this.addRNodeToQueue(source, null, 0, 0);
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
