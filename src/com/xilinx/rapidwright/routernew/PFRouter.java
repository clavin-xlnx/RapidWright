package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingModel;

public class PFRouter<E>{
	//PathFinder routing schedule parameters
	private float pres_fac;
	private float initial_pres_fac;
	private float pres_fac_mult;
	private float acc_fac;
//	private float IPIN_WIRE_BASE_COST = 0.95f;//not used currently
	private Design design;
	private Device device;
	
	private TimingManager tmanager;
	private TimingModel tmodel;
	
	private List<Netplus<E>> nets;
	private List<Connection<E>> connections;
	private int fanout1Net;
	private PriorityQueue<QueueElement<E>> queue;
	private Collection<RNodeData<E>> rnodesTouched;
	private Map<String, RNode<E>> rnodesCreated;
	
	private Set<Integer> overUsedRNodes;
	private Set<Integer> usedRNodes;
	private Set<Integer> illegalRNodes;//nodes that have multiple drivers in a net
//	private int usedRNodes;
//	private int illegalRNodes;
	
	private int connectionsRouted;
	private long nodesExpanded;
	private int connectionsRoutedIteration;
	private long hops;
	private float manhattanD;
	
	private int itry;
	private int rnodeId = 0;
	
	public boolean debugRoutingCon = false;
	public boolean debugExpansion = false;

	public int bbRange;
	public float mdWeight;
	public float hopWeight;
	public float base_cost_fac;
	
	public PFRouter(Design design, 
			PriorityQueue<QueueElement<E>> queue, 
			Collection<RNodeData<E>> rnodesTouched, 
			Map<String, RNode<E>> rnodesCreated,
			int bbRange,
			float mdWeight,
			float hopWeight,
			float base_cost_fac){
		this.design = design;
		
		this.device = this.design.getDevice();
		
		this.tmanager = new TimingManager(design);
		this.tmodel = this.tmanager.getTimingModel();
		
		this.nets = new ArrayList<>();
		this.connections = new ArrayList<>();
		this.queue = queue;
		this.rnodesTouched = rnodesTouched;
		this.rnodesCreated = rnodesCreated;
		
		this.bbRange = bbRange;
		this.mdWeight = mdWeight;
		this.hopWeight = hopWeight;
		this.base_cost_fac = base_cost_fac;
		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesExpanded = 0;
		
		this.usedRNodes = new HashSet<>();
		this.overUsedRNodes = new HashSet<>();
		this.illegalRNodes = new HashSet<>();
	}
	
	public int initializeNetsCons(RoutingGranularityOpt opt){
		int inet = 0;
		int icon = 0;
		for(Net n:this.design.getNets()){
			if(n.getFanOut() > 0){//ignore nets that have no pins
				
				Netplus<E> np = new Netplus<E>(inet, this.bbRange, n);
				this.nets.add(np);
				inet++;
				
				SitePinInst source = n.getSource();
				RNode<E> sourceRNode = new RNode<E>(this.rnodeId, source, RoutableType.SOURCERNODE, opt);
				sourceRNode.setBaseCost(this.base_cost_fac);
				this.rnodesCreated.put(sourceRNode.name, sourceRNode);	
				this.rnodeId++;
				
				for(SitePinInst sink:n.getSinkPins()){
					
					int wire1 = sink.getSiteInst().getSite().getTileWireIndexFromPinName(sink.getName());
					int wire2 = sink.getSiteExternalWireIndex();
					if(wire1 != wire2) System.out.println("not the same");
					Connection<E> c = new Connection<E>(icon, source, sink, this.tmodel);	
					c.setSourceRNode(sourceRNode);
					
					//create RNode of the sink pin external wire up front 
					RNode<E> sinkRNode = new RNode<E>(this.rnodeId, sink, RoutableType.SINKRNODE, opt);
					sinkRNode.setBaseCost(this.base_cost_fac);
					c.setSinkRNodeAndTargetName(sinkRNode);
					this.rnodesCreated.put(sinkRNode.name, sinkRNode);
					this.rnodeId++;
					
					Tile adjacentTile = this.findSinkPinAdjacentINTtile(sink);
					
					this.connections.add(c);
					c.setNet(np);//TODO new and set its TimingEdge for timing-driven version
					np.addCons(c);
					icon++;
				}
			}
			if(n.getFanOut() == 1)
				this.fanout1Net++;
		}
		return this.rnodeId;
	}
	
