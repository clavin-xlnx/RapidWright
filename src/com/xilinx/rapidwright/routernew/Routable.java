package com.xilinx.rapidwright.routernew;

import java.util.List;

import com.xilinx.rapidwright.device.Tile;

public interface Routable {
	
	public final String ROUTABLE_TILE = "INT_";
	
	public Tile getTle();
	
	public List<Routable> getChildren();
	
	public void setChildren(List<Routable> children);
	
	public void setBaseCost();
	
	public boolean overUsed();
	
	public boolean used();
	
	public boolean illegal();
	
	public void setCenterXY();
	
	public void updatePresentCongestionPenalty(float pres_fac);
	
	public String toString();

}
