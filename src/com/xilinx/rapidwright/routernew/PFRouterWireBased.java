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
import com.xilinx.rapidwright.device.PIP;
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
	
	public PFRouter<Wire> router;
	public List<Connection<Wire>> sortedListOfConnection;
	public List<Netplus<Wire>> sortedListOfNetplus;
	public ChildRNodesCreation childRNodesGeneration;
	
	public RouterTimer routerTimer;
	public long iterationStart;
	public long iterationEnd;
	
	public float initial_pres_fac; 
	public float pres_fac_mult; 
	public float acc_fac;
	
	public int globalRNodeIndex;
	public int firstIterRNodes;
	
	public boolean trial = false;
	
	public PFRouterWireBased(Design design,
			String dcpFileName,
			int nrOfTrials,
			CodePerfTracker t,
			int bbRange,
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
		
		this.routerTimer = new RouterTimer();
		this.router = new PFRouter<Wire>(this.design, 
				this.queue, 
				this.rnodesTouched, 
				this.rnodesCreated, 
				bbRange,
				mdWeight,
				hopWeight,
				base_cost_fac);
		
		this.globalRNodeIndex = this.router.initializeNetsCons(RoutingGranularityOpt.WIRE);
		
		this.childRNodesGeneration = new ChildRNodesCreation(this.rnodesCreated, null, null, base_cost_fac);
		
		this.sortedListOfConnection = new ArrayList<>();
		this.sortedListOfNetplus = new ArrayList<>();
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
		this.sortedListOfConnection.addAll(this.router.getConnections());
		Collections.sort(this.sortedListOfConnection, Comparators.FanoutBBConnection);	
			
		this.sortedListOfNetplus.addAll(this.router.getNets());
		Collections.sort(this.sortedListOfNetplus, Comparators.FanoutNet);
		
		//unroute nets except GND VCC and clocK
//		this.router.unrouteNetsReserveGndVccClock();
		
		//initialize router
		this.router.initializeRouter(this.initial_pres_fac, this.pres_fac_mult, this.acc_fac);
		
		//do routing
		boolean validRouting;
        List<Netplus<Wire>> trialNets = new ArrayList<>();
        for(Netplus<Wire> net : this.sortedListOfNetplus){
//        	if(net.getNet().getName().equals("n767") || net.getNet().getName().equals("n761")){
        	if(net.getNet().getName().equals("n22c")){// || net.getNet().getName().equals("n689")){
        		trialNets.add(net);
        	}
        }
        
		while(this.router.getItry() < this.nrOfTrials){
			this.iterationStart = System.nanoTime();
			
			this.router.resetConnectionsRoutedIteration();	
			validRouting = true;	
			if(this.trial) this.printInfo("iteration " + this.router.getItry() + " begins");
			
			if(!this.trial){
				for(Connection<Wire> con:this.sortedListOfConnection){
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
				for(Netplus<Wire> np : trialNets){
					for(Connection<Wire> c : np.getConnection()){
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
			if(validRouting){
				this.router.fixIllegalTree(sortedListOfConnection);
			}
			
			//TODO update timing and criticalities of connections
			
			this.iterationEnd = System.nanoTime();
			//statistics
			this.routerTimer.calculateStatistics.start();
			this.router.staticticsInfo(this.sortedListOfConnection, 
					this.iterationStart, this.iterationEnd, 
					this.globalRNodeIndex, this.routerTimer.rnodesCreation.getTime());
			this.routerTimer.calculateStatistics.finish();;
			//if the routing is valid /realizable return, the routing completed successfully
	
			if(this.router.getItry() == 1) this.firstIterRNodes = this.rnodesCreated.size();
	
			if (validRouting) {
				//TODO generate and assign a list of PIPs for each Net net
				this.printInfo("\tvalid routing - no congested rnodes");
				
				/*this.routerTimer.pipsAssignment.start();
				this.pipsAssignment();
				this.routerTimer.pipsAssignment.finish();*/
				
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
	
	public void pipsAssignment(){
		for(Netplus<Wire> np:this.sortedListOfNetplus){
			if(np.getNet().getName().equals("n22c")){
				System.out.println(np.getNet().toStringFull());
				Set<PIP> netPIPs = new HashSet<>();
				for(Connection<Wire> c:np.getConnection()){
					netPIPs.addAll(this.conPIPs(c));
				}
				np.getNet().setPIPs(netPIPs);
				
			}
		}
	}
	
	public List<PIP> conPIPs(Connection<Wire> con){
		List<PIP> conPIPs = new ArrayList<>();
		RNode<Wire> rn = con.getSinkRNode().rnodeData.getPrev();//this is a wire, add sink pin SitePinInst?
		while(rn != null){
			RNode<Wire> rnprev = rn.rnodeData.getPrev(); 
			PIP pip = new PIP(rn.getTile(), rnprev.getWire(), rn.getWire());//NullPointerException when creating PIPs between two INT tiles
			conPIPs.add(pip);
			System.out.println(pip.toString());
			if(rnprev.rnodeData.getPrev().type == RoutableType.SOURCERNODE){
				break;
			}
			rn = rnprev;
		}
		
		return conPIPs;
	}
	
	public void findCongestion(){
		/*for(RNode<Wire> rn : this.rnodesCreated.values()){
			if(rn.overUsed()){
				System.out.println(rn.toString());
			}
		}*/
		Set<Connection<Wire>> congestedCons = new HashSet<>();
//		Map<Netplus<Wire>, Integer> congestedNets = new HashMap<>();
		for(Connection<Wire> con:this.sortedListOfConnection){
			if(con.congested()){
				congestedCons.add(con);
				/*Netplus<Wire> np = con.getNet();
				if(congestedNets.containsKey(np)){
					congestedNets.put(np, congestedNets.get(np)+1);
				}else{
					congestedNets.put(np, 1);
				}*/
			}
		}
		for(Connection<Wire> con:congestedCons){
			System.out.println(con.toString());
			for(RNode<Wire> rn : con.rnodes){
				if(rn.overUsed()) System.out.println("\t"+ rn.toString());
			}
			System.out.println();
		}
	}
	
	public void routeACon(PFRouter<Wire> router, ChildRNodesCreation childRNodesGeneration, Connection<Wire> con){
		router.prepareForRoutingACon(con);
		if(this.router.debugRoutingCon) this.printInfo("routing for " + con.toString());
		
		while(!router.targetReached(con)){
			router.increaseNodesExpanded();
			
			if(this.queue.isEmpty()){
				System.out.println(con.getNet().getNet().getName() + " " + con.source.getName() + " " + con.sink.getName());
				throw new RuntimeException("Queue is empty: target unreachable?");
			}
			
			RNode<Wire> rnode = queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			if(!rnode.childrenSet){
				this.globalRNodeIndex = childRNodesGeneration.wireBased(rnode, this.globalRNodeIndex);
			}
			this.routerTimer.rnodesCreation.finish();
			
			router.exploringAndExpansion(rnode, con);
		}
		
		router.finishRoutingACon(con);
		
		router.printConRNodes(con);
	}
	
	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}
}
