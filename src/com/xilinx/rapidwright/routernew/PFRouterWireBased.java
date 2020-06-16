package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class PFRouterWireBased {
	public Design design;
	public PriorityQueue<QueueElement<Wire>> queue;
	public Collection<RNodeData<Wire>> rnodesTouched;
	public Map<String, RNode<Wire>> rnodesCreated;//name and rnode pair
	public String dcpFileName;
	public int nrOfTrials;
	public CodePerfTracker t;
	
	public PFRouterWireBased(Design design,
			String dcpFileName,
			int nrOfTrials,
			CodePerfTracker t){
		this.design = design;
		this.queue = new PriorityQueue<>(Comparators.PRIORITY_COMPARATOR);
		this.rnodesTouched = new ArrayList<>();
		
		this.rnodesCreated = new HashMap<>();
		this.dcpFileName = dcpFileName;
		this.nrOfTrials = nrOfTrials;
		this.t = t;		
	}
	
	public void route(){
		PFRouter<Wire> router = new PFRouter<Wire>(this.design, this.queue, this.rnodesTouched, this.rnodesCreated);
		//sorted nets and connections
		List<Connection<Wire>> sortedListOfConnection = new ArrayList<>();
		sortedListOfConnection.addAll(router.getConnections());
//		Collections.sort(sortedListOfConnection, Comparators.FanoutBBConnection);
		
		List<Netplus<Wire>> sortedListOfNetplus = new ArrayList<>();		
		sortedListOfNetplus.addAll(router.getNets());
//		Collections.sort(sortedListOfNetplus, Comparators.FanoutNet);
		
		//unroute nets except GND VCC and clocK
		router.unrouteNetsReserveGndVccClock();
		
		//initialize router
		router.initializeRouter();
		
		ChildRNodeGeneration expan = new ChildRNodeGeneration(this.rnodesCreated);
		
		//do routing
		t.start("Route Design");
		boolean validRouting;
		while(router.getItry() < this.nrOfTrials){
			router.resetConnectionsRoutedIteration();
			
			validRouting = true;
			
			/*this.printInfo("trial start");
			this.routeACon(router, expan, sortedListOfConnection.get(0));
			this.printInfo("trial end");
			for(RNode<Wire> rnode:sortedListOfConnection.get(0).rNodes){
				this.printInfo(rnode.toString());
			}*/
			
			for(Connection<Wire> con:sortedListOfConnection){
				if(router.getItry() == 1){
					this.routeACon(router, expan, con);	
				}else if(con.congested()){
					this.routeACon(router, expan, con);	
				}
			}
		
			//check if routing is valid
			validRouting = router.isValidRouting();
			
			//fix illegal routing trees if any
			if(validRouting){
				//TODO
				this.printInfo("\tvalid routing");
			}
			
			//update timing and criticalities of connections
			
			//statistics
			router.staticticsInfo(sortedListOfConnection);
			
			//if the routing is valid /realizable return, the routing completed successfully
			if (validRouting) {
				//TODO generate and assign a list of PIPs for each Net net
				router.getDesign().writeCheckpoint(dcpFileName,t);
				return;
			}
			//Updating the cost factors
			router.updateCostFactors();
			
			// increase router iteration
			router.updateItry();
		}
		t.stop();
		
		this.outOfTrialIterations(router);
		router.getDesign().writeCheckpoint(dcpFileName,t);
		
		return;
	}
	
	public void routeACon(PFRouter<Wire> router, ChildRNodeGeneration expan, Connection<Wire> con){
		router.ripup(con);
		
		router.prepareForRoutingACon(con);
		this.printInfo("routing for " + con.toString());
		
		while(!router.targetReached(con)){
			router.increaseNodesExpanded();
			if(this.queue.isEmpty()){
				System.out.println(con.getNet().getNet().getName() + " " + con.source.getName() + " " + con.sink.getName());
				throw new RuntimeException("Queue is empty: target unreachable?");
			}
			
			RNode<Wire> rnode = queue.poll().rnode;
			expan.wireBased(rnode,con);
			router.exploringAndExpansion(rnode, con);
		}
		
		//save routing in connection class
		router.saveRouting(con);
		// Reset path cost
		router.resetPathCost();
		
		router.add(con);
		
		router.printConRNodes(con);
	}
	
	public void outOfTrialIterations(PFRouter<Wire> router){
		if (router.getItry() == this.nrOfTrials + 1) {
			System.out.println("Routing failled after " + router.getItry() + " trials!");
		}
	}
	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}
}
