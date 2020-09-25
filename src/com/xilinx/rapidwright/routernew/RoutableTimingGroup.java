package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.timing.GroupDelayType;
import com.xilinx.rapidwright.timing.ImmutableTimingGroup;
import com.xilinx.rapidwright.timing.SiblingsTimingGroup;
import com.xilinx.rapidwright.timing.TimingModel;
import com.xilinx.rapidwright.util.Pair;

public class RoutableTimingGroup implements Routable{
	public int index;
	private SiblingsTimingGroup sibTimingGroups;
	public GroupDelayType groupType;
	public RoutableType type;
	private ImmutableTimingGroup thruImmuTg;//could be removed? TODO use index of the childrenImmuTG objects, ArrayList.indexOf()?
	
	public short xlow, xhigh;
	public short ylow, yhigh;
	
	public float base_cost;
	
	public final RoutableData rnodeData;//data for the siblings, that is for the exit nodes
	
	//belong to the class, visible to all RoutableTimingGroup
	static Map<Node, CountingSet<SitePinInst>> entryNodeSources;
	static Map<Node, CountingSet<Routable>> entryNodeParents;
	static Map<Node, Pair<Float, Float>> entryNodePresHistCosts;//lazy adding approach: creating a pair of costs when meet an entry node
	
	public boolean target;
	public List<Pair<RoutableTimingGroup, ImmutableTimingGroup>> childrenImmuTG;//TODO use this one
	public boolean childrenSet;
	
	public boolean debug = false;
	
	static {
		//Node - CountingSet maps are needed for per-connection routing
		//retaining per connection routing is needed for future connection-aware parallelization
		entryNodeSources = new HashMap<>();
		entryNodeParents  = new HashMap<>();
		entryNodePresHistCosts = new HashMap<>();
	}
	
	public RoutableTimingGroup(int index, SitePinInst sitePinInst, RoutableType type, TimingModel tmodel){
		this.index = index;
		this.type = type;
		
		this.sibTimingGroups = new SiblingsTimingGroup(sitePinInst);
		this.groupType = this.sibTimingGroups.type();		
		this.rnodeData = new RoutableData(this.index);
		this.target = false;
		this.childrenSet = false;
		this.setXY();
		this.thruImmuTg = null;
	}
	
	public RoutableTimingGroup(int index, SiblingsTimingGroup sTimingGroups){
		this.index = index;
		this.type = RoutableType.INTERRR;
		this.sibTimingGroups = sTimingGroups;
		this.groupType = this.sibTimingGroups.type();
		this.rnodeData = new RoutableData(this.index);
		this.target= false;
		this.childrenSet = false;	
		this.setXY();
		this.thruImmuTg = null;
	}
	
