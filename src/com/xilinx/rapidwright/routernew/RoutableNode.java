package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.util.Pair;

public class RoutableNode implements Routable{
	public int index;
	private Node node;
	public RoutableType type;
	
	public short x, y;
	
	public float base_cost;
	
	private final RoutableData rnodeData;
	
	private boolean target;
	public List<Routable> children;
	private boolean childrenSet;
	
	public RoutableNode(int index, Node node, RoutableType type){
		this.index = index;
		this.type = type;
		this.node = node;
		this.rnodeData = new RoutableData(this.index);
		this.childrenSet = false;
		this.target = false;
		this.setXY();
		this.setBaseCost();
	}
	
	public Pair<Integer, Long> setChildren(Connection c, int globalIndex, Map<Node, RoutableNode> createdRoutable,
			RouteThruHelper routethruHelper, long callingOfGetNextRoutable, Set<Node> reserved){
		List<Node> allDownHillNodes = this.node.getAllDownhillNodes();
		callingOfGetNextRoutable++;
		this.children = new ArrayList<>();
		for(Node node:allDownHillNodes){
			//TODO make available routethrus available
			if(reserved.contains(node)) continue;
			if(!routethruHelper.isRouteThru(this.node, node)){//routethrus are forbidden in this way
				RoutableNode child = createdRoutable.get(node);
				if(child == null) {
					child = new RoutableNode(globalIndex, node, RoutableType.INTERRR);
					globalIndex++;
					this.children.add(child);
					createdRoutable.put(node, child);
				}else {
					this.children.add(child);//the sink routable of a target has been created up-front 
				}
			}
		}
		this.childrenSet = true;
		
		return new Pair<Integer, Long>(globalIndex, callingOfGetNextRoutable);
	}
	
	public void setBaseCost(){
		if(this.type == RoutableType.SOURCERR){
			base_cost = 1;
			
		}else if(this.type == RoutableType.INTERRR){
			base_cost = 1f;
			
		}else if(this.type == RoutableType.SINKRR){//this is for faster maze expansion convergence to the sink
			base_cost = 0.95f;//virtually the same to the logic block input pin, since no alternative ipins are considered
		}else{
			base_cost = 1;
		}
	}

	@Override
	public boolean overUsed() {
		return Routable.capacity < this.getOccupancy();
	}
	
	@Override
	public boolean used(){
		return this.getOccupancy() > 0;
	}
	
	@Override
	public boolean illegal(){
		return Routable.capacity < this.rnodeData.numUniqueParents();
	}

	@Override
	public void setXY() {
		try {
			this.node.getAllWiresInNode();
		}catch (Exception e) {
			System.out.println(this.node);
		}
		Wire[] wires = this.node.getAllWiresInNode();
		List<Tile> intTiles = new ArrayList<>();
		
		for(Wire w : wires) {
			if(w.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
				intTiles.add(w.getTile());
			}
		}
		
		if(intTiles.size() > 1) {
			this.x = (short) intTiles.get(1).getColumn();
			this.y = (short) intTiles.get(1).getRow();
		}else if(intTiles.size() == 1) {
			this.x = (short) intTiles.get(0).getColumn();
			this.y = (short) intTiles.get(0).getRow();
		}else {
			this.x = (short) wires[0].getTile().getColumn();
			this.y = (short) wires[0].getTile().getRow();
		}
	}
	
	@Override
	public void updatePresentCongestionPenalty(float pres_fac) {
		
		RoutableData data = this.rnodeData;
		
		int occ = this.getOccupancy();
		int cap = Routable.capacity;
		
		if (occ < cap) {
			data.setPres_cost(1);
		} else {
			data.setPres_cost(1 + (occ - cap + 1) * pres_fac);
		}
	}
	
	@Override
	public String toString(){
		String coordinate = "";	
		coordinate = "(" + this.x + "," + this.y + ")";
		StringBuilder s = new StringBuilder();
		s.append("id = " + this.index);
		s.append(", ");
		s.append("node " + this.node.toString());
		s.append(", ");
		s.append(coordinate);
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
	
	@Override
	public Node getNode() {
		return this.node;
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
	public int getOccupancy() {
		
		return this.rnodeData.getOccupancy();
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
	public float getDelay() {
		// Auto-generated method stub
		return 0;
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

	public boolean isChildrenSet() {
		return childrenSet;
	}

	@Override
	public void setChildrenSet(boolean childrenSet) {
		this.childrenSet = childrenSet;
	}

	@Override
	public List<Routable> getChildren() {
		return this.children;
	}
	
}