	public Tile findSinkPinAdjacentINTtile(SitePinInst sink){
		Tile t = sink.getTile();
		int wire = sink.getSiteExternalWireIndex();
		Node node = new Node(t, wire);
		RouteNode n = new RouteNode(node.getTile(),node.getWire());//n.getTile() is what wanted
		
		int watchdog = 1000;
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		q.add(n);
		while(!q.isEmpty()){
			n = q.poll(); 
			if(this.isSwitchBox(n.getTile())){
				ArrayList<RouteNode> path = new ArrayList<RouteNode>();
				while(n != null){
					path.add(n);
					n = n.getParent();
				}
				if(path.size() != 1) 
					System.out.println("sink to input pin feed path size: " + path.size());
				return path.get(0).getTile();
			}
			for(PIP pip : n.getBackwardPIPs()){
				Wire tmp = new Wire(n.getTile(),pip.getStartWireIndex());
				RouteNode newNode = new RouteNode(tmp.getTile(),tmp.getWireIndex(),n,n.getLevel()+1); 
				q.add(newNode);
				Wire nodeHead = tmp.getStartWire();
				if(!nodeHead.equals(tmp)){
					q.add(new RouteNode(nodeHead.getTile(),nodeHead.getWireIndex(),newNode,newNode.getLevel()+1));
				}
				
			}
			watchdog--;
			if(watchdog < 0) break;
		}
		return null;
	}
	
	public boolean isSwitchBox(Tile t){
		TileTypeEnum tt = t.getTileTypeEnum();
		if(t.getDevice().getSeries() == Series.Series7){
			return tt == TileTypeEnum.INT_L || tt == TileTypeEnum.INT_R;
		}
		return tt == TileTypeEnum.INT;
	}
	
	public void designInfo(){
		System.out.println("------------------------------------------------------------------------------");
		System.out.println("FPGA tiles size: " + this.device.getColumns() + "x" + this.device.getRows());
		System.out.println("Num con to be routed: " + this.connections.size());
		System.out.println("Num net to be routed: " + this.nets.size());
		System.out.println("Num 1-sink net: " + this.fanout1Net);
		System.out.println("------------------------------------------------------------------------------");
	}
	
