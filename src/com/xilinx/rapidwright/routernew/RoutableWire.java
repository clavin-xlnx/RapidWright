package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;

public class RoutableWire implements Routable{
	public int index;
	public Tile tile;
	private int wire;
	private RoutableType type;
	
	public short xlow, xhigh;
	public short ylow, yhigh;
	
	public float delay;//TODO for timing-driven
		
	public float base_cost;
	
	@SuppressWarnings("rawtypes")
	public final RNodeData rnodeData;
	
	public boolean target;
	public List<Routable> children;
	public boolean childrenSet;
	
	public RoutableWire(int index, SitePinInst sitePinInst, RoutableType type){
		this.index = index;
		this.type = type;
		this.tile = sitePinInst.getTile();
		this.rnodeData = new RNodeData<>(this.index);
		this.childrenSet = false;
		this.target = false;
		this.setXY();
	}
	
	public RoutableWire(int index, Wire wire, RoutableType type){
		this.index = index;
		this.type = type;
		this.tile = wire.getTile();
		this.rnodeData = new RNodeData<>(this.index);
		this.childrenSet = false;
		this.target = false;
		this.setXY();
	}
	
	@Override
	public Tile getTle() {
		return this.tile;
	}
	
	@Override
	public List<Routable> getChildren() {
		return this.children;
	}
	
	public int setChildren(int globalIndex, float base_cost_fac, Map<Wire, Routable> createdRoutable, Set<Routable> reserved){
		this.children = new ArrayList<>();
		List<Wire> wires = this.tile.getWireConnections(this.wire);
		for(Wire wire:wires){
			if(wire.getTile().getName().startsWith("INT_")){
				if(!createdRoutable.containsKey(wire)){
					Routable child;
					child = new RoutableWire(globalIndex, wire, RoutableType.INTERRR);
					child.setBaseCost(base_cost_fac);
					globalIndex++;
					this.children.add(child);
					createdRoutable.put(wire, child);
				}else{
					this.children.add(createdRoutable.get(wire));//the sink routable a target created up-front 
				}
			}
		}
		this.childrenSet = true;
		return globalIndex;
	}
	
	@Override
	public void setBaseCost(float fac) {
		this.setBaseCost();
		this.base_cost *= fac;//(this.xhigh - this.xlow) + (this.yhigh - this.ylow) + 1;
	}
	
	//default 1, 1, 0.95 are picked up from Vaughn's book page 77
	public void setBaseCost(){
		//base cost of different types of routing resource
		if(this.type == RoutableType.SOURCERR){
			this.base_cost = 1;
			
		}else if(this.type == RoutableType.INTERRR){
			//aver cost around 4 when using deltaX + deltaY +1 
			this.base_cost = 1;
			
		}else if(this.type == RoutableType.SINKRR){//this is for faster maze expansion convergence to the sink
			this.base_cost = 0.95f;//virtually the same to the logic block input pin, since no alternative ipins are considered
		}
	}

	@Override
	public boolean overUsed() {
		return Routable.capacity < this.rnodeData.getOccupation();
	}
	
	@Override
	public boolean used(){
		return this.rnodeData.getOccupation() > 0;
	}
	
	@Override
	public boolean illegal(){
		return Routable.capacity < this.rnodeData.numUniqueParents();
	}

	@Override
	public void setXY() {
		this.xlow = (short) this.tile.getColumn();
		this.xhigh = this.xlow;
		this.ylow = (short) this.tile.getRow();
		this.yhigh = this.ylow;
	}

	@Override
	public void updatePresentCongestionPenalty(float pres_fac) {
		@SuppressWarnings("rawtypes")
		RNodeData data = this.rnodeData;
		
		int occ = data.numUniqueSources();
		int cap = Routable.capacity;
		
		if (occ < cap) {
			data.setPres_cost(1);
		} else {
			data.setPres_cost(1 + (occ - cap + 1) * pres_fac);
		}

		data.setOccupation(occ);
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
		s.append(String.format("basecost = %.2e", this.base_cost));
		s.append(", ");
		s.append(String.format("capacity = %d", Routable.capacity));
		s.append(", ");
		s.append(String.format("occupation = %d", this.rnodeData.getOccupation()));
		s.append(", ");
		s.append(String.format("num_unique_sources = %d", this.rnodeData.numUniqueSources()));
		s.append(", ");
		s.append(String.format("num_unique_parents = %d", this.rnodeData.numUniqueParents()));
		s.append(", ");
		s.append(String.format("level = %d", this.rnodeData.getLevel()));
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
			md = Math.abs(this.rnodeData.getPrev().centerx - this.getCenterX()) + Math.abs(this.rnodeData.getPrev().centery - this.getCenterY());
		}
		return md;
	}

	@Override
	public boolean isInBoundingBoxLimit(@SuppressWarnings("rawtypes") Connection con) {		
		return this.xlow < con.net.x_max_b && this.xhigh > con.net.x_min_b && this.ylow < con.net.y_max_b && this.yhigh > con.net.y_min_b;
	}

}
