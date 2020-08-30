package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.ImmutableTimingGroup;
import com.xilinx.rapidwright.timing.SiblingsTimingGroup;
import com.xilinx.rapidwright.timing.TimingModel;

public class RoutableTimingGroup implements Routable{
	public int index;
	private SiblingsTimingGroup sibTimingGroups;
	public RoutableType type;
	
	public short xlow, xhigh;
	public short ylow, yhigh;
	
	public float base_cost;
	
	public final RoutableData rnodeData;
	
	public boolean target;
	public List<RoutableTimingGroup> children;
	public boolean childrenSet;
	
	public boolean debug = false;
	
	public RoutableTimingGroup(int index, SitePinInst sitePinInst, RoutableType type, TimingModel tmodel){
		this.index = index;
		this.type = type;
		
		this.sibTimingGroups = new SiblingsTimingGroup(sitePinInst);
		
		this.rnodeData = new RoutableData(this.index);
		this.target = false;
		this.childrenSet = false;
		this.setXY();
	}
	
	public RoutableTimingGroup(int index, SiblingsTimingGroup sTimingGroups){
		this.index = index;
		this.type = RoutableType.INTERRR;
		this.sibTimingGroups = sTimingGroups;
		this.rnodeData = new RoutableData(this.index);
		this.target= false;
		this.childrenSet = false;	
		this.setXY();
	}
	
	public int setChildren(int globalIndex, float base_cost_fac, Map<Node, RoutableTimingGroup> createdRoutable, Set<Node> reservedNodes){
		if(debug) this.downHillNodesodTheLastNode();
		
		this.children = new ArrayList<>();
		if(debug) System.out.println("set children");
		//TODO getNextSiblingTimingGroups does not return right list of siblingsTimingGroups
		for(SiblingsTimingGroup stGroups:this.sibTimingGroups.getNextSiblingTimingGroups(reservedNodes)){
			RoutableTimingGroup childRNode;
			//the last node of timing group siblings is unique, used as the key
			
			Node key = stGroups.getSiblings()[0].exitNode();//TODO Yun - why this is necessary and using SiblingsTimingGroup hash code does not work
			
			if(debug) System.out.println(stGroups.getSiblings()[0].exitNode().toString());
			if(!createdRoutable.containsKey(key)){
				childRNode = new RoutableTimingGroup(globalIndex, stGroups);
				childRNode.setBaseCost(base_cost_fac);
				globalIndex++;
				children.add(childRNode);
				createdRoutable.put(key, childRNode);
			}else{
				children.add(createdRoutable.get(key));
			}
		}
		if(debug){
			System.out.println("children size = " + this.children.size());
			for(RoutableTimingGroup rtg:this.children){
				System.out.println(rtg.toString());
			}
			System.out.println();
		}
		this.childrenSet = true;
		return globalIndex;
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
		s.append(", last node ");
		s.append(this.sibTimingGroups.getSiblings()[0].exitNode().toString());
		s.append(", ");
		s.append(String.format("type = %s", this.type));
		
		return s.toString();
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
		s.append(",");
		s.append(this.sibTimingGroups.getSiblings()[0].exitNode().toString());
		s.append(", ");
		s.append(String.format("type = %s", this.type));
		
		return s.toString();
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
	
}