	public int setChildren(int globalIndex, float base_cost_fac, 
			Map<Node, RoutableTimingGroup> createdRoutable, 
			Set<Node> reservedNodes,
			RouteThruHelper helper){
		
		this.childrenImmuTG = new ArrayList<>();
		
		List<Pair<SiblingsTimingGroup,ImmutableTimingGroup>> next = this.sibTimingGroups.getNextSiblingTimingGroups(reservedNodes, helper);
		
		for(Pair<SiblingsTimingGroup,ImmutableTimingGroup> stGroups : next){
			
			RoutableTimingGroup childRNode;
			ImmutableTimingGroup thruImmuTg;
			Pair<RoutableTimingGroup,ImmutableTimingGroup> childThruImmuTg;
			
			Node key = stGroups.getFirst().getExitNode();//TODO Yun - why this is necessary and using SiblingsTimingGroup hash code does not work
			
			if(!createdRoutable.containsKey(key)){
				childRNode = new RoutableTimingGroup(globalIndex, stGroups.getFirst());
				childRNode.setBaseCost(base_cost_fac);
				
				thruImmuTg = stGroups.getSecond();
				
				childThruImmuTg = new Pair<>(childRNode, thruImmuTg);
				
				globalIndex++;

				this.childrenImmuTG.add(childThruImmuTg);
				createdRoutable.put(key, childRNode);
			}else{
				childRNode = createdRoutable.get(key);

				thruImmuTg = RouterHelper.findImmutableTimingGroup(this, createdRoutable.get(key));

				this.childrenImmuTG.add(new Pair<>(childRNode, thruImmuTg));
			}
			
			/*if(this.index == 58597 && childRNode.index == 70491){
			 * //TODO this is where the conflicts come from
			 * the entry node connecting two exit nodes INT_X9Y101/WW1_E_7_FT0 -> * -> INT_X9Y102/BOUNCE_W_0_FT1 is not identical
			 * INT_X9Y102/INODE_W_1_FT1 from RouterHelper, while it is INT_X9Y101/INODE_W_62_FT0 from the API getNextSiblings
				System.out.println(thruImmuTg.toString()); //ImmuTg = ( INT_X9Y101/INODE_W_62_FT0, INT_X9Y102/BOUNCE_W_0_FT1 )
				System.out.println();//this thruImmuTg returned from the API is different from when calling RouterHelper.findImmuTgBetweenTwoSiblings()
			}*/
			
			//for checking up on the above finding
			/*if(this.sibTimingGroups.getExitNode().toString().equals("INT_X9Y101/WW1_E_7_FT0")){
				System.out.println("all downhill nodes of exit node INT_X9Y101/WW1_E_7_FT0 in Siblings " + this.index + ":");
				for(Node nextNode:this.sibTimingGroups.getExitNode().getAllDownhillNodes()){
					System.out.println(nextNode.toString());
				}
			}
			if(this.sibTimingGroups.getExitNode().toString().equals("INT_X9Y102/BOUNCE_W_0_FT1")){
				System.out.println("all uphill nodes of exit node INT_X9Y102/BOUNCE_W_0_FT1 in Siblings " + this.index + ":");
				for(Node nextNode:this.sibTimingGroups.getExitNode().getAllUphillNodes()){
					System.out.println(nextNode.toString());
				}
			}*/
			
			//store entry nodes and initialize the costs of entry nodes
			//in consistent with the initialization of each routable
			Node entry = thruImmuTg.entryNode();
			this.putNewEntryNode(entry);
		}
		
		this.childrenSet = true;
		return globalIndex;
	}
	
	public void putNewEntryNode(Node entry){
		if(entry != null && !entryNodePresHistCosts.containsKey(entry)){
			entryNodePresHistCosts.put(entry, new Pair<>(1f, 1f));
		}
	}
	
	public List<Node> downHillNodesodTheLastNode(){
		List<Node> nodes = this.sibTimingGroups.getSiblings()[0].exitNode().getAllDownhillNodes();
		for(Node node:nodes){
			System.out.println(node.toString() + " downhill nodes: ");
			for(Node dn:node.getAllDownhillNodes()){
				System.out.println("\t" + dn.toString());
			}
		}
		return nodes;
	}
	
	@Override
	public void setBaseCost(float fac) {
		this.setBaseCost();
		this.base_cost *= fac;//(this.xhigh - this.xlow) + (this.yhigh - this.ylow) + 1;
	}
	
	public void setBaseCost(){
		if(this.type == RoutableType.SOURCERR){
			base_cost = 1;
			
		}else if(this.type == RoutableType.INTERRR){
			//aver cost around 4 when using deltaX + deltaY +1 
			//(most (deltaX + deltaY +1 ) values range from 1 to 90+, maximum can be 176)
			//(deltaX + deltaY +1 ) normalized to the maximum , does not work
			base_cost = 1;
			
		}else if(this.type == RoutableType.SINKRR){//this is for faster maze expansion convergence to the sink
			base_cost = 0.95f;//virtually the same to the logic block input pin, since no alternative ipins are considered
		}
	}

	@Override
	public boolean overUsed() {
		return Routable.capacity < this.rnodeData.getOccupancy();
	}
	
	@Override
	public boolean used(){
		return this.rnodeData.getOccupancy() > 0;
	}
	
	@Override
	public boolean illegal(){
		return Routable.capacity < this.rnodeData.numUniqueParents();
	}
	
	//TODO separating occupancy methods of rnode and the entry node makes sense and makes the router 3x faster
	public int getOccupancy(){
//		return Math.max(this.findMaximumOccEntryNodes(), this.rnodeData.getOccupancy());//not valid any more if the entry node costs are added to the siblings cost
		return this.rnodeData.getOccupancy();
	}
	
