package com.xilinx.rapidwright.routernew;

import java.util.List;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.TimingGroup;
import com.xilinx.rapidwright.timing.TimingModel;

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
	private TimingModel timingModel;
	
	public String name;
	
	//information used to check if the RNode is in the routing bounding box of a connection
	//to finalize using the column/row of the tile?
	public short xlow, xhigh;//exact coordinate seems to be invisible
	public short ylow, yhigh;
	public float centerx, centery;
	
	public final short capacity = 1;
	
	public float delay;//TODO for timing-driven
		
	public float base_cost;
	
	public final RNodeData<E> rnodeData;
	
	public boolean target;
	public List<RNode<E>> children;//populate the child rnodes of the current 
	public boolean childrenSet;
	
	public RNode(int index, SitePinInst sitePinInst, RoutableType type, RoutingGranularityOpt opt){
		this.index = index;
		this.type = type;
		//TODO get Wire wire to replace the name as the key, no name will be needed
		if(opt == RoutingGranularityOpt.WIRE){
			this.tile = sitePinInst.getSiteInst().getTile();
			this.wire = sitePinInst.getSiteExternalWireIndex();
			this.name = this.tile.getName() + "/" + this.wire;
			this.setCenterXYWire();
		}else if(opt == RoutingGranularityOpt.NODE){
			this.node = sitePinInst.getConnectedNode();
			this.name = this.node.toString();
			this.setCenterXYNode(this.node);
		}

		this.rnodeData = new RNodeData<E>(this.index);
		this.childrenSet = false;
	}
	
	public RNode(int index, SitePinInst sitePinInst, RoutableType type, TimingModel tmodel){
		this.index = index;
		this.type = type;
		this.timingGroup =  new TimingGroup(sitePinInst, tmodel);
		if(this.timingGroup == null) System.out.println("true null");
		this.name = this.timingGroup.getLastNode().toString();
		this.setCenterXYTimingGroup(this.timingGroup);
		this.rnodeData = new RNodeData<E>(this.index);
		this.childrenSet = false;
	}
	
	public RNode(int index, Tile tile, int wire){
		this.index = index;
		this.tile = tile;
		this.wire = wire;
		this.type = RoutableType.INTERRR;
		this.name = this.tile.getName() + "/" + this.wire;
		this.rnodeData = new RNodeData<E>(this.index);
		this.childrenSet = false;
		this.setCenterXYWire();
		
	}
	public RNode(int index, Node node){
		this.index = index;
		this.type = RoutableType.INTERRR;
		this.node = node;
		this.name = this.node.toString();
		
		this.rnodeData = new RNodeData<E>(this.index);
		this.childrenSet = false;
		//different base cost for different routing resources
		this.setCenterXYNode(this.node);
	}
	
	public RNode(int index, TimingGroup timingGroup){
		this.index = index;
		this.type = RoutableType.INTERRR;
		this.timingGroup = timingGroup;
		this.name = this.timingGroup.getLastNode().toString();
		this.rnodeData = new RNodeData<E>(this.index);
		this.childrenSet = false;
		
		this.setCenterXYTimingGroup(timingGroup);
	}
	
	public void setCenterXYWire(){
		this.xlow = (short) this.tile.getColumn();
		this.xhigh = this.xlow;
		this.ylow = (short) this.tile.getRow();
		this.yhigh = this.ylow;
		
		this.centerx = (this.xhigh + this.xlow) / 2;
		this.centery = (this.yhigh + this.ylow) / 2;
	}
	
	public void setCenterXYTimingGroup(TimingGroup tg){
		int nodeSize = tg.getNodes().size();
		short[] xMaxCoordinates = new short[nodeSize];
		short[] xMinCoordinates = new short[nodeSize];
		short[] yMaxCoordinates = new short[nodeSize];
		short[] yMinCoordinates = new short[nodeSize];
		short nodeId = 0;
		for(Node node:timingGroup.getNodes()){
			this.setCenterXYNode(node);
			xMaxCoordinates[nodeId] = this.xhigh;
			xMinCoordinates[nodeId] = this.xlow;
			yMaxCoordinates[nodeId] = this.yhigh;
			yMinCoordinates[nodeId] = this.ylow;
		}
		
		this.xlow = this.min(xMinCoordinates);
		this.xhigh = this.max(xMaxCoordinates);
		this.ylow = this.min(yMinCoordinates);
		this.yhigh = this.max(yMaxCoordinates);
		
		this.centerx = (this.xhigh + this.xlow) / 2;
		this.centery = (this.yhigh + this.ylow) / 2;
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
	
	//increasing base_cost only will hurt the runtime a lot
	public void setBaseCost(float fac){
		this.setBaseCost();
		this.base_cost *= fac;//(this.xhigh - this.xlow) + (this.yhigh - this.ylow) + 1;
	}
	
	//default 1, 1, 0.95 are picked up from Vaughn's book page 77
	public void setBaseCost(){
		//base cost of different types of routing resource
		if(this.type == RoutableType.SOURCERR){
			this.base_cost = 1;
			
		}else if(this.type == RoutableType.INTERRR){
			//TODO aver cost around 4 when using deltaX + deltaY +1 
			//(most (deltaX + deltaY +1 ) values range from 1 to 90+, maximum can be 176)
			//(deltaX + deltaY +1 ) normalized to the maximum , does not work
			this.base_cost = 1;
			
		}else if(this.type == RoutableType.SINKRR){//this is for faster maze expansion convergence to the sink
			this.base_cost = 0.95f;//virtually the same to the logic block input pin, since no alternative ipins are considered
		}
	}
	
	public float getDelay(){
		return this.delay;
	}
	
	public void setDelay(float del){
		this.delay = del;
	}
	
	public boolean overUsed(){
		return this.capacity < this.rnodeData.getOccupation();
	}
	
	public boolean used(){
		return this.rnodeData.getOccupation() > 0;
	}
	
	public boolean illegal(){
		return this.capacity < this.rnodeData.numUniqueParents();
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
		
	public void updatePresentCongestionPenalty(float pres_fac) {
		RNodeData<E> data = this.rnodeData;
		
		int occ = data.numUniqueSources();
		int cap = this.capacity;
		
		if (occ < cap) {
			data.setPres_cost(1);
		} else {
			data.setPres_cost(1 + (occ - cap + 1) * pres_fac);
		}

		data.setOccupation(occ);
	}
	
	public float getManhattanD(){
		float md = 0;
		if(this.rnodeData.getPrev() != null){
			md = Math.abs(this.rnodeData.getPrev().centerx - this.centerx) + Math.abs(this.rnodeData.getPrev().centery - this.centery);
		}
		return md;
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
	public int hashCode() {
		return this.index;
	}
}
