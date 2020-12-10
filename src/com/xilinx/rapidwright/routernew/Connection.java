package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.timing.ImmutableTimingGroup;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingVertex;

public class Connection{
	public final int id;
	
	public final SitePinInst source;
	public final SitePinInst sink;
	
	public Netplus net;
    public final short boundingBox;
    private short x_min_b, x_max_b, y_min_b, y_max_b;
	
    public List<TimingEdge> timingEdges;//FOR LUT_6_2_* SITEPININSTS
    public float criticality;
    
	private Routable sourceRNode;
	private Routable sinkRNode;
	public List<Routable> rnodes;
//	public List<Routable> pathFromSinkToSwitchBox;
	
	public List<Node> nodes;
	public List<ImmutableTimingGroup> timingGroups;//TODO could be removed when not needed for debugging
	
	public void newNodes(){
		this.nodes = new ArrayList<>();
	}
	
	public void addNode(Node node){
		this.nodes.add(node);
	}
	
	public Connection(int id, SitePinInst source, SitePinInst sink){
		this.id = id;
		
		this.source = source;
		this.sink = sink;
		this.criticality = 0.0f;
		
		this.boundingBox = this.calculateBoundingBox();
		
		this.rnodes = new ArrayList<>();
		this.timingGroups = new ArrayList<>();
	}
	
	public short calculateBoundingBox() {
		short x_min, x_max, y_min, y_max;
		
		short sourceX = (short) this.source.getTile().getColumn();
		short sinkX = (short) this.sink.getTile().getColumn();
		if(sourceX < sinkX) {
			x_min = sourceX;
			x_max = sinkX;
		} else {
			x_min = sinkX;
			x_max = sourceX;
		}
		
		short sourceY = (short) this.source.getTile().getRow();
		short sinkY = (short) this.sink.getTile().getRow();
		if(sourceY < sinkY) {
			y_min = sourceY;
			y_max = sinkY;
		} else {
			y_min = sinkY;
			y_max = sourceY;
		}
		
		return (short) ((x_max - x_min + 1) + (y_max - y_min + 1));
	}
	
	public void calculateGeoConBoundingBox(short bbRange) {
		short x_min, x_max, y_min, y_max;
		short x_geo = (short) Math.ceil(this.net.x_geo);
		short y_geo = (short) Math.ceil(this.net.y_geo);
		x_max = this.maxOfThree(this.sourceRNode.getXmax(), this.sinkRNode.getXmax(), x_geo);
		x_min = this.minOfThree(this.sourceRNode.getXmin(), this.sinkRNode.getXmin(), x_geo);
		y_max = this.maxOfThree(this.sourceRNode.getYmax(), this.sinkRNode.getYmax(), y_geo);
		y_min = this.minOfThree(this.sourceRNode.getYmin(), this.sinkRNode.getYmin(), y_geo);
		this.x_max_b = (short) (x_max + bbRange);
		this.x_min_b = (short) (x_min - bbRange);
		this.y_max_b = (short) (y_max + bbRange);
		this.y_min_b = (short) (y_min - bbRange);
	}
	
	public short maxOfThree(short var1, short var2, short var3) {
		if(var1 >= var2 && var1 >= var3) {
			return var1;
		}else if(var2 >= var1 && var2 >= var3) {
			return var2;
		}else {
			return var3;
		}
	}
	
	public short minOfThree(short var1, short var2, short var3) {
		if(var1 <= var2 && var1 <= var3) {
			return var1;
		}else if(var2 <= var1 && var2 <= var3) {
			return var2;
		}else {
			return var3;
		}
	}

	public short getX_min_b() {
		return x_min_b;
	}

	public void setX_min_b(short x_min_b) {
		this.x_min_b = x_min_b;
	}

	public short getX_max_b() {
		return x_max_b;
	}

	public void setX_max_b(short x_max_b) {
		this.x_max_b = x_max_b;
	}

	public short getY_min_b() {
		return y_min_b;
	}

	public void setY_min_b(short y_min_b) {
		this.y_min_b = y_min_b;
	}

	public short getY_max_b() {
		return y_max_b;
	}

	public void setY_max_b(short y_max_b) {
		this.y_max_b = y_max_b;
	}

	public void setNet(Netplus net){
		this.net = net;
	}
	
	public Netplus getNet(){
		return this.net;
	}

	public List<TimingEdge> getTimingEdge() {
		return timingEdges;
	}
	
	public void calculateCriticality(float maxDelay, float maxCriticality, float criticalityExponent){
		TimingVertex source = this.timingEdges.get(0).getSrc();
		float slackCon = source.getRequiredTime() - source.getArrivalTime();
		float tempCriticality  = (1 - slackCon / maxDelay);
    	tempCriticality = (float) (Math.pow(tempCriticality, criticalityExponent) * maxCriticality);
    	
    	if(tempCriticality > this.criticality) 
    		this.setCriticality(tempCriticality);
	}
	
	public void setCriticality(float criticality){
		this.criticality = criticality;
	}
	
	public void resetCriticality(){
		this.criticality = 0;
	}
	
	public float getCriticality(){
		return this.criticality;
	}
	
	public void setTimingEdge(List<TimingEdge> e){
		this.timingEdges = new ArrayList<>();
		timingEdges = e;
	}

	public void printInfo(String s){
		System.out.println(s);
	}
	
	public void resetConnection(){
		this.rnodes.clear();
		this.timingGroups.clear();
	}
	
	public Routable getSourceRNode() {
		return sourceRNode;
	}

