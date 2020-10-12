package com.xilinx.rapidwright.routernew;

public class RouterTimer {
	public Timer firstIteration;
//	public Timer updateTiming;
//	public Timer rerouteCritical;
	public Timer rerouteCongestion;
//	public Timer rerouteIllegal;
//	public Timer rerouteOpin;
	public Timer rerouteIllegal;
//	public Timer setRerouteCriticality;
	public Timer rnodesCreation;
	public Timer calculateStatistics;
	public Timer updateCost;
	public Timer pipsAssignment;
	public Timer rtgFilter;
	public Timer checkOnEntryNodeCongestion;
	public Timer checkIfEntryNodesRoutingValid;
	
	public RouterTimer() {
		this.firstIteration = new Timer("first iteration");
//		this.updateTiming = new Timer("update timing");
//		this.rerouteCritical = new Timer("reroute critical");
		this.rerouteCongestion = new Timer("reroute congestion");
//		this.rerouteIllegal = new Timer("reroute illegal");
		this.rnodesCreation = new Timer("rnodes creation");
//		this.rerouteOpin = new Timer("reroute opin");
		this.rerouteIllegal = new Timer("fix illegal tree");
//		this.setRerouteCriticality = new Timer("set reroute crit");
		this.calculateStatistics = new Timer("calc stat");
		this.updateCost = new Timer("update cost");
		this.pipsAssignment = new Timer("pips assignment");
		this.rtgFilter = new Timer("rtg filter");
		this.checkOnEntryNodeCongestion = new Timer("check on entry node congestion");
		this.checkIfEntryNodesRoutingValid = new Timer("check if entry nodes routing valid");
	}
	
	@Override
	public String toString() {
		String result = "";
		
		result += this.firstIteration;
		result += this.rerouteCongestion;
//		result += this.rerouteIllegal;
//		result += this.rerouteCritical;
//		result += this.setRerouteCriticality;
		result += this.rnodesCreation;
		result += this.calculateStatistics;
//		result += this.updateTiming;
		result += this.updateCost;
		result += this.rerouteIllegal;
		result += this.pipsAssignment;
		result += this.rtgFilter;
		result += this.checkOnEntryNodeCongestion;
		result += this.checkIfEntryNodesRoutingValid;
		
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
		return String.format("%-20s %7.2f s\n", this.name, this.time * 1e-9);
	}
}