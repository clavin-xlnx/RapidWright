package com.xilinx.rapidwright.routernew;

import com.xilinx.rapidwright.design.SitePinInst;

public class RNodeData<E> {
//	public final int index;
	
	public float pres_cost;
	public float acc_cost;
	
	public float partial_path_cost;
	public float lower_bound_total_path_cost;
	
	public boolean touched;
	
	public RNode<E> prev;
	
	public int occupation;
	public int level;
	
	//SitePinInst -> source and sink of the connection
	private CountingSet<SitePinInst> sourcesSet;
	private CountingSet<RNode<E>> parentsSet;//the drivers of the route node
	
	public RNodeData() {
//    	this.index = index;
    	this.pres_cost = 1;
    	this.acc_cost = 1;
    	this.occupation = 0;
    	this.touched = false;

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
		this.touched = true;
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
	
	public void addParent(RNode<E> parent) {
		if(this.parentsSet == null) {
			this.parentsSet = new CountingSet<>();
		}
		this.parentsSet.add(parent);
	}
	
	public void removeParent(RNode<E> parent) {
		this.parentsSet.remove(parent);
		if(this.parentsSet.isEmpty()) {
			this.parentsSet = null;
		}
	}
	
	public RNode<E> getPrev() {
		return prev;
	}

	public void setPrev(RNode<E> prev) {
		this.prev = prev;
	}
	
	public int getLevel() {
		return level;
	}

	public void setLevel(int level) {
		this.level = level;
	}

	/*@Override
	public int hashCode() {
		return this.index;
	}*/
	
}
