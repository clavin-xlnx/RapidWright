package com.xilinx.rapidwright.routernew;

import java.util.List;

import com.xilinx.rapidwright.device.Tile;

public interface Routable {
	//public static final
	short capacity = 1;
	
	public Tile getTle();
	
	public List<Routable> getChildren();
	
	public void setBaseCost(float base_cost_fac);

	public boolean used();
	public boolean overUsed();
	public boolean illegal();
	
	public void setXY();
	public float getCenterX();
	public float getCenterY();
	
	public void updatePresentCongestionPenalty(float pres_fac);
	public float getManhattanD();
	public boolean isInBoundingBoxLimit(@SuppressWarnings("rawtypes") Connection con);
	
}
