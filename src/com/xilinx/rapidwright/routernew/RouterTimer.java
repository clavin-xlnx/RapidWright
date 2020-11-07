package com.xilinx.rapidwright.routernew;

public class RouterTimer {
	public Timer firstIteration;
	public Timer rerouteCongestion;
	public Timer rerouteIllegal;
	
	public Timer rnodesCreation;
	public Timer rnodesDummy;
	
	public Timer calculateStatistics;
	public Timer updateCost;
	public Timer pipsAssignment;
	
	public Timer checkOnEntryNodeCongestion;
	public Timer checkIfEntryNodesRoutingValid;
	
	public Timer addRNodeToQueueEvaluation;
	public Timer addRNodeToQueuePushing;
	public Timer addRNodeDummy;
	
	public Timer getRouteNodeCost;
	
	public Timer getNextRoutable;
	public Timer getNextDummy;
	public Timer addChildren;
	
	public Timer getThruImmuTg;
	public Timer putEntryNodes;
	
	public RouterTimer() {
		this.firstIteration = new Timer("first iteration");
		this.rerouteCongestion = new Timer("reroute congestion");
		
		this.rnodesCreation = new Timer("rnodes creation");
		this.rnodesDummy = new Timer("rnodes dummy");
		
		this.rerouteIllegal = new Timer("fix illegal tree");
		this.calculateStatistics = new Timer("calc stat");
		this.updateCost = new Timer("update cost");
		this.pipsAssignment = new Timer("pips assignment");
		
		this.checkOnEntryNodeCongestion = new Timer("check on entry congestion");
		this.checkIfEntryNodesRoutingValid = new Timer("check entry routing valid");
		
		this.addRNodeToQueueEvaluation = new Timer("add RNode Evaluation");
		this.addRNodeToQueuePushing = new Timer("add RNode Pushing");
		this.addRNodeDummy = new Timer("add RNode dummy");
		
		this.getRouteNodeCost = new Timer("get RNode cost");
		
		this.getNextRoutable = new Timer(" get next routable");
		this.getNextDummy = new Timer(" get next dummy");
		this.addChildren = new Timer(" add Children");
		
		this.getThruImmuTg = new Timer("get thruImmuTg");
		this.putEntryNodes = new Timer("put entry nodes");
	}
	
	@Override
	public String toString() {
		String result = "";
		
//		result += this.firstIteration;
//		result += this.rerouteCongestion;
		result += this.rnodesCreation;
		result += this.rnodesDummy;
		
		result += this.getNextRoutable;
		result += this.addChildren;
		result += this.getNextDummy;
		
		result += this.calculateStatistics;
		result += this.updateCost;
		result += this.rerouteIllegal;
		result += this.pipsAssignment;
		result += this.checkOnEntryNodeCongestion;
		result += this.checkIfEntryNodesRoutingValid;
		
		result += this.addRNodeToQueueEvaluation;
		result += this.addRNodeToQueuePushing;
		result += this.addRNodeDummy;
		
		result += this.getRouteNodeCost;
		
		result += this.getThruImmuTg;
		result += this.putEntryNodes;
		
		return result;
	}
}

class Timer {
	private String name;
	private long time;
	private long start;
	
	public Timer(String name) {
		this.name = name;
		this.time = 0;
	}
	
	public void start() {
		this.start = System.nanoTime();
	}
	public void finish() {
		this.time += System.nanoTime() - this.start;
	}	
	public long getTime() {
		return this.time;
	}
	public String toString() {
		return String.format("%-25s %7.3f s\n", this.name, this.time * 1e-9);
	}
}