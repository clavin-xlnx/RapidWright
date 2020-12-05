package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.timing.GroupDelayType;
import com.xilinx.rapidwright.timing.ImmutableTimingGroup;
import com.xilinx.rapidwright.timing.NodeWithFaninInfo;
import com.xilinx.rapidwright.timing.SiblingsTimingGroup;
import com.xilinx.rapidwright.timing.TimingModel;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorTable;
import com.xilinx.rapidwright.util.Pair;

public class RoutableTimingGroup implements Routable{
	public int index;
	private SiblingsTimingGroup sibTimingGroups;
//	public GroupDelayType groupType;
	public RoutableType type;
	private ImmutableTimingGroup thruImmuTg;//could be removed? use index of the childrenImmuTG objects, ArrayList.indexOf()?
	
	public short xlow, xhigh;
	public short ylow, yhigh;
	
	public float base_cost;
	public float delay;
	
	public final RoutableData rnodeData;//data for the siblings, that is for the exit nodes
	
	/** A flag to indicate the router in expansion
	* true: should be pushed into the queue directly with cost copied from the parent
	* false: after being popped out from the queue, cost should be calculated
	*/
	public boolean virtualMode;
		
	static Set<NodeWithFaninInfo> entryNodesExpanded;
	
	public boolean target;
	public List<Pair<RoutableTimingGroup, ImmutableTimingGroup>> childrenImmuTG;
	public boolean childrenSet;
	
	public boolean delaySet = false;
	
	static {
		entryNodesExpanded = new HashSet<>();
	}
	
	public RoutableTimingGroup(int index, SitePinInst sitePinInst, RoutableType type, DelayEstimatorTable estimator){
		this.index = index;
		this.type = type;
		
		this.sibTimingGroups = new SiblingsTimingGroup(sitePinInst);		
		this.rnodeData = new RoutableData(this.index);
		this.target = false;
		this.childrenSet = false;
		this.setXY();
		this.thruImmuTg = null;
		this.setDelay(estimator.getDelayOfSitePin(sitePinInst));
		this.sibTimingGroups.getSiblings()[0].setDelay((short)this.delay);
		if(this.type == RoutableType.SOURCERR){
			this.virtualMode = false;
		}else{
			this.virtualMode = true; // true or false does not matter to sinkrr, becase of the isTarget() check
		}
	}
	
	public RoutableTimingGroup(int index, SiblingsTimingGroup sTimingGroups){
		this.index = index;
		this.type = RoutableType.INTERRR;
		this.sibTimingGroups = sTimingGroups;
		this.rnodeData = new RoutableData(this.index);
		this.target= false;
		this.childrenSet = false;	
		this.setXY();
		this.thruImmuTg = null;
		this.virtualMode = true;
	}
	
	public Pair<Integer, Long> setChildren(int globalIndex, float base_cost_fac, 
			Map<NodeWithFaninInfo, RoutableTimingGroup> createdRoutable, 
			Set<Node> reservedNodes,
			RouteThruHelper helper,
			boolean timingDriven,
			DelayEstimatorTable estimator,
			RouterTimer timer,
			long callingOfGetNextRoutable){
		
//		timer.getNextRoutable.start();
		List<SiblingsTimingGroup> next = this.sibTimingGroups.getNextSiblingTimingGroups(reservedNodes);
//		timer.getNextRoutable.finish();
		
//		timer.getNextDummy.start();
//		timer.getNextDummy.finish();
		
//		timer.addChildren.start();
		callingOfGetNextRoutable++;
		this.childrenImmuTG = new ArrayList<>();
		for(SiblingsTimingGroup stGroups : next){
			
			RoutableTimingGroup childRNode;
			ImmutableTimingGroup thruImmuTg;
			Pair<RoutableTimingGroup,ImmutableTimingGroup> childThruImmuTg;
			
			NodeWithFaninInfo key = stGroups.getExitNode();//using node as the key is necessary, different nodes may have a same hasCode()
			
			if(!createdRoutable.containsKey(key)){
				childRNode = new RoutableTimingGroup(globalIndex, stGroups);
				childRNode.setBaseCost(base_cost_fac);
							
				thruImmuTg = stGroups.getThruImmuTg(this.sibTimingGroups.getExitNode());
				
				if(timingDriven){
					short delay = estimator.getDelayOf(thruImmuTg);
					if(delay == -3)
						System.out.println("  parent exit node: " + this.sibTimingGroups.getExitNode().toString());
					if(delay <= 0){
						System.out.println("delay = " + delay + ", " + thruImmuTg.toString() + ", parent exit node: " + this.sibTimingGroups.getExitNode().toString());
						delay = 0;
					}
					childRNode.setDelay(delay);//TODO check //moved to delay of Siblings 
					thruImmuTg.setDelay(delay);
				}
				
				childThruImmuTg = new Pair<>(childRNode, thruImmuTg);
				
				globalIndex++;

				this.childrenImmuTG.add(childThruImmuTg);
				createdRoutable.put(key, childRNode);
			}else{
				childRNode = createdRoutable.get(key);
				
				
				thruImmuTg = childRNode.getSiblingsTimingGroup().getThruImmuTg(this.sibTimingGroups.getExitNode());//RouterHelper.findImmutableTimingGroup(this, createdRoutable.get(key))
				if(timingDriven){
					short delay = estimator.getDelayOf(thruImmuTg);
					if(delay == -3)
						System.out.println("  parent exit node: " + this.sibTimingGroups.getExitNode().toString());
					if(delay <= 0){
						System.out.println("delay = " + delay + ", " + thruImmuTg.toString() + ", parent exit node: " + this.sibTimingGroups.getExitNode().toString());
						delay = 0;
					}
					childRNode.setDelay(delay);
					thruImmuTg.setDelay(delay);
				}
				this.childrenImmuTG.add(new Pair<>(childRNode, thruImmuTg));
			}
			
			//store entry nodes and initialize the costs of entry nodes
			NodeWithFaninInfo entry = thruImmuTg.entryNode();
			putNewEntryNode(entry);//better to be here than to be in the expansion
		}
		
		this.childrenSet = true;
//		timer.addChildren.finish();
		
		return new Pair(globalIndex, callingOfGetNextRoutable);
	}
	
