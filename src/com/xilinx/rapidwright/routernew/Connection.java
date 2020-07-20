package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.timing.TimingModel;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.routernew.RNode;

public class Connection<E> implements Comparable<Connection<E>>{
	public final int id;
	
	public final SitePinInst source;
	public final SitePinInst sink;
	
	/**variables for timing information
	 * TODO to be specified according to RW timing information
	 */
	/*private final TimingVertex sourceTimingNode;
	private final TimingVertex sinkTimingNode;
	private TimingEdge timingEdge;*/
	private float criticality;
	
	public Netplus<E> net;
    public final int boundingBox;
	
	public final String sourceName;
	
	private RNode<E> sourceRNode;
	private RNode<E> sinkRNode;
	boolean sinkRNodeSet;
	
	public List<RNode<E>> rnodes;
	public List<PIP> pips;
	public String targetName;//remove target name save memory?
	
	public Connection(int id, SitePinInst source, SitePinInst sink, TimingModel tm){
		this.id = id;
		
		this.source = source;
		this.sink = sink;
		
		//TODO check information appearS in timinggraph.java
		/*this.sourceTimingNode = new TimingVertex(this.source.getName());
		this.sinkTimingNode = new TimingVertex(this.sink.getName());
		this.timingEdge = new TimingEdge(tm.getTimingManager().getTimingGraph(), this.sourceTimingNode,
				this.sinkTimingNode, null, this.net.getNet());//this.net should be set first
		*/
		
		this.boundingBox = this.calculateBoundingBox();
		this.sourceName = this.source.getName();
		
		this.rnodes = new ArrayList<>();
		this.pips = new ArrayList<>();
		this.sinkRNodeSet = false;
	}
	
	//TODO optimization using Wire wire / Node node /TimingGroup tg as the target
	public void setTargetName(RoutingGranularityOpt opt){
		if(opt == RoutingGranularityOpt.WIRE){
			this.targetName = this.sink.getSiteInst().getTile().getName() + "/" + this.sink.getSiteExternalWireIndex();
		}else if(opt == RoutingGranularityOpt.NODE){
			this.targetName = this.sink.getConnectedNode().toString();
		}else if(opt == RoutingGranularityOpt.TIMINGGROUP){
			
		}
	}
	
	public int calculateBoundingBox() {
		int min_x, max_x, min_y, max_y;
		
		int sourceX = this.source.getTile().getColumn();
		int sinkX = this.sink.getTile().getColumn();
		if(sourceX < sinkX) {
			min_x = sourceX;
			max_x = sinkX;
		} else {
			min_x = sinkX;
			max_x = sourceX;
		}
		
		int sourceY = this.source.getTile().getRow();
		int sinkY = this.sink.getTile().getRow();
		if(sourceY < sinkY) {
			min_y = sourceY;
			max_y = sinkY;
		} else {
			min_y = sinkY;
			max_y = sourceY;
		}
		
		return (max_x - min_x + 1) + (max_y - min_y + 1);
	}
	
	public void setNet(Netplus<E> net){
		this.net = net;
	}
	public Netplus<E> getNet(){
		return this.net;
	}
	//TODO RNode<E>
	public boolean isInBoundingBoxLimit(RNode<E> rnode){
//		this.printInfo("\t\t" + this.net.x_min_b + ", " + this.net.x_max_b + " " + this.net.y_min_b + ", " + this.net.y_max_b);
//		this.printInfo("\t\t" + rnode.xlow + ", " + rnode.xhigh + " " + rnode.ylow + ", " + rnode.yhigh);
		
		return rnode.xlow < this.net.x_max_b && rnode.xhigh > this.net.x_min_b && rnode.ylow < this.net.y_max_b && rnode.yhigh > this.net.y_min_b;
	}
	
	public void printInfo(String s){
		System.out.println(s);
	}
	
	public void addPIP(PIP p){
		this.pips.add(p);
	}
	
	public void resetConnection(){
		this.rnodes.clear();
	}
	
	
	public RNode<E> getSourceRNode() {
		return sourceRNode;
	}

	public void setSourceRNode(RNode<E> sourceNode) {
		this.sourceRNode = sourceNode;
	}

	public RNode<E> getSinkRNode() {
		return sinkRNode;
	}

	public void setSinkRNodeAndTargetName(RNode<E> childRNode) {
		this.sinkRNode = childRNode;
		this.targetName = this.sinkRNode.name;
		this.sinkRNodeSet = true;
	}

	public void setCriticality(float criticality) {
		this.criticality = criticality;
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
		s.append(String.format("net = %12s", this.net.getNet().getName()));
		s.append(", ");
		s.append(String.format("net fanout = %3s", this.net.fanout));
		s.append(", ");
		s.append(String.format("source = %26s", this.source.getName() + " -> " + this.source.getTile().getName() + "/" + this.source.getSiteExternalWireIndex()));
		s.append(", ");
		s.append(String.format("sink = %26s", this.sink.getTile().getName() + "/" + this.sink.getSiteExternalWireIndex() + " -> " +  this.sink.getName()));
		s.append(", ");
		s.append(String.format("mahattan d = %4d ", this.getManhattanDistance()));
		
		return s.toString();	
		
	}

	public float getCriticality() {
		// TODO Auto-generated method stub
		return this.criticality;
	}
	
	public int getManhattanDistance() {
		int dx = Math.abs(this.source.getTile().getColumn() - this.sink.getTile().getColumn());
		int dy = Math.abs(this.source.getTile().getRow() - this.sink.getTile().getRow());
		int manhattanDistance = dx + dy;
		
		return manhattanDistance;
	}
	
	public boolean congested() {
		for(RNode<E> rn : this.rnodes){
			if(rn.overUsed()) {
				return true;
			}
		}
		return false;
	}
	
	public boolean illegal() {
		for(RNode<E> rn : this.rnodes){
			if(rn.illegal()) {
				return true;
			}
		}
		return false;
	}
	
	@Override
	public int compareTo(Connection<E> arg0) {
		// TODO Auto-generated method stub
		return 0;
	}
	
	public int hashCode(){
		return this.id;
	}
	
	public void addRNode(RNode<E> rn) {
		this.rnodes.add(rn);	
	}
}
