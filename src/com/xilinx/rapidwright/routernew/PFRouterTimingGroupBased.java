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
import com.xilinx.rapidwright.timing.TimingGroup;
import com.xilinx.rapidwright.timing.TimingModel;

public class PFRouterTimingGroupBased{
	public Design design;
	public PriorityQueue<QueueElement<TimingGroup>> queue;
	public Collection<RNodeData<TimingGroup>> rnodesTouched;
	public Map<String, RNode<TimingGroup>> rnodesCreated;//name and rnode pair
	public String dcpFileName;
	public int nrOfTrials;
	public CodePerfTracker t;
	
	public PFRouter<TimingGroup> router;
	public List<Connection<TimingGroup>> sortedListOfConnection;
	public List<Netplus<TimingGroup>> sortedListOfNetplus;
	public ChildRNodesCreation childRNodesCreation;
	public Set<TimingGroup> reservedNodes;
	
	public RouterTimer routerTimer;
	public long iterationStart;
	public long iterationEnd;
	
	public float initial_pres_fac; 
	public float pres_fac_mult; 
	public float acc_fac;
	
	public int globalRNodeIndex;
	public int firstIterRNodes;
	
	public TimingModel timingModel;
	
	public boolean trial = true;
	
	public PFRouterTimingGroupBased(Design design,
			TimingModel timingModel,
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
		
		this.timingModel = timingModel;
		
		this.routerTimer = new RouterTimer();
		this.router = new PFRouter<TimingGroup>(this.design, 
				this.queue, 
				this.rnodesTouched, 
				this.rnodesCreated, 
				bbRange,
				mdWeight,
				hopWeight,
				base_cost_fac);
		
		this.globalRNodeIndex = this.router.initializeNetsCons(this.timingModel);
		
		this.childRNodesCreation = new ChildRNodesCreation(null, null, rnodesCreated, base_cost_fac);
		
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
		List<Net> reservedNets = this.router.unrouteNetsReserveGndVccClock();
		/*this.reservedNodes = new HashSet<>();
		for(Net net:reservedNets){
			for(PIP pip:net.getPIPs()){
				this.reservedNodes.add(pip.getStartNode());
				this.reservedNodes.add(pip.getEndNode());
			}
		}*/
		//initialize router
		this.router.initializeRouter(this.initial_pres_fac, this.pres_fac_mult, this.acc_fac);
				
		//do routing
		boolean validRouting;
        List<Netplus<TimingGroup>> trialNets = new ArrayList<>();
        for(Netplus<TimingGroup> net : this.sortedListOfNetplus){
        	if(net.getNet().getName().equals("n2a9")){// || net.getNet().getName().equals("n2ab")){
        		System.out.println("trial net fanout" + net.getNet().getFanOut());
        		trialNets.add(net);
        	}
        }
        
		while(this.router.getItry() < this.nrOfTrials){
			this.iterationStart = System.nanoTime();
			
			this.router.resetConnectionsRoutedIteration();	
			validRouting = true;	
			if(this.trial) this.printInfo("iteration " + this.router.getItry() + " begins");
			
			if(!this.trial){
				for(Connection<TimingGroup> con:this.sortedListOfConnection){
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
				for(Netplus<TimingGroup> np : trialNets){
					for(Connection<TimingGroup> c : np.getConnection()){
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
				this.printInfo("\nvalid routing - no congested/illegal rnodes\n ");
				
				/*this.routerTimer.pipsAssignment.start();
				this.pipsAssignment();
				this.routerTimer.pipsAssignment.finish();*/
				
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
		for(Netplus<TimingGroup> np:this.sortedListOfNetplus){
			Set<PIP> netPIPs = new HashSet<>();
			for(Connection<TimingGroup> c:np.getConnection()){
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
		for(Netplus<TimingGroup> net:this.sortedListOfNetplus){
			if(net.getNet().getName().equals(netname)){
				System.out.println(net.getNet().toString());
				for(Connection<TimingGroup> c: net.getConnection()){
					System.out.println(c.getSourceRNode().name + "\t" + c.getSinkRNode().name);
					for(PIP p:this.conPIPs(c)){
						System.out.println("\t" + p.toString());
					}
					System.out.println();
				}
			}
		}
	}
	
	public void checkNetRoutedPins(){
		Map<Net, Integer> netRoutedPins = new HashMap<>();
		for(Net net:this.design.getNets()){
			int routedPins = 0;
			for(PIP pip:net.getPIPs()){
				if(pip.getEndWireName().contains("IMUX_") && !pip.getEndWireName().contains("_IMUX_")){
					routedPins++;
				}
			}
			netRoutedPins.put(net, routedPins);
		}
		
		int errorNetsRoutedLargeThanFanout = 0;
		int errorNetsRoutedLessThanFanout = 0;
		
		for(Net net:this.design.getNets()){
			if(net.getFanOut() < netRoutedPins.get(net)){
				errorNetsRoutedLargeThanFanout++;

				
			}else if(net.getFanOut() > netRoutedPins.get(net)){
//				System.out.println("error nets routed less than fanout");
//				System.out.println(net.toStringFull());
				errorNetsRoutedLessThanFanout++;
			}
		}
		
		System.out.println("error nets routed large than fanout = " + errorNetsRoutedLargeThanFanout);
		System.out.println("error nets routed less than fanout = " + errorNetsRoutedLessThanFanout);
	}
	
	public List<PIP> conPIPs(Connection<TimingGroup> con){
		List<PIP> conPIPs = new ArrayList<>();
		
		for(int i = con.rnodes.size() -1; i > 0; i--){
			Node nodeFormer = con.rnodes.get(i).getNode();//TODO un-checked
			Node nodeLatter = con.rnodes.get(i-1).getNode();
			
			Wire startWire = this.findEndWireOfNode(nodeFormer.getAllWiresInNode(), nodeLatter.getTile());
			
			if(startWire != null){
				PIP pip = new PIP(nodeLatter.getTile(), startWire.getWireIndex(), nodeLatter.getWire());
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
		for(RNode<TimingGroup> rn:this.rnodesCreated.values()){
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
		Set<Connection<TimingGroup>> congestedCons = new HashSet<>();
//		Map<Netplus<Node>, Integer> congestedNets = new HashMap<>();
		for(Connection<TimingGroup> con:this.sortedListOfConnection){
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
		for(Connection<TimingGroup> con:congestedCons){
			System.out.println(con.toString());
			for(RNode<TimingGroup> rn : con.rnodes){
				if(rn.overUsed()) System.out.println("\t"+ rn.toString());
			}
			System.out.println();
		}
	}

	public void routeACon(Connection<TimingGroup> con){
		this.router.prepareForRoutingACon(con);
		if(this.router.debugRoutingCon) this.printInfo("routing for " + con.toString());
		
		while(!this.router.targetReached(con)){
			this.router.increaseNodesExpanded();
			
			if(this.queue.isEmpty()){
				System.out.println(con.getNet().getNet().getName() + " " + con.source.getName() + " " + con.sink.getName());
				throw new RuntimeException("Queue is empty: target unreachable?");
			}
			
			RNode<TimingGroup> rnode = queue.poll().rnode;
			
			this.routerTimer.rnodesCreation.start();
			if(!rnode.childrenSet){
				this.globalRNodeIndex = this.childRNodesCreation.timingGroupBased(rnode, this.globalRNodeIndex, this.timingModel);
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
		float allInter = 0;
		for(RNode<TimingGroup> rntg:this.rnodesCreated.values()){
			if(rntg.type == RoutableType.INTERRR){
				for(Node node:rntg.getTimingGroup().getNodes()){
					sum += node.getAllWiresInNode().length;
				}
				allInter++;
			}
		}
		aver = sum / allInter;
		
		return aver;
	}
	public float checkAverageNumNodes(){
		float aver = 0;
		float sum = 0;
		float allInter = 0;
		for(RNode<TimingGroup> rntg:this.rnodesCreated.values()){
			if(rntg.type == RoutableType.INTERRR){
				sum += rntg.getTimingGroup().getNodes().size();
				allInter++;
			}
		}
		aver = sum / allInter;
		
		return aver;
	}
	
	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}

	public Design getDesign() {
		return this.design;
	}
}