	public List<Net> unrouteNetsReserveGndVccClock(){
		List<Net> reservedNets = new ArrayList<>();
		System.out.println();
		for(Net net:this.design.getNets()){
			if(net.isClockNet() || net.isStaticNet()){
				System.out.println("Routed net reserved:\t" +  net.getName());
				reservedNets.add(net);
				continue;
			}else if(net.getFanOut() > 0){
				net.unroute();
			}
		}
		
		return reservedNets;
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
	
	public void ripup(Connection<E> con){
		RNode<E> parent = null;
		for(int i = con.rnodes.size() - 1; i >= 0; i--){
			RNode<E> rnode = con.rnodes.get(i);
			RNodeData<E> rNodeData = rnode.rnodeData;
			
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
	public void add(Connection<E> con){
		RNode<E> parent = null;
		for(int i = con.rnodes.size()-1; i >= 0; i--){
			RNode<E> rnode = con.rnodes.get(i);
			RNodeData<E> rNodeData = rnode.rnodeData;
			
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
	
	public void printConRNodes(Connection<E> con){
		if(this.debugRoutingCon){
			for(RNode<E> rn:con.rnodes){
				this.printInfo(rn.toString());
			}
			this.printInfo("");	
		}	
	}
	
	public void prepareForRoutingACon(Connection<E> con){
		this.ripup(con);
		
		this.connectionsRouted++;
		this.connectionsRoutedIteration++;
		// Clear previous route of the connection
		con.resetConnection();
		// Clear the priority queue
		this.queue.clear();	
		
		// Add source to queue
		RNode<E> source = con.getSourceRNode();
		this.addRNodeToQueue(source, null, 0, 0);
	}
	
	public void exploringAndExpansion(RNode<E> rnode, Connection<E> con){
		this.nodesExpanded++;
		
		if(this.debugExpansion){
			this.printInfo("\t" + " exploring rnode " + rnode.toString());
		}
		if(this.debugExpansion) this.printInfo("\t starting  queue size: " + this.queue.size());
		for(RNode<E> childRNode:rnode.children){
			
			if(childRNode.name.equals(con.targetName)){		
				if(this.debugExpansion) this.printInfo("\t\t childRNode is the target");
				this.addNodeToQueue(rnode, childRNode, con);
				
			}else{
				//traverse INT tiles only, otherwise, the router would take CLB sites as next hop candidates
				//this can be done by downsizing the created rnodes
				if(con.isInBoundingBoxLimit(childRNode)){
					if(this.debugExpansion) this.printInfo("\t\t" + " add node to the queue");
					this.addNodeToQueue(rnode, childRNode, con);
					if(this.debugExpansion) this.printInfo("");
				}	
			}
		}
	}
	
	public boolean targetReached(Connection<E> con){
		if(this.queue.size() > 0){
			RNode<E> queueHead = this.queue.peek().rnode;
			return queueHead.name.equals(con.targetName);
		}else{//dealing with null pointer exception
			System.out.println("queue is empty");
			return false;
		}
	}
	
	public void finishRoutingACon(Connection<E> con){
		//save routing in connection class
		this.saveRouting(con);
		// Reset path cost
		this.resetPathCost();
		
		this.add(con);
	}
	
	public void saveRouting(Connection<E> con){
		RNode<E> rn = con.getSinkRNode();
		while (rn != null) {
			con.addRNode(rn);
			rn = rn.rnodeData.getPrev();
		}
	}

	public void resetPathCost() {
		for (RNodeData<E> node : this.rnodesTouched) {
			node.setTouched(false);
			node.setLevel(0);
		}
		this.rnodesTouched.clear();	
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
		for(RNode<E> rnode:this.rnodesCreated.values()){
			RNodeData<E> data = rnode.rnodeData;
			int overuse = data.getOccupation() - rnode.capacity;
			//Present congestion penalty
			if(overuse == 0) {
				data.setPres_cost(1 + pres_fac);
			} else if (overuse > 0) {
				data.setPres_cost(1 + (overuse + 1) * pres_fac);
				data.setAcc_cost(data.getAcc_cost() + overuse * acc_fac);
			}
		}	
	}
		
	private void addNodeToQueue(RNode<E> rnode, RNode<E> childRNode, Connection<E> con) {
		RNodeData<E> data = childRNode.rnodeData;
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
		
		if(childRNode.type == RoutableType.INTERRNODE){
			
//			if(this.debugExpansion) this.printInfo("\t\t target RNode " + con.targetName + " (" + con.sink.getTile().getColumn() + "," + con.sink.getTile().getRow() + ")");
			expected_distance_cost = this.expectMahatD(childRNode, con);
			
			expected_wire_cost = expected_distance_cost / (1 + countSourceUses);
			new_lower_bound_total_path_cost = new_partial_path_cost + this.mdWeight * expected_wire_cost + this.hopWeight * (rnode.rnodeData.getLevel() + 1);
			
		}else{//lut input pin (sink)
			new_lower_bound_total_path_cost = new_partial_path_cost;
		}
		//cost info for debugging
		/*if(this.debugExpansion){
			StringBuilder s = new StringBuilder();
			s.append(String.format("partial_path_cost = %10.3f", partial_path_cost));
			s.append(", ");
			s.append(String.format("rnode_cost = %10.3f", rnodeCost));
			s.append(", ");
			s.append(String.format("# count source usage = %2d", countSourceUses));
			s.append(", ");
			s.append(String.format("# est_dis = %2d", expected_distance_cost));
			s.append(", ");
			s.append(String.format("new_total_path_cost = %10.3f", new_lower_bound_total_path_cost));
			String costInfo = s.toString();
			this.printInfo("\t\t "+ costInfo);
		}*/
		
		this.addRNodeToQueue(childRNode, rnode, new_partial_path_cost, new_lower_bound_total_path_cost);
		
	}
	
	private void addRNodeToQueue(RNode<E> childRNode, RNode<E> rnode, float new_partial_path_cost, float new_lower_bound_total_path_cost) {
		RNodeData<E> data = childRNode.rnodeData;
		
		if(!data.isTouched()) {
			if(this.debugExpansion) this.printInfo("\t\t not touched");
			this.rnodesTouched.add(data);
			if(this.debugExpansion) this.printInfo("\t\t touched node size = "+this.rnodesTouched.size());
			data.setLowerBoundTotalPathCost(new_lower_bound_total_path_cost);
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.rnodeData.getLevel()+1);
			this.queue.add(new QueueElement<E>(childRNode, new_lower_bound_total_path_cost));
			if(this.debugExpansion) this.printInfo("\t\t node added, queue size = " + this.queue.size());
			
		} else if (data.updateLowerBoundTotalPathCost(new_lower_bound_total_path_cost)) {
			//queue is sorted by lower bound total cost
			if(this.debugExpansion) this.printInfo("\t\t touched previously");
			data.setPartialPathCost(new_partial_path_cost);
			data.setPrev(rnode);
			if(rnode != null) data.setLevel(rnode.rnodeData.getLevel()+1);
			this.queue.add(new QueueElement<E>(childRNode, new_lower_bound_total_path_cost));
		}
	}
	
	private float expectMahatD(RNode<E> childRNode, Connection<E>con){
		float md;
		if(this.itry == 1){
			md = Math.abs(childRNode.centerx - con.sink.getTile().getColumn()) + Math.abs(childRNode.centery - con.sink.getTile().getRow());
		}else{
			md = Math.abs(childRNode.centerx - con.getSinkRNode().centerx) + Math.abs(childRNode.centery - con.getSinkRNode().centery);
		}
		return md;
	}

	private float getRouteNodeCost(RNode<E> rnode, Connection<E> con, int countSourceUses) {
		RNodeData<E> data = rnode.rnodeData;
		
		boolean containsSource = countSourceUses != 0;
		//Present congestion cost
		float pres_cost;
		if(containsSource) {
			int overoccupation = data.numUniqueSources() - rnode.capacity;
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
		if(rnode.type == RoutableType.INTERRNODE) {
			Netplus<E> net = con.getNet();
			bias_cost = 0.5f * rnode.base_cost / net.fanout * 
					(Math.abs(rnode.centerx - net.x_geo) + Math.abs(rnode.centery - net.y_geo)) / net.hpwl;
		}
		
		/*if(this.debugExpansion)
			this.printInfo("\t\t rnode cost = b(n)*h(n)*p(n)/(1+sourceUsage) = " + rnode.base_cost + " * " + data.getAcc_cost()+ " * " + pres_cost + " / (1 + " + countSourceUses + ") + " + bias_cost);
		*/
		return rnode.base_cost * data.getAcc_cost() * pres_cost / (1 + countSourceUses) + bias_cost;
	}
	
	/******************************************************************
	 * routing tester: check if the whole design is successfully routed
	 * or if there exists any illegal/congested RNode
	 ******************************************************************/
	public boolean isValidRouting(){
		for(RNode<E> rnode:this.rnodesCreated.values()){
			if(rnode.overUsed()){
				return false;
			}
		}
		return true;
	}
	
	/*
	 * statistics output for each router iteration
	 */
	public void staticticsInfo(List<Connection<E>> connections, 
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
	
	public void getAllHopsAndManhattanD(){
		//first check if routing is valid
		int err = 0;
		for(RNode<E> rn:this.rnodesCreated.values()){
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
		Set<RNode<E>> netRNodes = new HashSet<>();
		for(Netplus<E> net:this.nets){	
			for(Connection<E> c:net.getConnection()){
				netRNodes.addAll(c.rnodes);
				this.hops += c.rnodes.size() - 1;//hops for all sinks
			}
			for(RNode<E> rnode:netRNodes){
				this.manhattanD += rnode.getManhattanD();
			}
			netRNodes.clear();
		}
	}
	
	public void getOverusedAndIllegalRNodesInfo(List<Connection<E>> connections) {
		this.usedRNodes.clear();
		this.overUsedRNodes.clear();
		this.illegalRNodes.clear();
		for(Connection<E> conn : connections) {
			for(RNode<E> rnode : conn.rnodes) {
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
	
	public int getIllegalNumRNodes(List<Connection<E>> cons){
		Set<String> illegal = new HashSet<>();	
		for(Connection<E> c:cons){
			for(RNode<E> rn:c.rnodes){
				if(rn.illegal()){
					illegal.add(rn.name);
				}
			}
		}	
		return illegal.size();	
	}
	
	public void fixIllegalTree(List<Connection<E>> cons) {
//		this.printInfo("checking if there is any illegal node");	
		int numIllegal = this.getIllegalNumRNodes(cons);	
		if(numIllegal > 0){
//			this.printInfo("There are " + numIllegal + " illegal routing tree nodes");
			
			List<Netplus<E>> illegalTrees = new ArrayList<>();
			for(Netplus<E> net : this.getNets()) {
				boolean illegal = false;
				for(Connection<E> con : net.getConnection()) {
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
			for(Netplus<E> illegalTree : illegalTrees){
				RNode<E> illegalRNode;
				while((illegalRNode = illegalTree.getIllegalNode()) != null){
					List<Connection<E>> illegalCons = new ArrayList<>();
					for(Connection<E> con : illegalTree.getConnection()) {
						for(RNode<E> rnode : con.rnodes) {
							if(rnode.equals(illegalRNode)) {
								illegalCons.add(con);
							}
						}
					}
					
					//fixing the illegal trees, since there is no criticality info, use the hops info
					//Find the illegal connection with maximum number of RNodes (hops)
					Connection<E> maxCriticalConnection = illegalCons.get(0);
					for(Connection<E> illegalConnection : illegalCons) {
						if(illegalConnection.rnodes.size() > maxCriticalConnection.rnodes.size()) {
							maxCriticalConnection = illegalConnection;
						}
					}
					
					//Get the path from the connection with maximum hops
					List<RNode<E>> newRouteNodes = new ArrayList<>();
					boolean add = false;
					for(RNode<E> newRouteNode : maxCriticalConnection.rnodes) {
						if(newRouteNode.equals(illegalRNode)) add = true;
						if(add) newRouteNodes.add(newRouteNode);
					}
					
					//Replace the path of each illegal connection with the path from the connection with maximum hops
					for(Connection<E> illegalConnection : illegalCons) {
						this.ripup(illegalConnection);
						
						//Remove illegal path from routing tree
						while(!illegalConnection.rnodes.remove(illegalConnection.rnodes.size() - 1).equals(illegalRNode));
						
						//Add new path to routing tree
						for(RNode<E> newRouteNode : newRouteNodes) {
							illegalConnection.addRNode(newRouteNode);
						}
						
						this.add(illegalConnection);
					}
					
				}
			}
		}	
	}

	/********************************
	 * print PIPs info of routed nets
	 ********************************/
	public void checkPIPsInfo(){
		for(Netplus<E> netp:this.nets){
			if(netp.getNet().hasPIPs() && netp.getNet().getPIPs().size() == 4){
				System.out.println(netp.getNet().toStringFull());
				for(PIP p:netp.getNet().getPIPs()){
					Wire start = p.getStartWire();
					Wire end = p.getEndWire();
					
					System.out.println("str wire tile: " + start.getTile().getColumn() + " " 
											+ start.getTile().getRow() + " " + start.getIntentCode());
					System.out.println("end wire tile: " + end.getTile().getColumn() + " " 
							+ end.getTile().getRow() + " " + end.getIntentCode());
				}
				System.out.println("\n");
			}
			
		}
	}
	/********************************
	 * nets with bi-directional pips
	 ********************************/
	public void netsWithBidirecPips(){
		List<Netplus<E>> netsWithBidirectionalPIPs = new ArrayList<>();
		for(Netplus<E> netp:this.nets){
			if(netp.getNet().hasPIPs()){
				boolean bidire = false;
				List<PIP> pips = netp.getNet().getPIPs();
				for(PIP p:pips){
					if(p.isBidirectional()){
						bidire = true;
					}
				}
				if(bidire){
					netsWithBidirectionalPIPs.add(netp);
				}
			}
		}
	}
	/********************************
	 * print net info
	 ********************************/
	public void printNetInfo(){
		for(Netplus<E> n:this.nets){
			if(n.getNet().getSource() != null){
				System.out.println("-------------------------------------------");
				System.out.println("srce tile -> (" + n.getNet().getSource().getTile().getColumn() + ", " 
				+ n.getNet().getSource().getTile().getRow() + ") type: "	+ n.getNet().getSource().getTile().getName());
				for(SitePinInst sink:n.getNet().getSinkPins()){
					System.out.println("sink tile -> (" + sink.getTile().getColumn() + ", " 
				+ sink.getTile().getRow() + ") type: " + sink.getTile().getName());
				}
				
			}
			System.out.println(n.getNet().toStringFull());
		}
	}
	
	public List<Netplus<E>> getNets() {
		return nets;
	}

	public void setNets(List<Netplus<E>> nets) {
		this.nets = nets;
	}

	public List<Connection<E>> getConnections() {
		return connections;
	}

	public void setConnections(List<Connection<E>> connections) {
		this.connections = connections;
	}

	public Design getDesign(){
		return this.design;
	}
	
	public int getItry() {
		return itry;
	}

	public void updateItry() {
		this.itry++;
	}
		
	public float getPres_fac() {
		return pres_fac;
	}

	public void setPres_fac(float pres_fac) {
		this.pres_fac = pres_fac;
	}

	public long getNodesExpanded() {
		return nodesExpanded;
	}
	
	public int getUsedRNodes() {
		return this.usedRNodes.size();
	}

	public void increaseNodesExpanded() {
		this.nodesExpanded++;
	}

	public int getConnectionsRouted() {
		return connectionsRouted;
	}

	public void setConnectionsRouted(int connectionsRouted) {
		this.connectionsRouted = connectionsRouted;
	}

	public int getConnectionsRoutedIteration() {
		return connectionsRoutedIteration;
	}

	public void resetConnectionsRoutedIteration() {
		this.connectionsRoutedIteration = 0;
	}	
	
	public Collection<RNodeData<E>> getRNodesTouched() {
		return rnodesTouched;
	}

	public PriorityQueue<QueueElement<E>> getQueue() {
		return queue;
	}

	public Set<RNode<E>> getRnodesCreated() {
		return (Set<RNode<E>>) rnodesCreated.values();
	}
	
	public long getHops() {
		return hops;
	}

	public float getManhattanD() {
		return manhattanD;
	}

	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}
	
}
