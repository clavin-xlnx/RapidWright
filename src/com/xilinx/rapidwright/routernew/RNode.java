package com.xilinx.rapidwright.routernew;

import java.util.List;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.TimingGroup;

public class RNode<E>{
	public int index;	
	public RoutableType type;
	
	//RNode<Wire>
	private Tile tile;
	private int wire;
	
	//RNode<Node>
	private Node node;
	
	//RNode<TimingGroup>
	private TimingGroup timingGroup;
	
	public String name;
	
	//information used to check if the RNode is in the routing bounding box of a connection
	//to finalize using the column/row of the tile?
	public short xlow, xhigh;//exact coordinate seems to be invisible
	public short ylow, yhigh;
	public float centerx, centery;
	
	public final short capacity = 1;
	
	public float delay;//TODO for timing-driven
		
	public float base_cost;
	
	public final RNodeData<E> rNodeData;
	
	public boolean target;
	public List<RNode<E>> children;//populate the child rnodes of the current 
	public boolean childrenSet;
	
	public RNode(int index, SitePinInst sitePinInst, RoutableType type, RoutingGranularityOpt opt){
		this.index = index;
		this.type = type;
				
		if(opt == RoutingGranularityOpt.WIRE){
			this.tile = sitePinInst.getSiteInst().getTile();
			this.wire = sitePinInst.getSiteExternalWireIndex();
			this.name = this.tile.getName() + "/" + this.wire;
			this.setCenterXYWire();
		}else if(opt == RoutingGranularityOpt.NODE){
			this.node = sitePinInst.getConnectedNode();
			this.name = this.node.toString();
			this.setCenterXYNode();
		}

		this.rNodeData = new RNodeData<E>(this.index);
		this.childrenSet = false;
	}
	
	public RNode(int index, Tile tile, int wire){
		this.index = index;
		this.tile = tile;
		this.wire = wire;
		this.type = RoutableType.INTERRNODE;
		this.name = this.tile.getName() + "/" + this.wire;
		this.rNodeData = new RNodeData<E>(this.index);
		this.childrenSet = false;
		//different base cost for different routing resources
		this.setCenterXYWire();
		
	}
	public RNode(int index, Node node){
		this.index = index;
		this.type = RoutableType.INTERRNODE;
		this.node = node;
		this.name = this.node.toString();
		
		this.rNodeData = new RNodeData<E>(this.index);
		this.childrenSet = false;
		//different base cost for different routing resources
		this.setCenterXYNode();
	}
	
	public RNode(int index, TimingGroup timingGroup){//, TimingModel timingModel){
		this.index = index;
		this.type = RoutableType.INTERRNODE;
		this.timingGroup = timingGroup;
		this.rNodeData = new RNodeData<E>(this.index);
		this.childrenSet = false;
		
		//TODO set centerXY
	}
	
	public void setCenterXYWire(){
		this.xlow = (short) this.tile.getColumn();
		this.xhigh = this.xlow;
		this.ylow = (short) this.tile.getRow();
		this.yhigh = this.ylow;
		
		this.centerx = (this.xhigh + this.xlow) / 2;
		this.centery = (this.yhigh + this.ylow) / 2;
	}
	
	public void setCenterXYNode(){
		int length = this.node.getAllWiresInNode().length;
		short[] xCoordinates = new short[length];
		short[] yCoordinates = new short[length];
		short id = 0;
		for(Wire w : this.node.getAllWiresInNode()){
			xCoordinates[id] = (short) w.getTile().getColumn();
			yCoordinates[id] = (short) w.getTile().getRow();
			id++;
		}
		/*if(length > 0){
			this.xlow = this.min(xCoordinates);
			this.xhigh = this.max(xCoordinates);
			this.ylow = this.min(yCoordinates);
			this.yhigh = this.max(yCoordinates);
		}*/
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
		
		this.centerx = (this.xhigh + this.xlow) / 2;
		this.centery = (this.yhigh + this.ylow) / 2;
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
	
	public void setBaseCostNode(){
		this.setBaseCost();
		this.base_cost *= 3;
	}
	
	//TODO tune fac to check if #averWire works best
	public void setBaseCost(float fac){
		this.setBaseCost();
		this.base_cost *= fac;
	}
	
	public void setBaseCost(){
		if(this.type == RoutableType.SOURCERNODE || this.type == RoutableType.INTERRNODE){
			this.base_cost = 1;
		}else if(this.type == RoutableType.SINKRNODE){//this is for faster convergence to the sink, but not used currently
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
	
	public Node getNode(){
		return this.node;
	}
	
	public TimingGroup getTimingGroup(){
		return this.timingGroup;
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
	
	@Override
	public int hashCode() {
		return this.index;
	}
}