	public float getDelay(){
		return this.delaySet ? this.delay : 0f;
	}
	
	public void setDelay(short d){
		this.delay = d;
		this.delaySet = true;
	}
	
	public static void putNewEntryNode(NodeWithFaninInfo entry){
		if(entry != null && !entryNodesExpanded.contains(entry)){
			entry.initialize();//create sources and parents CountingSet, initialize pres_cost and acc_cost
			entryNodesExpanded.add(entry);
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
	
	//TODO ADJUST (1 1 0.95 orginal)
	public void setBaseCost(){
		if(this.type == RoutableType.SOURCERR){
			base_cost = 1f;
			
		}else if(this.type == RoutableType.INTERRR){
			//aver cost around 4 when using deltaX + deltaY +1 
			//(most (deltaX + deltaY +1 ) values range from 1 to 90+, maximum can be 176)
			//(deltaX + deltaY +1 ) normalized to the maximum , does not work
			base_cost = 1f;
			
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
	
	//separating occupancy methods of rnode and the entry node makes sense and makes the router 3x faster
	public int getOccupancy(){
//		return Math.max(this.findMaximumOccEntryNodes(), this.rnodeData.getOccupancy());//not valid any more if the entry node costs are added to the siblings cost
		return this.rnodeData.getOccupancy();
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
		/*for(ImmutableTimingGroup tg:this.sibTimingGroups.getSiblings()){
			if(tg.entryNode() != null)
				wiresInTG.addAll(Arrays.asList(tg.entryNode().getAllWiresInNode()));
			if(tg.exitNode() != null)
				wiresInTG.addAll(Arrays.asList(tg.exitNode().getAllWiresInNode()));
		}*/
		wiresInTG.addAll(Arrays.asList(this.sibTimingGroups.getExitNode().getAllWiresInNode()));
		
		List<Short> xCoordinates = new ArrayList<>();
		List<Short> yCoordinates = new ArrayList<>();
		
		for(Wire w:wiresInTG){
			Tile tile = w.getTile();
			if(tile.getTileTypeEnum() == TileTypeEnum.INT){
				
				xCoordinates.add((short) w.getTile().getColumn());
				yCoordinates.add((short) w.getTile().getRow());
			}
		}
		
		this.xlow = this.min(xCoordinates);
		this.xhigh = this.max(xCoordinates);
		this.ylow = this.min(yCoordinates);
		this.yhigh = this.max(yCoordinates);
	}
	
	public short max(List<Short> coordinates){
		short max = 0;
		for(short c:coordinates){
			if(c > max)
				max = c;
		}
		return max;
	}
	public short min(List<Short> coordinates){
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
		
		int occ = data.numUniqueSources(); 
		int cap = Routable.capacity;
		
		if (occ < cap) {
			data.setPres_cost(1);
		} else {
			data.setPres_cost(1 + (occ - cap + 1) * pres_fac);
		}
	}
	
	public static void updatePresentCongestionPenaltyOfEntryNode(NodeWithFaninInfo entry, float pres_fac){
		int occ = entry.getOcc();
		int cap = Routable.capacity;
		
		if (occ < cap) {
			entry.setPresCost(1f);
		} else {
			entry.setPresCost(1 + (occ - cap + 1) * pres_fac);
		}
//		entryNodesExpanded.add(entry);//redundant
	}
	
	public static void updateEntryNodesCosts(float pres_fac, float acc_fac){
		for(NodeWithFaninInfo entry : entryNodesExpanded){
			int overuse = entry.getOcc() - Routable.capacity;
			
			if(overuse == 0){
				entry.setPresCost(1 + pres_fac);
			}else if(overuse > 0){
				entry.setPresCost(1 + (overuse + 1) * pres_fac);
				entry.setAccCost(entry.getAccCost() + overuse * acc_fac);
				
			}
//			entryNodesExpanded.add(entry);
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
	
	public String toStringFull(){
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
	@Override
	public String toString(){
		
		StringBuilder s = new StringBuilder();
		s.append(this.toStringEntriesAndExit());
		s.append(", ");
		s.append(String.format("occupation = %d", this.getOccupancy()));
		s.append(", ");
		s.append(String.format("num_unique_sources = %d", this.rnodeData.numUniqueSources()));
		s.append(", ");
		s.append(String.format("num_unique_parents = %d", this.rnodeData.numUniqueParents()));
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
		return this.xlow > con.net.x_min_b && this.xhigh < con.net.x_max_b && this.ylow > con.net.y_min_b && this.yhigh < con.net.y_max_b;
	}
	
	public boolean isInConBoundingBoxLimit(Connection con) {		
		return this.xlow > con.getX_min_b() && this.xhigh < con.getX_max_b() && this.ylow > con.getY_min_b() && this.yhigh < con.getY_max_b();
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
		return false;
	}

	@Override
	public boolean isBounce() {
		
		return false;
	}
	
	public GroupDelayType getGroupDelayType(){
		return this.sibTimingGroups.type();
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

	@Override
	public short getXmax() {
		return this.xhigh;
	}

	@Override
	public short getXmin() {
		return this.xlow;
	}

	@Override
	public short getYmax() {
		return this.yhigh;
	}

	@Override
	public short getYmin() {
		return this.ylow;
	}	
}
