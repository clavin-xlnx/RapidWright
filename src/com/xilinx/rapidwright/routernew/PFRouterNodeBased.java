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
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
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
	
	public PFRouterNodeBased(Design design,
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
		this.router = new PFRouter<Node>(this.design, 
				this.queue, 
				this.rnodesTouched, 
				this.rnodesCreated, 
				bbRange,
				mdWeight,
				hopWeight,
				base_cost_fac);
		
		this.globalRNodeIndex = this.router.initializeNetsCons(RoutingGranularityOpt.NODE);
		
		this.childRNodesGeneration = new ChildRNodesCreation(null, this.rnodesCreated, null, base_cost_fac);
		
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
		this.router.unrouteNetsReserveGndVccClock();
		
		//initialize router
		this.router.initializeRouter(this.initial_pres_fac, this.pres_fac_mult, this.acc_fac);
				
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
						this.routeACon(con);
						this.routerTimer.firstIteration.finish();
					}else if(con.congested()){
						this.routerTimer.rerouteCongestion.start();
						this.routeACon(con);
						this.routerTimer.rerouteCongestion.finish();
					}
				}
			}else{
				for(Netplus<Node> np : trialNets){
					for(Connection<Node> c : np.getConnection()){
						if(this.router.getItry() == 1){
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
			validRouting = this.router.isValidRouting();
			
			//fix illegal routing trees if any
			if(validRouting){
				this.routerTimer.rerouteIllegal.start();
				this.router.fixIllegalTree(sortedListOfConnection);
				this.routerTimer.rerouteIllegal.finish();
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
				
				this.routerTimer.pipsAssignment.start();
				this.pipsAssignment();
				this.routerTimer.pipsAssignment.finish();
				
				return;
			}
			
			this.routerTimer.updateCost.start();
			//Updating the cost factors
			this.router.updateCostFactors();
			// increase router iteration
			this.router.updateItry();
			this.routerTimer.updateCost.finish();
//			System.out.println(this.routerTimer.rnodesCreation.toString());
		}
		
		this.router.outOfTrialIterations(this.nrOfTrials);
		
		return;
	}
	
	public void pipsAssignment(){
		for(Netplus<Node> np:this.sortedListOfNetplus){
			Set<PIP> netPIPs = new HashSet<>();
			for(Connection<Node> c:np.getConnection()){
				netPIPs.addAll(this.conPIPs(c));
			}
			np.getNet().setPIPs(netPIPs);
		}
		
		for(Net n:this.design.getNets()){
			if(n.getName().equals("n689"))
			System.out.println(n.getFanOut());
		}
	}
	
	public List<PIP> conPIPs(Connection<Node> con){
		List<PIP> conPIPs = new ArrayList<>();
		
		for(int i = con.rnodes.size() -1; i > 0; i--){
			Node nodeFormer = con.rnodes.get(i).getNode();
			Node nodeLatter = con.rnodes.get(i-1).getNode();
			
			Wire startWire = this.findEndWireIndexOfNode(nodeFormer.getAllWiresInNode(), nodeLatter.getTile());
			
			PIP pip = new PIP(startWire.getTile(), startWire.getWireIndex(), nodeLatter.getWire());
			
			
			conPIPs.add(pip);
		}
		return conPIPs;
	}
	
	public Wire findEndWireIndexOfNode(Wire[] wires, Tile tile){
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
		for(RNode<Node> rn:this.rnodesCreated.values()){
			sum += rn.base_cost;
			costs.add(rn.base_cost);
		}
		aver = sum/this.rnodesCreated.size();
		System.out.println(aver);
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
			for(RNode<Node> rn : con.rnodes){
				if(rn.overUsed()) System.out.println("\t"+ rn.toString());
			}
			System.out.println();
		}
	}

	public void routeACon(Connection<Node> con){
		this.router.prepareForRoutingACon(con);
		if(this.router.debugRoutingCon) this.printInfo("routing for " + con.toString());
		
		while(!this.router.targetReached(con)){
			this.router.increaseNodesExpanded();
			
			if(this.queue.isEmpty()){
				System.out.println(con.getNet().getNet().getName() + " " + con.source.getName() + " " + con.sink.getName());
				throw new RuntimeException("Queue is empty: target unreachable?");
			}
			
			RNode<Node> rnode = queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			if(!rnode.childrenSet){
				this.globalRNodeIndex = this.childRNodesGeneration.nodeBased(rnode, this.globalRNodeIndex);
			}
			this.routerTimer.rnodesCreation.finish();
			
			this.router.exploringAndExpansion(rnode, con);
		}
		
		this.router.finishRoutingACon(con);
		
		this.router.printConRNodes(con);
	}
	
	public float checkAverageNumWires(){
		float aver = 0;
		float sum = 0;
		for(RNode<Node> rn:this.rnodesCreated.values()){
			sum += rn.getNode().getAllWiresInNode().length;
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
}