	public void setSourceRNode(Routable sourceNode) {
		this.sourceRNode = sourceNode;
	}

	public Routable getSinkRNode() {
		return sinkRNode;
	}

	public void setSinkRNode(Routable childRNode) {
		this.sinkRNode = childRNode;
	}
	
	public String toStringTiming(){
		StringBuilder s = new StringBuilder();
		s.append("Con");
		s.append(String.format("%6s", this.id));
		s.append(", ");
		s.append("net = " + this.net.getNet().getName());
		s.append(", ");
		s.append(String.format("net fanout = %3s", this.net.fanout));
		s.append(", TimingEdge = ");
		s.append(this.timingEdges.get(0).toString() + ", " + this.timingEdges.get(0).delaysInfo());
		
		return s.toString();
	}
	
	public String toString() {
		
		StringBuilder s = new StringBuilder();
		s.append("Con ");
		s.append(String.format("%6s", this.id));
		s.append(", ");
		s.append("bb = " + this.boundingBox);
		s.append(", ");
		s.append("net = " + this.net.getNet().getName());
		s.append(", ");
		s.append(String.format("net fanout = %3s", this.net.fanout));
//		s.append(", ");
//		s.append(String.format("source = %26s", this.source.getName() + " -> " + this.source.getConnectedNode().toString()));
//		s.append(", ");
//		s.append("sink = " + this.sink.getConnectedNode().toString() + " -> " +  this.sink.getName());
		s.append(", ");
		s.append("sourceReq = " + this.timingEdges.get(0).getSrc().getRequiredTime());
		s.append(", ");
		s.append("sourceArr = " + this.timingEdges.get(0).getSrc().getArrivalTime());
		s.append(", ");
		s.append("sinkReq = " + this.timingEdges.get(0).getDst().getRequiredTime());
		s.append(", ");
		s.append("sinkArr = " + this.timingEdges.get(0).getDst().getArrivalTime());
		s.append(", ");
		s.append(String.format("criticality = %4.3f ", this.getCriticality()));
		
		return s.toString();
		
	}
	
	public String toStringWire() {
		
		StringBuilder s = new StringBuilder();
			
		String coordinate = "(" + this.source.getTile().getColumn() + "," + this.source.getTile().getRow() + ") to (" 
							+ this.sink.getTile().getColumn() + "," + this.sink.getTile().getRow() + ")";
		
		s.append("Con");
		s.append(String.format("%6s", this.id));
		s.append(", ");
		s.append(String.format("%22s", coordinate));
		s.append(", ");
		s.append(String.format("net = %12s", this.net.getNet().getName()));
		s.append(", ");
		s.append(String.format("net fanout = %3s", this.net.fanout));
		s.append(", ");
		s.append(String.format("source = %26s", ((RoutableWire)this.sourceRNode).wire.toString()));
		s.append(", ");
		s.append(String.format("sink = %26s", ((RoutableWire)this.sinkRNode).wire.toString()));
		s.append(", ");
		s.append(String.format("mahattan d = %4d ", this.getManhattanDistance()));
		
		return s.toString();
		
	}
	
	public String toStringTG() {
		
		StringBuilder s = new StringBuilder();
			
		String coordinate = "(" + this.source.getTile().getColumn() + "," + this.source.getTile().getRow() + ") to (" 
							+ this.sink.getTile().getColumn() + "," + this.sink.getTile().getRow() + ")";
		
		s.append("Con");
		s.append(String.format("%6s", this.id));
		s.append(", ");
		s.append(String.format("%22s", coordinate));
		s.append(", ");
		s.append(String.format("net = %s", this.net.getNet().getName()));
		s.append(", ");
		s.append(String.format("net fanout = %3s", this.net.fanout));
		s.append(", ");
		s.append(String.format("source = %26s", this.source.getName() + " -> " + ((RoutableTimingGroup)this.sourceRNode).toStringShort()));
		s.append(", ");
		s.append(String.format("sink = %26s", ((RoutableTimingGroup) this.sinkRNode).toStringShort() + " -> " +  this.sink.getName()));
		s.append(", ");
		s.append(String.format("mahattan d = %4d ", this.getManhattanDistance()));
		
		return s.toString();
		
	}
	
	public int getManhattanDistance() {
		int dx = Math.abs(this.source.getTile().getColumn() - this.sink.getTile().getColumn());
		int dy = Math.abs(this.source.getTile().getRow() - this.sink.getTile().getRow());
		int manhattanDistance = dx + dy;
		
		return manhattanDistance;
	}
	
	public boolean congested() {
		for(Routable rn : this.rnodes){
			if(rn.overUsed()) {
				return true;
			}
		}
		return false;
	}
	
	public boolean illegal() {
		for(Routable rn : this.rnodes){
			if(rn.illegal()) {
				return true;
			}
		}
		return false;
	}
	
	public int hashCode(){
		return this.id;
	}
	
	public void addRNode(Routable rn) {
		this.rnodes.add(rn);	
	}
	
	public void addTimingGroup(ImmutableTimingGroup timingGroup){
		this.timingGroups.add(timingGroup);
	}
	
	public void updateRouteDelay(){
		float routeDelay = this.getRouteDelay();
		
		this.setTimingEdgeDelay(routeDelay);
	}
	
	public void setTimingEdgeDelay(float routeDelay){
		for(TimingEdge e : this.timingEdges){
			e.setRouteDelay(routeDelay);
		}
	}
	
	public float getRouteDelay() {
		float routeDelay = 0;
		for(Routable tg : this.rnodes){
			routeDelay += tg.getDelay();
		}
		return routeDelay;
	}
}
