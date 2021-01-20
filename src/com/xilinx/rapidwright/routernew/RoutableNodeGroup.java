package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
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
import com.xilinx.rapidwright.timing.GroupWireDirection;
import com.xilinx.rapidwright.timing.NodeGroup;
import com.xilinx.rapidwright.timing.EntryNode;
import com.xilinx.rapidwright.timing.NodeGroupSiblings;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorTable;
import com.xilinx.rapidwright.util.Pair;

public class RoutableNodeGroup implements Routable{
	public int index;
	private NodeGroupSiblings nodeGroupSiblings;
	private RoutableType type;
	private NodeGroup thruNodeGroup;
	
	private short x, y;
	
	private float base_cost;
	private float delay = -1000;//-1000 to indicate unset delay status
	
	private final RoutableData rnodeData;//data for the siblings, that is for the exit nodes
	
	/** A flag to indicate the router in expansion
	* true: should be pushed into the queue directly with cost copied from the parent
	* false: after being popped out from the queue, cost should be calculated
	*/
	public boolean virtualMode;
		
	static Set<EntryNode> entryNodesExpanded;
	
	public boolean target;
	public List<Pair<RoutableNodeGroup, NodeGroup>> childrenAndThruGroup;
	public boolean childrenSet;
	
	static {
		entryNodesExpanded = new HashSet<>();
	}
	
	public RoutableNodeGroup(int index, SitePinInst sitePinInst, RoutableType type, DelayEstimatorTable estimator){
		this.index = index;
		this.type = type;
		
		this.nodeGroupSiblings = new NodeGroupSiblings(sitePinInst);		
		this.rnodeData = new RoutableData(this.index);
		this.target = false;
		this.childrenSet = false;
		this.setXY();
		this.setBaseCost();
		this.thruNodeGroup = null;
		if(estimator != null) this.setDelay(estimator.getDelayOfSitePin(sitePinInst));
		this.nodeGroupSiblings.getSiblings()[0].setDelay((short)this.delay);
		if(this.type == RoutableType.PINFEED_O){
			this.virtualMode = false;
		}else{
			this.virtualMode = true; // true or false does not matter to sinkrr, becase of the isTarget() check
		}
	}
	
	public RoutableNodeGroup(int index, NodeGroupSiblings nodeGroupSiblings){
		this.index = index;
		this.nodeGroupSiblings = nodeGroupSiblings;
		this.rnodeData = new RoutableData(this.index);
		this.target= false;
		this.childrenSet = false;	
		this.setXY();
		this.setBaseCost();
		this.thruNodeGroup = null;
		this.virtualMode = true;
	}
	
	public Pair<Integer, Long> setChildren(int globalIndex,
			Map<Node, RoutableNodeGroup> createdRoutable, 
			Set<Node> reservedNodes,
			RouteThruHelper helper,
			boolean timingDriven,
			DelayEstimatorTable estimator,
			long callingOfGetNextRoutable){
		
		List<NodeGroupSiblings> next = this.nodeGroupSiblings.getNextNodeGroupSiblings(reservedNodes);
		callingOfGetNextRoutable++;
		this.childrenAndThruGroup = new ArrayList<>();
		
		for(NodeGroupSiblings nodeGroupSiblings : next){
			
			NodeGroup thruNodeGroup;		
			EntryNode key = nodeGroupSiblings.getExitNode();//using node as the key is necessary, different nodes may have a same hasCode()
			RoutableNodeGroup childRNode = createdRoutable.get(key);
			
			if(childRNode == null){
				childRNode = new RoutableNodeGroup(globalIndex, nodeGroupSiblings);
				globalIndex++;
				createdRoutable.put(key, childRNode);
				thruNodeGroup = nodeGroupSiblings.getThruNodeGroup(this.nodeGroupSiblings.getExitNode());	
			}else{				
				thruNodeGroup = childRNode.getNodeGroupSiblings().getThruNodeGroup(this.nodeGroupSiblings.getExitNode());
			}
			
			if(timingDriven){
				short delay = estimator.getDelayOf(thruNodeGroup);
				if(delay < 0 || delay > 16380){
					System.out.println("unexpected delay = " + delay + ", " + thruNodeGroup.toString() + ", parent exit node: " + this.nodeGroupSiblings.getExitNode().toString());
					delay = Short.MAX_VALUE/2;
				}
				childRNode.setDelay(delay);
				thruNodeGroup.setDelay(delay);
			}
			this.childrenAndThruGroup.add(new Pair<>(childRNode, thruNodeGroup));
			//store entry nodes and initialize the costs of entry nodes
			EntryNode entry = thruNodeGroup.entryNode();
			putNewEntryNode(entry);//better to be here than to be in the expansion
		}
		
		this.childrenSet = true;
		
		return new Pair<Integer, Long>(globalIndex, callingOfGetNextRoutable);
	}
	
