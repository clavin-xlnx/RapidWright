package com.xilinx.rapidwright.routernew;

public class RouterTimer {
	public Timer firstIteration;
	public Timer rerouteCongestion;
	public Timer rerouteIllegal;
	public Timer rnodesCreation;
	public Timer updateTiming;
	public Timer calculateStatistics;
	public Timer updateCost;
	public Timer pipsAssignment;
	
	public RouterTimer() {
		this.firstIteration = new Timer("first iteration");
		this.rerouteCongestion = new Timer("reroute congestion");
		this.rnodesCreation = new Timer("rnodes creation");	
		this.rerouteIllegal = new Timer("fix illegal tree");
		this.calculateStatistics = new Timer("calc stat");
		this.updateCost = new Timer("update cost");
		this.updateTiming = new Timer("update timing");
		this.pipsAssignment = new Timer("pips assignment");
	}
	
	@Override
	public String toString() {
		String result = "";
		
		result += this.firstIteration;
		result += this.rerouteCongestion;	
		result += this.rnodesCreation;	
		result += this.calculateStatistics;
		result += this.updateTiming;
		result += this.updateCost;
		result += this.rerouteIllegal;
		result += this.pipsAssignment;
		
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