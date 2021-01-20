package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;

public class RoutableWire implements Routable{
	public int index;
	public Wire wire;
	public RoutableType type;
	
	public short x, y;
		
	public float base_cost;
	
	public final RoutableData rnodeData;
	
	public boolean target;
	public List<Routable> children;
	public boolean childrenSet;
	
	public RoutableWire(int index, SitePinInst sitePinInst, RoutableType type){
		this.index = index;
		this.wire = new Wire(sitePinInst.getTile(), sitePinInst.getConnectedWireIndex());		
		this.type = type;
		this.rnodeData = new RoutableData(this.index);
		this.childrenSet = false;
		this.target = false;
		this.setXY();
		this.setBaseCost();
	}
	
	public RoutableWire(int index, Wire wire, RoutableType type){
		this.index = index;
		this.type = type;
		this.wire = wire;
		this.rnodeData = new RoutableData(this.index);
		this.childrenSet = false;
		this.target = false;
		this.setXY();
		this.setBaseCost();
	}
	
	public int setChildren(int globalIndex, Map<Wire, RoutableWire> createdRoutable, Set<Wire> reserved){
		this.children = new ArrayList<>();
		List<Wire> wires = this.wire.getTile().getWireConnections(this.wire.getWireIndex());
		for(Wire wire:wires){
			if(reserved.contains(wire)) continue;
			if(!createdRoutable.containsKey(wire)){
				RoutableWire child;
				child = new RoutableWire(globalIndex, wire, RoutableType.INTERRR);
				globalIndex++;
				this.children.add(child);
				createdRoutable.put(wire, child);
			}else{
				this.children.add(createdRoutable.get(wire));//the sink routable a target created up-front 
			}
		}
		this.childrenSet = true;
		return globalIndex;
	}
	
	public void setBaseCost(){
		if(this.type == RoutableType.SOURCERR){
			base_cost = 1;
			
		}else if(this.type == RoutableType.INTERRR){
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

	@Override
	public void setXY() {
		this.x = (short) this.wire.getTile().getColumn();	
		this.y = (short) this.wire.getTile().getRow();
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
	
	@Override
	public String toString(){
		String coordinate = "";
		coordinate = "(" + this.x + "," + this.y + ")";
		
		StringBuilder s = new StringBuilder();
		s.append("RNode " + this.index + " ");
		s.append(String.format("%-11s", coordinate));
		s.append(String.format("basecost = %.2e", this.base_cost));
		s.append(", ");
		s.append(String.format("capacity = %d", Routable.capacity));
		s.append(", ");
		s.append(String.format("occupation = %d", this.rnodeData.getOccupancy()));
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
			md = Math.abs(this.rnodeData.getPrev().getX() - this.getX()) + Math.abs(this.rnodeData.getPrev().getY() - this.getY());
		}
		return md;
	}
	
	public boolean isInBoundingBoxLimit(Connection con) {		
		return this.x < con.net.x_max_b && this.x > con.net.x_min_b && this.y < con.net.y_max_b && this.y > con.net.y_min_b;
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
	public Node getNode() {
		// TODO Auto-generated method stub
		return this.wire.getNode();
	}

	@Override
	public float getDelay() {
		// TODO Auto-generated method stub
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
		return this.children;
	}

	@Override
	public void setDelay(short delay) {
		// TODO Auto-generated method stub
		
	}
}