	public float getDelay(){
		return this.delay;
	}
	
	@Override
	public void setDelay(short d){
		this.delay = d;
	}
	
	public static void putNewEntryNode(EntryNode entry){
		if(entry != null && !entryNodesExpanded.contains(entry)){
			entry.initialize();//create sources and parents CountingSet, initialize pres_cost and acc_cost
			entryNodesExpanded.add(entry);
		}
	}
	
	//TODO ADJUST (1 1 0.95 orginal)
	public void setBaseCost(){
		if(this.type == RoutableType.PINFEED_O){
			base_cost = 1f;
			
		}else if(this.type == RoutableType.PINFEED_I){
			base_cost = 0.95f;
		}else{
			GroupDelayType type = this.nodeGroupSiblings.groupDelayType();
			GroupWireDirection direction = this.nodeGroupSiblings.groupWireDirectiont();
			switch(type) {
			case SINGLE:
				if(direction == GroupWireDirection.VERTICAL)
					base_cost = 1f;
				else
					base_cost = 1.2f;
				this.type = RoutableType.WIRE;
				break;
			case DOUBLE:
				if(direction == GroupWireDirection.VERTICAL)
					base_cost = 1.2f;
				else
					base_cost = 1.4f;
				this.type = RoutableType.WIRE;
				break;
			case QUAD:
				if(direction == GroupWireDirection.VERTICAL)//vertical QUAD less expensive
					base_cost = 1.4f;
				else
					base_cost = 1.6f;
				this.type = RoutableType.WIRE;
				break;
			case LONG:
				if(direction == GroupWireDirection.VERTICAL)//vertical LONG less expensive
					base_cost = 1.6f;
				else
					base_cost = 1.8f;
				this.type = RoutableType.WIRE;
				break;
			case INTERNAL:
			case GLOBAL:
				base_cost = 1f;
				this.type = RoutableType.WIRE;
				break;
			case PIN_BOUNCE:
				base_cost = 1f;
				this.type = RoutableType.PINBOUNCE;
				break;
			case PINFEED:
				base_cost = 1f;
				this.type = RoutableType.PINFEED_I;
				break;
			default:
				base_cost = 1f;
				this.type = null;
			}
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
	
	public int getOccupancy(){
		return this.rnodeData.getOccupancy();
	}

	public NodeGroup getThruNodeGroup() {
		return thruNodeGroup;
	}

	public void setThruNodeGroup(NodeGroup thruNodeGroup) {
		this.thruNodeGroup = thruNodeGroup;
	}

	@Override
	public void setXY() {
		Wire[] wires = this.getNode().getAllWiresInNode();
		List<Tile> intTiles = new ArrayList<>();
		
		for(Wire w : wires) {
			if(w.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
				intTiles.add(w.getTile());
			}
		}
		
		if(intTiles.size() > 1) {
			this.x = (short) intTiles.get(1).getColumn();
			this.y = (short) intTiles.get(1).getRow();
		}else if(intTiles.size() == 1){
			this.x = (short) intTiles.get(0).getColumn();
			this.y = (short) intTiles.get(0).getRow();
		}else{
//			System.out.println("non-INT tile wire of node " + this.getNode());
			this.x = (short) wires[0].getTile().getColumn();
			this.y = (short) wires[0].getTile().getRow();
		}
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
	
	public static void updatePresentCongestionPenaltyOfEntryNode(EntryNode entry, float pres_fac){
		int occ = entry.getOcc();
		int cap = Routable.capacity;
		
		if (occ < cap) {
			entry.setPresCost(1f);
		} else {
			entry.setPresCost(1 + (occ - cap + 1) * pres_fac);
		}
	}
	
	public static void updateEntryNodesCosts(float pres_fac, float acc_fac){
		for(EntryNode entry : entryNodesExpanded){
			int overuse = entry.getOcc() - Routable.capacity;
			
			if(overuse == 0){
				entry.setPresCost(1 + pres_fac);
			}else if(overuse > 0){
				entry.setPresCost(1 + (overuse + 1) * pres_fac);
				entry.setAccCost(entry.getAccCost() + overuse * acc_fac);
				
			}
		}
	}
	
	public String toStringFull(){
		String coordinate = "";
		
		coordinate = "(" + this.x + "," + this.y + ")";
		
		StringBuilder s = new StringBuilder();
		s.append("RNode " + this.index + " ");
		s.append(String.format("%-11s", coordinate));
		s.append(", last node ");
		s.append(this.nodeGroupSiblings.getExitNode().toString());
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
		s.append(this.nodeGroupSiblings.getSiblings()[0].exitNode().toString());
		s.append(", ");
		s.append(String.format("type = %s", this.type));
		
		return s.toString();
	}
	
	public String toStringEntriesAndExit(){
		String s = this.type + ", Siblings " + this.index + " = { ";
		NodeGroup[] nodeGroups = nodeGroupSiblings.getSiblings();
		if(nodeGroups.length == 1){
			s += nodeGroups[0].exitNode().toString() + " }";
		}else{
			s += "( ";
			for(int i = 0; i < nodeGroups.length; i++){
				s += nodeGroups[i].entryNode().toString() + "  ";
			}
			s += " ) -> " + nodeGroups[0].exitNode() + " }"; 
		}
		
		return s;
	}
	
	public String toStringShort(){
		String coordinate = "";
		coordinate = "(" + this.x + "," + this.y + ")";
		
		StringBuilder s = new StringBuilder();
		s.append("Last Node " + this.nodeGroupSiblings.getSiblings()[0].exitNode().toString() + " ");
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
			md = Math.abs(this.rnodeData.getPrev().getX() - this.getX()) + Math.abs(this.rnodeData.getPrev().getY() - this.getY());
		}
		return md;
	}

	
	public boolean isInBoundingBoxLimit(Connection con) {		
		return this.x > con.net.x_min_b && this.x < con.net.x_max_b && this.y > con.net.y_min_b && this.y < con.net.y_max_b;
	}
	
	public boolean isInConBoundingBoxLimit(Connection con) {		
		return this.x > con.getX_min_b() && this.x < con.getX_max_b() && this.y > con.getY_min_b() && this.y < con.getY_max_b();
	}
	
	public NodeGroupSiblings getNodeGroupSiblings() {
		return this.nodeGroupSiblings;
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
	
	public GroupDelayType getGroupDelayType(){
		return this.nodeGroupSiblings.groupDelayType();
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
		return this.nodeGroupSiblings.getExitNode();
	}

	@Override
	public short getX() {
		return this.x;
	}

	@Override
	public short getY() {
		return this.y;
	}
	
	@Override
	public float getBase_cost() {
		return this.base_cost;
	}
	
	@Override
	public RoutableData getRoutableData() {
		return this.rnodeData;
	}
	
	@Override
	public boolean isChildrenSet() {
		return childrenSet;
	}

	@Override
	public void setChildrenSet(boolean childrenSet) {
		this.childrenSet = childrenSet;
	}

	@Override
	public List<Routable> getChildren() {
		// TODO Auto-generated method stub
		return null;
	}
}