	public int findMaximumOccEntryNodes(){
		int occ = 0;
		ImmutableTimingGroup[] itgs = this.sibTimingGroups.getSiblings();
		for(int i = 0; i < itgs.length; i++){
			Node entry = itgs[i].entryNode();
			if(entry != null && entryNodeSources.containsKey(entry)){
				int usage = entryNodeSources.get(entry).uniqueSize();
				if(usage > occ){
					occ = usage;
				}
			}
		}
		return occ;
	}

	public ImmutableTimingGroup getThruImmuTg() {
		return thruImmuTg;
	}

	public void setThruImmuTg(ImmutableTimingGroup thruImmuTg) {
		this.thruImmuTg = thruImmuTg;
	}

	@Override
	public void setXY() {
		List<Wire> wiresInTG = new ArrayList<>();
		for(ImmutableTimingGroup tg:this.sibTimingGroups.getSiblings()){
			if(tg.entryNode() != null)
				wiresInTG.addAll(Arrays.asList(tg.entryNode().getAllWiresInNode()));
			if(tg.exitNode() != null)
				wiresInTG.addAll(Arrays.asList(tg.exitNode().getAllWiresInNode()));
		}
		int length = wiresInTG.size();
		short[] xCoordinates = new short[length];
		short[] yCoordinates = new short[length];
		
		short id = 0;
		for(Wire w:wiresInTG){
			xCoordinates[id] = (short) w.getTile().getColumn();
			yCoordinates[id] = (short) w.getTile().getRow();
			id++;
		}
		
		this.xlow = this.min(xCoordinates);
		this.xhigh = this.max(xCoordinates);
		this.ylow = this.min(yCoordinates);
		this.yhigh = this.max(yCoordinates);
	}
	
	public short max(short[] coordinates){
		short max = 0;
		for(short c:coordinates){
			if(c > max)
				max = c;
		}
		return max;
	}
	public short min(short[] coordinates){
		short min = 10000;
		for(short c:coordinates){
			if(c < min)
				min = c;
		}
		return min;
	}
	
	@Override
	public void updatePresentCongestionPenalty(float pres_fac) {
		
		RoutableData data = this.rnodeData;
		
		int occ = data.numUniqueSources();//TODO max? a specific entry node occ and rnode data occ 
		int cap = Routable.capacity;
		
		if (occ < cap) {
			data.setPres_cost(1);
		} else {
			data.setPres_cost(1 + (occ - cap + 1) * pres_fac);
		}
	}
	
	public static void updatePeresetCongestionPenaltyOfEntryNode(Node entry, float pres_fac){
		int occ = entryNodeSources.get(entry).uniqueSize();
		int cap = Routable.capacity;
		Pair<Float, Float> presHistCosts = entryNodePresHistCosts.get(entry);
		if (occ < cap) {
			presHistCosts.setFirst(1f);
		} else {
			presHistCosts.setFirst(1 + (occ - cap + 1) * pres_fac);
		}
		entryNodePresHistCosts.put(entry, presHistCosts);
	}
	
	public static void updateEntryNodesCosts(float pres_fac, float acc_fac){
		for(Node entry : entryNodePresHistCosts.keySet()){
			int overuse;
			if(entryNodeSources.get(entry) == null){
				overuse = -1;
			}else{
				overuse = entryNodeSources.get(entry).uniqueSize() - Routable.capacity;
			}
			Pair<Float, Float> presHistCosts = entryNodePresHistCosts.get(entry);
			if(overuse == 0){
				presHistCosts.setFirst(1 + pres_fac);
			}else if(overuse > 0){
				presHistCosts.setFirst(1 + (overuse + 1) * pres_fac);
				presHistCosts.setSecond(presHistCosts.getSecond() + overuse * acc_fac);
			}
			entryNodePresHistCosts.put(entry, presHistCosts);
		}
	}

	@Override
	public float getCenterX() {
		return (this.xhigh + this.xlow) / 2;
	}
	
	@Override
	public float getCenterY() {
		return (this.yhigh + this.ylow) / 2;
	}
	
