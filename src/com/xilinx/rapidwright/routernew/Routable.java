package com.xilinx.rapidwright.routernew;

import java.util.List;

import com.xilinx.rapidwright.device.Tile;

public interface Routable {
	public RoutableType type = null;
	public Tile tile = null;
//	public Wire wire = null;
	public String name = null;	
	/*
	 * information used to check if the RNode is in the routing bounding box of a connection
	 * using the column/row of the tile
	 */
	public short xlow = 0;
	public short xhigh = 0;
	public short ylow = 0;
	public short yhigh = 0;
	public float centerx = 0;
	public float centery = 0;
	
	public short capacity = 1;
	
	public float delay = 0;//TODO for timing-driven
		
	public float base_cost = 0;
	
	public RNodeData<Routable> rNodeData = null;
	
	public boolean target = false;
	public List<Routable> children = null;//populate the child routable nodes of the current 
	
	public List<Routable> getChildren();
	public void setChildren(List<Routable> children);
	public void setBaseCost();
	public boolean overUsed();
	public boolean used();
	public boolean illegal();
	public void setCenterXY();
	public void updatePresentCongestionPenalty(float pres_fac);
	public String toString();
//	public boolean existsInGraph();
}
