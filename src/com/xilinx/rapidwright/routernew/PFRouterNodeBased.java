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
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class PFRouterNodeBased{
	public Design design;
	public PriorityQueue<QueueElement<Node>> queue;
	public Collection<RNodeData<Node>> rnodesTouched;
	public Map<String, RNode<Node>> rnodesCreated;//name and rnode pair
	public String dcpFileName;
	public int nrOfTrials;
	public CodePerfTracker t;
	
	public PFRouter<Node> router;
	public List<Connection<Node>> sortedListOfConnection;
	public List<Netplus<Node>> sortedListOfNetplus;
	
	public RouterTimer routerTimer;
	public long iterationStart;
	public long iterationEnd;
	
	public boolean trial = false;
	
	public PFRouterNodeBased(Design design,
			String dcpFileName,
			int nrOfTrials,
			CodePerfTracker t,
			int bbRange){
		this.design = design;
		this.queue = new PriorityQueue<>(Comparators.PRIORITY_COMPARATOR);
		this.rnodesTouched = new ArrayList<>();
		
		this.rnodesCreated = new HashMap<>();
		this.dcpFileName = dcpFileName;
		this.nrOfTrials = nrOfTrials;
		this.t = t;
		
		this.routerTimer = new RouterTimer();
		this.router = new PFRouter<Node>(this.design, this.queue, this.rnodesTouched, this.rnodesCreated, bbRange);
		
		this.router.initializeNetsCons(RoutingGranularityOpt.NODE);
		
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfNetplus = new ArrayList<>();
	}
	
	 public int routingRuntime(){
		 long start = System.nanoTime();
		 this.route();
		 long end = System.nanoTime();
		 int timeInMilliSeconds = (int)Math.round((end-start) * Math.pow(10, -6));			
		 return timeInMilliSeconds;
	 }
	
	public void route(){
		
		//sorted nets and connections		
		this.sortedListOfConnection.addAll(this.router.getConnections());
		Collections.sort(this.sortedListOfConnection, Comparators.FanoutBBConnection);	
			
		this.sortedListOfNetplus.addAll(this.router.getNets());
		Collections.sort(this.sortedListOfNetplus, Comparators.FanoutNet);
		
		//unroute nets except GND VCC and clocK
		this.router.unrouteNetsReserveGndVccClock();
		
		//initialize router
		this.router.initializeRouter();
		
		ChildRNodesCreation childRNodesGeneration = new ChildRNodesCreation(this.rnodesCreated, RoutingGranularityOpt.NODE);
		
		//do routing
		boolean validRouting;
        List<Netplus<Node>> trialNets = new ArrayList<>();
        for(Netplus<Node> net : this.sortedListOfNetplus){
//        	if(net.getNet().getName().equals("n767") || net.getNet().getName().equals("n761")){
        	if(net.getNet().getName().equals("n775") || net.getNet().getName().equals("n689")){
        		trialNets.add(net);
        	}
        }
        
		while(this.router.getItry() < this.nrOfTrials){
			this.iterationStart = System.nanoTime();
			
			this.router.resetConnectionsRoutedIteration();	
			validRouting = true;	
			if(this.trial) this.printInfo("iteration " + this.router.getItry() + " begins");
			
			if(!this.trial){
				for(Connection<Node> con:this.sortedListOfConnection){
					if(this.router.getItry() == 1){
						this.routerTimer.firstIteration.start();
						this.routeACon(this.router, childRNodesGeneration, con);
						this.routerTimer.firstIteration.finish();
					}else if(con.congested()){
						this.routerTimer.rerouteCongestion.start();
						this.routeACon(this.router, childRNodesGeneration, con);
						this.routerTimer.rerouteCongestion.finish();
					}
				}
			}else{
				for(Netplus<Node> np : trialNets){
					for(Connection<Node> c : np.getConnection()){
						if(this.router.getItry() == 1){
							this.routerTimer.firstIteration.start();
							this.routeACon(this.router, childRNodesGeneration, c);
							this.routerTimer.firstIteration.finish();
						}else if(c.congested()){
							this.routerTimer.rerouteCongestion.start();
//							this.router.debugExpansion = true;
//							this.router.debugRoutingCon = true;
							this.routeACon(this.router, childRNodesGeneration, c);
							this.routerTimer.rerouteCongestion.finish();
						}
					}	
				}
			}
		
			//check if routing is valid
			validRouting = this.router.isValidRouting();
			
			//fix illegal routing trees if any
			/*if(validRouting){
				this.fixIllegalTree(sortedListOfConnection);
				this.printInfo("\tvalid routing - no congested rnodes");
			}*/
			
			//TODO update timing and criticalities of connections
			
			this.iterationEnd = System.nanoTime();
			//statistics
			this.routerTimer.calculateStatistics.start();
			this.router.staticticsInfo(this.sortedListOfConnection, this.iterationStart, this.iterationEnd);
			this.routerTimer.calculateStatistics.finish();;
			//if the routing is valid /realizable return, the routing completed successfully
	
//			if(this.router.getItry() > 1)this.findCongestion();
	
			if (validRouting) {
				//TODO generate and assign a list of PIPs for each Net net
				this.printInfo("\tvalid routing - no congested rnodes");
				this.router.getDesign().writeCheckpoint(dcpFileName,t);
				return;
			}
			
			this.routerTimer.updateCost.start();
			//Updating the cost factors
			this.router.updateCostFactors();
			// increase router iteration
			this.router.updateItry();
			this.routerTimer.updateCost.finish();
		}
		
		this.router.outOfTrialIterations(this.nrOfTrials);
		this.router.getDesign().writeCheckpoint(dcpFileName,t);
		
		return;
	}
	
	public void findCongestion(){
		/*for(RNode<Node> rn : this.rnodesCreated.values()){
			if(rn.overUsed()){
				System.out.println(rn.toString());
			}
		}*/
		Set<Connection<Node>> congestedCons = new HashSet<>();
//		Map<Netplus<Node>, Integer> congestedNets = new HashMap<>();
		for(Connection<Node> con:this.sortedListOfConnection){
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
		for(Connection<Node> con:congestedCons){
			System.out.println(con.toString());
			for(RNode<Node> rn : con.rNodes){
				if(rn.overUsed()) System.out.println("\t"+ rn.toString());
			}
			System.out.println();
		}
	}
	
	public void fixIllegalTree(List<Connection<Node>> cons) {
		this.printInfo("checking if there is any illegal node");
		
		int numIllegal = this.getIllegalNumRNodes(cons);
		
		if(numIllegal > 0){
			this.printInfo("There are " + numIllegal + " illegal routing tree nodes");
			
			List<Netplus<Node>> illegalTrees = new ArrayList<>();
			for(Netplus<Node> net : this.router.getNets()) {
				boolean illegal = false;
				for(Connection<Node> con : net.getConnection()) {
					if(con.illegal()) {
						illegal = true;
					}
				}
				if(illegal) {
					illegalTrees.add(net);
				}
			}
			
			this.printInfo("There are " + illegalTrees.size() + " illegal trees");
			//find the illegal connections
			for(Netplus<Node> illegalTree : illegalTrees){
				RNode<Node> illegalRNode;
				while((illegalRNode = illegalTree.getIllegalNode()) != null){
					List<Connection<Node>> illegalCons = new ArrayList<>();
					for(Connection<Node> con : illegalTree.getConnection()) {
						for(RNode<Node> rnode : con.rNodes) {
							if(rnode.equals(illegalRNode)) {
								illegalCons.add(con);
							}
						}
					}
					
					//TODO fix the illegal trees, since there is no criticality info, using the Manhattan distance, or hops?
					
				}
			}
		}	
	}
	
	public int getIllegalNumRNodes(List<Connection<Node>> cons){
		Set<String> illegal = new HashSet<>();	
		for(Connection<Node> c:cons){
			for(RNode<Node> rn:c.rNodes){
				if(rn.illegal()){
					illegal.add(rn.name);
				}
			}
		}	
		return illegal.size();	
	}

	public void routeACon(PFRouter<Node> router, ChildRNodesCreation childRNodesGeneration, Connection<Node> con){
		router.prepareForRoutingACon(con);
		if(this.router.debugRoutingCon) this.printInfo("routing for " + con.toString());
		
		while(!router.targetReached(con)){
			router.increaseNodesExpanded();
			
			if(this.queue.isEmpty()){
				System.out.println(con.getNet().getNet().getName() + " " + con.source.getName() + " " + con.sink.getName());
				throw new RuntimeException("Queue is empty: target unreachable?");
			}
			
			RNode<Node> rnode = queue.poll().rnode;
			if(!rnode.childrenSet){
				childRNodesGeneration.nodeBased(rnode);
			}		
			router.exploringAndExpansion(rnode, con);
		}
		
		router.finishRoutingACon(con);
		
		router.printConRNodes(con);
	}
	
	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}
}