	@Override
	public String toString(){
		String coordinate = "";
		if(this.xlow == this.xhigh && this.ylow == this.yhigh) {
			coordinate = "(" + this.xlow + "," + this.ylow + ")";
		} else {
			coordinate = "(" + this.xlow + "," + this.ylow + ") to (" + this.xhigh + "," + this.yhigh + ")";
		}
		
		StringBuilder s = new StringBuilder();
		s.append("RNode " + this.index + " ");
		s.append(String.format("%-11s", coordinate));
		s.append(", last node ");
		s.append(this.sibTimingGroups.getExitNode().toString());
		s.append(", ");
		s.append(String.format("type = %s", this.type));
		
		return s.toString();
	}
	
	public String toStringFull(){
		
		StringBuilder s = new StringBuilder();
		s.append(this.toString());
		s.append(", ");
		s.append(String.format("occupation = %d", this.getOccupancy()));
		s.append(", ");
		s.append(String.format("num_unique_sources = %d", this.rnodeData.numUniqueSources()));
		s.append(", ");
		s.append(String.format("num_unique_parents = %d", this.rnodeData.numUniqueParents()));
//		s.append(", ");
//		s.append(String.format("level = %d", this.rnodeData.getLevel()));
		s.append(",");
		s.append(this.sibTimingGroups.getSiblings()[0].exitNode().toString());
		s.append(", ");
		s.append(String.format("type = %s", this.type));
		
		return s.toString();
	}
	
	public String toStringEntriesAndExit(){
		String s = this.type + ", Siblings " + this.index + " = { ";
		ImmutableTimingGroup[] immuTgs = sibTimingGroups.getSiblings();
		if(immuTgs.length == 1){
			s += immuTgs[0].exitNode().toString() + " }";
		}else{
			s += "( ";
			for(int i = 0; i < immuTgs.length; i++){
				s += immuTgs[i].entryNode().toString() + "  ";
			}
			s += " ) -> " + immuTgs[0].exitNode() + " }"; 
		}
		
		return s;
	}
	
	public String toStringShort(){
		String coordinate = "";
		if(this.xlow == this.xhigh && this.ylow == this.yhigh) {
			coordinate = "(" + this.xlow + "," + this.ylow + ")";
		} else {
			coordinate = "(" + this.xlow + "," + this.ylow + ") to (" + this.xhigh + "," + this.yhigh + ")";
		}
		
		StringBuilder s = new StringBuilder();
		s.append("Last Node " + this.sibTimingGroups.getSiblings()[0].exitNode().toString() + " ");
		s.append(", ");
		s.append(String.format("%-11s", coordinate));
		s.append(", ");
		s.append(String.format("type = %s", this.type));
		
		return s.toString();
	}
	
	@Override
	public int hashCode(){
		return this.index;
	}

	@Override
	public float getManhattanD() {
		float md = 0;
		if(this.rnodeData.getPrev() != null){
			md = Math.abs(this.rnodeData.getPrev().getCenterX() - this.getCenterX()) + Math.abs(this.rnodeData.getPrev().getCenterY() - this.getCenterY());
		}
		return md;
	}

	
	public boolean isInBoundingBoxLimit(Connection con) {		
		return this.xlow < con.net.x_max_b && this.xhigh > con.net.x_min_b && this.ylow < con.net.y_max_b && this.yhigh > con.net.y_min_b;
	}

	public SiblingsTimingGroup getSiblingsTimingGroup() {
		return this.sibTimingGroups;
	}
	
	@Override
	public boolean isTarget() {
		return this.target;
	}

	@Override
	public void setTarget(boolean isTarget) {
		this.target = isTarget;	
	}

	@Override
	public RoutableType getRoutableType() {
		return this.type;
	}

	@Override
	public boolean isGlobal() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isBounce() {
		// TODO Auto-generated method stub
		return false;
	}
	
	@Override
	public float getPres_cost() {
		return this.rnodeData.getPres_cost();
	}

	@Override
	public void setPres_cost(float pres_cost) {	
		this.rnodeData.setPres_cost(pres_cost);
	}

	@Override
	public float getAcc_cost() {
		return this.rnodeData.getAcc_cost();
	}

	@Override
	public void setAcc_cost(float acc_cost) {
		this.rnodeData.setAcc_cost(acc_cost);	
	}

	@Override
	public Node getNode() {
		return this.sibTimingGroups.getExitNode();
	}	
}
