package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;

public class RNode<E>{
//	private int index;
	
	public RoutableType type;
	private Tile tile;
	private int wire;
	public String name;
	
	//TODO information used to check if the RNode is in the routing bounding box of a connection
	//to finalize using the column/row of the tile?
	public short xlow, xhigh;//exact coordinate seems to be invisible
	public short ylow, yhigh;
	public float centerx, centery;
	
	public final short capacity;
	
	public float delay;//TODO for timing-driven
		
	public float base_cost;
	
	public final RNodeData<E> rNodeData;
	
	public boolean target;
	public List<RNode<E>> children;//populate the child rnodes of the current 
	public boolean childrenSet;
	
	public RNode(SitePinInst sitePinInst, RoutableType type, int capacity){
//		this.index = index;
		this.tile = sitePinInst.getSiteInst().getTile();
		this.type = type;		
		this.wire = sitePinInst.getSiteExternalWireIndex();
		this.name = this.tile.getName() + "/" + this.wire;
		this.capacity = (short)capacity;
		this.rNodeData = new RNodeData<E>();
		this.childrenSet = false;
		//different base cost for different routing resources
		this.setBaseCost();
		this.setCenterXY();
	}
	
	public RNode(Tile tile, int wire, int capacity){
//		this.index = index;
		this.tile = tile;
		this.wire = wire;
		this.capacity = (short)capacity;
		this.type = RoutableType.INTERWIRE;
		this.name = this.tile.getName() + "/" + this.wire;
		this.rNodeData = new RNodeData<E>();
		this.childrenSet = false;
		//different base cost for different routing resources
		this.setBaseCost();
		this.setCenterXY();
		
	}
	
	/*public RNode(short capacity, TimingModel timingModel){
		this.type = RNodeType.WIRE;
		this.rNodeData = new RNodeData<E>(this.index);
		this.capacity = capacity;
		this.setBaseCost();
	}*/
	
	public void setCenterXY(){
		this.xlow = (short) this.tile.getColumn();
		this.xhigh = this.xlow;
		this.ylow = (short) this.tile.getRow();
		this.yhigh = this.ylow;
		this.centerx = (this.xhigh + this.xlow) / 2;
		this.centery = (this.yhigh + this.ylow) / 2;
	}
	
	public void setBaseCost(){
		if(this.type == RoutableType.SOURCEPINWIRE || this.type == RoutableType.INTERWIRE){
			this.base_cost = 1;
		}else if(this.type == RoutableType.SINKPINWIRE){
			this.base_cost = 0.95f;
		}
	}
	
	public float getDelay(){
		return this.delay;
	}
	
	public void setDelay(float del){
		this.delay = del;
	}
	
	public boolean overUsed(){
		return this.capacity < this.rNodeData.getOccupation();
	}
	
	public boolean used(){
		return this.rNodeData.getOccupation() > 0;
	}
	
	public boolean illegal(){
		return this.capacity < this.rNodeData.numUniqueParents();
	}
	
	/**
	 * Gets all the possible connections leaving this node
	 * @return The list of all possible connections leaving this node 
	 */
	public List<RNode<Wire>> getChildRNodeWireGrained(){
		List<Wire> wires = this.tile.getWireConnections(this.wire);
		List<RNode<Wire>> childRNodes = new ArrayList<>();
		
		for(Wire wire:wires){		
			childRNodes.add(new RNode<Wire>(wire.getTile(), wire.getWireIndex(), 1));
		}
		return childRNodes;
	}
	
	public List<RNode<Node>> getChildRNodeNodeGranied(){
		//TODO
		return null;
		
	}
	
	public Tile getTile() {
		return tile;
	}

	public void setTile(Tile tile) {
		this.tile = tile;
	}
	
	public List<RNode<E>> getChildren() {
		return children;
	}

	public void setChildren(List<RNode<E>> children) {
		this.children = children;
		this.childrenSet = true;
	}

	public int getWire() {
		return wire;
	}

	public void setWire(int wire) {
		this.wire = wire;
	}

	public boolean isTarget() {
		return this.target;
	}
	
	public void setTarget(boolean target) {
		this.target = target;
	}
	
	public void updatePresentCongestionPenalty(float pres_fac) {
		RNodeData<E> data = this.rNodeData;
		
		int occ = data.numUniqueSources();
		int cap = this.capacity;
		
		if (occ < cap) {
			data.setPres_cost(1);
		} else {
			data.setPres_cost(1 + (occ - cap + 1) * pres_fac);
		}

		data.setOccupation(occ);
	}
	
	public String toString() {	
		String coordinate = "";
		if(this.xlow == this.xhigh && this.ylow == this.yhigh) {
			coordinate = "(" + this.xlow + "," + this.ylow + ")";
		} else {
			coordinate = "(" + this.xlow + "," + this.ylow + ") to (" + this.xhigh + "," + this.yhigh + ")";
		}
		
		StringBuilder s = new StringBuilder();
		s.append("RNode " + this.name + " ");
		s.append(String.format("%-11s", coordinate));
		s.append(String.format("basecost = %.2e", this.base_cost));
		s.append(", ");
		s.append(String.format("capacity = %d", this.capacity));
//		s.append(", ");
//		s.append(String.format("children = %d", this.children.size()));
		s.append(", ");
		s.append(String.format("occupation = %d", this.rNodeData.getOccupation()));
		s.append(", ");
		s.append(String.format("num_unique_sources = %d", this.rNodeData.numUniqueSources()));
		s.append(", ");
		s.append(String.format("num_unique_parents = %d", this.rNodeData.numUniqueParents()));
		s.append(", ");
		s.append(String.format("level = %d", this.rNodeData.getLevel()));
		s.append(", ");
		s.append(String.format("type = %s", this.type));
		
		return s.toString();
	}
	
}
