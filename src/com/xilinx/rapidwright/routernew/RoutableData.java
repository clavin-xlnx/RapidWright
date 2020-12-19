package com.xilinx.rapidwright.routernew;

import com.xilinx.rapidwright.design.SitePinInst;

public class RoutableData {
	public final int index;
	
	private float pres_cost;
	private float acc_cost;
	
	private float partial_path_cost;
	private float lower_bound_total_path_cost;
	
	private boolean touched;
	
	private Routable prev;
	
	private int level;
	
	public CountingSet<SitePinInst> sourcesSet;//the sources of nets occupying the resource
	public CountingSet<Routable> parentsSet;//the drivers of the resource
	
	public RoutableData(int index) {
    	this.index = index;
    	this.pres_cost = 1;
    	this.acc_cost = 1;
    	this.setTouched(false);

		this.sourcesSet = null;
		this.parentsSet = null;
		this.level = 0;
		this.prev = null;
	}
	
	public boolean updateLowerBoundTotalPathCost(float new_lower_bound_total_path_cost) {
		if (new_lower_bound_total_path_cost < this.lower_bound_total_path_cost) {
			this.lower_bound_total_path_cost = new_lower_bound_total_path_cost;
			return true;
		}
		return false;
	}
	
	public void setLowerBoundTotalPathCost(float new_lower_bound_total_path_cost) {
		this.lower_bound_total_path_cost = new_lower_bound_total_path_cost;
		this.setTouched(true);
	}
	public void setPartialPathCost(float new_partial_path_cost) {
		this.partial_path_cost = new_partial_path_cost;
	}
	
	public float getLowerBoundTotalPathCost() {
		return this.lower_bound_total_path_cost;
	}
	public float getPartialPathCost() {
		return this.partial_path_cost;
	}

	public void addSource(SitePinInst source) {
		if(this.sourcesSet == null) {
			this.sourcesSet = new CountingSet<SitePinInst>();
		}
		this.sourcesSet.add(source);
	}
	
	public int numUniqueSources() {
		if(this.sourcesSet == null) {
			return 0;
		}
		return this.sourcesSet.uniqueSize();
	}
	
	public void removeSource(SitePinInst source) {
		this.sourcesSet.remove(source);
		if(this.sourcesSet.isEmpty()) {
			this.sourcesSet = null;
		}
	}

	public int countSourceUses(SitePinInst source) {
		if(this.sourcesSet == null) {
			return 0;
		}
		return this.sourcesSet.count(source);
	}
	
	public int numUniqueParents() {
		if(this.parentsSet == null) {
			return 0;
		}
		return this.parentsSet.uniqueSize();
	}
	
	public void addParent(Routable parent) {
		if(this.parentsSet == null) {
			this.parentsSet = new CountingSet<>();
		}
		this.parentsSet.add(parent);
	}
	
	public void removeParent(Routable parent) {
		this.parentsSet.remove(parent);
		if(this.parentsSet.isEmpty()) {
			this.parentsSet = null;
		}
	}
	
	public int getOccupancy() {
		return this.numUniqueSources();
	}
	
	public Routable getPrev() {
		return prev;
	}

	public void setPrev(Routable prev) {
		this.prev = prev;
	}
	
	public int getLevel() {
		return level;
	}
	
	public float getPres_cost() {
		return pres_cost;
	}

	public void setPres_cost(float pres_cost) {
		this.pres_cost = pres_cost;
	}

	public float getAcc_cost() {
		return acc_cost;
	}

	public void setAcc_cost(float acc_cost) {
		this.acc_cost = acc_cost;
	}

	public void setLevel(int level) {
		this.level = level;
	}

	public boolean isTouched() {
		return touched;
	}

	public void setTouched(boolean touched) {
		this.touched = touched;
	}

	@Override
	public int hashCode() {
		return this.index;
	}
	
	@Override
	public String toString(){
		StringBuilder s = new StringBuilder();
		s.append("RNode " + this.index + " ");
		s.append(", ");
		s.append(String.format("occupation = %d", this.getOccupancy()));
		s.append(", ");
		s.append(String.format("num_unique_sources = %d", this.numUniqueSources()));
		s.append(", ");
		s.append(String.format("num_unique_parents = %d", this.numUniqueParents()));
		s.append(", ");
		s.append(String.format("level = %d", this.getLevel()));
		s.append(",");
		return s.toString();
	}
	
}
