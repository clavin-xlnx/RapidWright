package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.TimingGroup;
import com.xilinx.rapidwright.timing.TimingModel;

public class RoutableTimingGroup implements Routable{
	public int index;
	private TimingGroup timingGroup;
	public RoutableType type;
	
	public short xlow, xhigh;
	public short ylow, yhigh;
		
	public float base_cost;
	
	public final RoutableData rnodeData;
	
	public boolean target;
	public List<RoutableTimingGroup> children;
	public boolean childrenSet;
	
	public RoutableTimingGroup(int index, SitePinInst sitePinInst, RoutableType type, TimingModel tmodel){
		this.index = index;
		this.type = type;
		this.timingGroup =  new TimingGroup(sitePinInst, tmodel);
		if(this.timingGroup == null) System.out.println("null timing group");
		this.rnodeData = new RoutableData(this.index);
		this.target = false;
		this.childrenSet = false;
		this.setXY();
	}
	
	public RoutableTimingGroup(int index, TimingGroup timingGroup){
		this.index = index;
		this.type = RoutableType.INTERRR;
		this.timingGroup = timingGroup;
		this.rnodeData = new RoutableData(this.index);
		this.target= false;
		this.childrenSet = false;	
		this.setXY();
	}
	
	public int setChildren(int globalIndex, float base_cost_fac, Map<TimingGroup, RoutableTimingGroup> createdRoutable){
		this.children = new ArrayList<>();

		if(this.timingGroup.getNextTimingGroups() != null){
			for(TimingGroup timingGroup:this.timingGroup.getNextTimingGroups()){
				RoutableTimingGroup childRNode;
				//TimingGroup toString/hashCode is not unique,
				if(!createdRoutable.containsKey(timingGroup)){
					childRNode = new RoutableTimingGroup(globalIndex, timingGroup);
					childRNode.setBaseCost(base_cost_fac);
					globalIndex++;
					children.add(childRNode);
					createdRoutable.put(timingGroup, childRNode);
				}else{
					children.add(createdRoutable.get(timingGroup));
				}
			}
		}else{
			System.out.println("next timing group is null");
		}
		this.childrenSet = true;
		return globalIndex;
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
		int nodeSize = this.timingGroup.getNodes().size();
		short[] xMaxCoordinates = new short[nodeSize];
		short[] xMinCoordinates = new short[nodeSize];
		short[] yMaxCoordinates = new short[nodeSize];
		short[] yMinCoordinates = new short[nodeSize];
		short nodeId = 0;
		for(Node node:this.timingGroup.getNodes()){
			this.setCenterXYNode(node);
			xMaxCoordinates[nodeId] = this.xhigh;
			xMinCoordinates[nodeId] = this.xlow;
			yMaxCoordinates[nodeId] = this.yhigh;
			yMinCoordinates[nodeId] = this.ylow;
			nodeId++;
		}
		
		this.xlow = this.min(xMinCoordinates);
		this.xhigh = this.max(xMaxCoordinates);
		this.ylow = this.min(yMinCoordinates);
		this.yhigh = this.max(yMaxCoordinates);
	}
	
	public void setCenterXYNode(Node node){
		int length = node.getAllWiresInNode().length;
		short[] xCoordinates = new short[length];
		short[] yCoordinates = new short[length];
		short id = 0;
		for(Wire w : node.getAllWiresInNode()){
			xCoordinates[id] = (short) w.getTile().getColumn();
			yCoordinates[id] = (short) w.getTile().getRow();
			id++;
		}
		
		if(length == 1){
			this.xlow = xCoordinates[0];
			this.xhigh = this.xlow;
			this.ylow = yCoordinates[0];
			this.yhigh = this.ylow;
		}else if(length == 2){
			if(xCoordinates[0] < xCoordinates[1]){
				this.xlow = xCoordinates[0];
				this.xhigh = xCoordinates[1];
			}else{
				this.xlow = xCoordinates[1];
				this.xhigh = xCoordinates[0];
			}
			if(yCoordinates[0] < yCoordinates[1]){
				this.ylow = yCoordinates[0];
				this.yhigh = yCoordinates[1];
			}else{
				this.ylow = yCoordinates[1];
				this.yhigh = yCoordinates[0];
			}
		}else{
			this.xlow = this.min(xCoordinates);
			this.xhigh = this.max(xCoordinates);
			this.ylow = this.min(yCoordinates);
			this.yhigh = this.max(yCoordinates);
		}
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
			md = Math.abs(this.rnodeData.getPrev().getCenterX() - this.getCenterX()) + Math.abs(this.rnodeData.getPrev().getCenterY() - this.getCenterY());
		}
		return md;
	}

	
	public boolean isInBoundingBoxLimit(Connection con) {		
		return this.xlow < con.net.x_max_b && this.xhigh > con.net.x_min_b && this.ylow < con.net.y_max_b && this.yhigh > con.net.y_min_b;
	}

	public TimingGroup getTimingGroup() {
		return this.timingGroup;
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
