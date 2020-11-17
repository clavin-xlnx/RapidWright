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
//    private short x_min_b, x_max_b, y_min_b, y_max_b;
	
    //TODO timing-driven
    public TimingVertex sourceTimingVertex;
    public TimingVertex sinkTimingVertex;
    public TimingEdge timingEdge;//an intra site delay added
    public float criticality;
//    public float intraSiteDelay;//TODO set
    
	private Routable sourceRNode;
	private Routable sinkRNode;
	public List<Routable> rnodes;
	public List<Routable> pathFromSinkToSwitchBox;
	
	public List<Node> nodes;
	public List<ImmutableTimingGroup> timingGroups;
	
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
//		this.intraSiteDelay = -1;
	}
	
	public short calculateBoundingBox() {
		short min_x, max_x, min_y, max_y;
		
		short sourceX = (short) this.source.getTile().getColumn();
		short sinkX = (short) this.sink.getTile().getColumn();
		if(sourceX < sinkX) {
			min_x = sourceX;
			max_x = sinkX;
		} else {
			min_x = sinkX;
			max_x = sourceX;
		}
		
		short sourceY = (short) this.source.getTile().getRow();
		short sinkY = (short) this.sink.getTile().getRow();
		if(sourceY < sinkY) {
			min_y = sourceY;
			max_y = sinkY;
		} else {
			min_y = sinkY;
			max_y = sourceY;
		}
		
		return (short) ((max_x - min_x + 1) + (max_y - min_y + 1));
	}
	
	/*public float getIntraSiteDelay() {
		return intraSiteDelay;
	}

	public void setIntraSiteDelay(float intraSiteDelay) {
		this.intraSiteDelay = intraSiteDelay;
	}*/

	public void setNet(Netplus net){
		this.net = net;
	}
	
	public Netplus getNet(){
		return this.net;
	}
	
	public TimingVertex getSourceTimingVertex() {
		return sourceTimingVertex;
	}

	public void setSourceTimingVertex(TimingVertex sourceTimingVertex) {
		this.sourceTimingVertex = sourceTimingVertex;
	}

	public TimingVertex getSinkTimingVertex() {
		return sinkTimingVertex;
	}

	public void setSinkTimingVertex(TimingVertex sinkTimingVertex) {
		this.sinkTimingVertex = sinkTimingVertex;
	}

	public TimingEdge getTimingEdge() {
		return timingEdge;
	}
	
	public void calculateCriticality(float maxDelay, float maxCriticality, float criticalityExponent){
		float slackCon = this.sinkTimingVertex.getRequiredTime() - this.sourceTimingVertex.getArrivalTime() - this.timingEdge.getDelay();
		// - this.timingEdge.getDelay();
//		float slack = this.sinkTimingVertex.getSlack();//slaskCon = slack
//		if(slackCon != slack) System.out.println("slack con = " + slackCon + ", sink TV slack = " + slack + ", " + this.timingEdge.getDelay());
		float tempCriticality  = (1 - slackCon / maxDelay);//TODO check up on criticality values
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
	
	//TODO debug - bugs due to the unmatched SitePinInst - TimingVertex
	public void setTimingEdge(TimingGraph timingGraph, EDIFNet edifNet, Net n) {
		this.timingEdge = new TimingEdge(timingGraph, this.sourceTimingVertex, this.sinkTimingVertex, edifNet, n);
	}
	
	public void setTimingEdge(TimingEdge e){
		this.timingEdge = e;
	}

	public void printInfo(String s){
		System.out.println(s);
	}
	
	public void resetConnection(){
		this.rnodes.clear();
		this.timingGroups.clear();
	}
	
	public void addPartialPath(Routable routable){
		if(this.pathFromSinkToSwitchBox == null){
			this.pathFromSinkToSwitchBox = new ArrayList<>();
			this.pathFromSinkToSwitchBox.add(routable);
		}else{
			this.pathFromSinkToSwitchBox.add(routable);
		}
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
		s.append(this.timingEdge.toString() + ", " + this.timingEdge.delaysInfo());
		
		return s.toString();
	}
	
	public String toString() {
		
		StringBuilder s = new StringBuilder();
			
//		String coordinate = "(" + this.source.getTile().getColumn() + "," + this.source.getTile().getRow() + ") to (" 
//							+ this.sink.getTile().getColumn() + "," + this.sink.getTile().getRow() + ")";
		String coordinate = "(" + this.net.x_min_b + ", " + this.net.y_min_b + ") to (" 
				+ this.net.x_max_b + ", " + this.net.y_max_b + ")";
		
		s.append("Con ");
		s.append(String.format("%6s", this.id));
		s.append(", ");
		s.append(String.format("%22s", coordinate));
		s.append(", ");
		s.append("net = " + this.net.getNet().getName());
		s.append(", ");
		s.append(String.format("net fanout = %3s", this.net.fanout));
		s.append(", ");
		s.append(String.format("source = %26s", this.source.getName() + " -> " + this.source.getConnectedNode().toString()));
		s.append(", ");
		s.append("sink = " + this.sink.getConnectedNode().toString() + " -> " +  this.sink.getName());
		s.append(", ");
		s.append(String.format("Manhattan d = %4d ", this.getManhattanDistance()));
		
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
		float routeDelay = 0;
		for(ImmutableTimingGroup tg : this.timingGroups){//TODO per siblings
			routeDelay += tg.getDelay();
		}
		this.timingEdge.setRouteDelay(routeDelay);
	}
	
	public void setTimingEdgeRouteDelay(float routeDelay){
		this.timingEdge.setRouteDelay(routeDelay);
	}
	
	public float getRouteDelay() {
		float routeDelay = 0;
		for(ImmutableTimingGroup tg : this.timingGroups){//TODO per siblings
			routeDelay += tg.getDelay();
		}
		return routeDelay;
	}
}
