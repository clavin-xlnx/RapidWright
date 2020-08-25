package com.xilinx.rapidwright.routernew;

public interface Routable {
	//public static final
	short capacity = 1;
	
	public void setBaseCost(float base_cost_fac);

	public boolean used();
	public boolean overUsed();
	public boolean illegal();
	
	public void setXY();
	public float getCenterX();
	public float getCenterY();
	
	public boolean isTarget();
	public void setTarget(boolean isTarget);
	
	public void updatePresentCongestionPenalty(float pres_fac);
	public float getManhattanD();
	public boolean isInBoundingBoxLimit(Connection con);
	public String toString();
	public int hashCode();
}
