package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingVertex;

public class Connection{
	public final int id;
	
	public final SitePinInst source;
	public final SitePinInst sink;
	
	public Netplus net;
    public final short boundingBox;
	
    //TODO timing-driven
    public TimingVertex sourceTimingVertex;
    public TimingVertex sinkTimingVertex;
    public TimingEdge timingEdge;
    public float criticality;
    
	private Routable sourceRNode;
	private Routable sinkRNode;
	public List<Routable> rnodes;
	public List<Routable> pathFromSinkToSwitchBox;
	
	public Connection(int id, SitePinInst source, SitePinInst sink){
		this.id = id;
		
		this.source = source;
		this.sink = sink;
		this.criticality = 0.99f;
		
		this.boundingBox = this.calculateBoundingBox();
		
		this.rnodes = new ArrayList<>();
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
	
	//TODO debug
	public void setTimingEdge(TimingGraph timingGraph, EDIFNet edifNet, Net n) {
		this.timingEdge = new TimingEdge(timingGraph, this.sourceTimingVertex, this.sinkTimingVertex, edifNet, n);
	}

	public void printInfo(String s){
		System.out.println(s);
	}
	
	public void resetConnection(){
		this.rnodes.clear();
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
	
	public String toString() {
		
		StringBuilder s = new StringBuilder();
			
		String coordinate = "(" + this.source.getTile().getColumn() + "," + this.source.getTile().getRow() + ") to (" 
							+ this.sink.getTile().getColumn() + "," + this.sink.getTile().getRow() + ")";
		
		s.append("Con");
		s.append(String.format("%6s", this.id));
		s.append(", ");
		s.append(String.format("%22s", coordinate));
		s.append(", ");
		s.append(String.format("net = %5s", this.net.getId()));
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

	public float getCriticality() {
		return this.criticality;
	}
}
