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
	
	public int getOccupancy();
	
	public void updatePresentCongestionPenalty(float pres_fac);
	public float getPres_cost();
	public void setPres_cost(float pres_cost);
	public float getAcc_cost();
	public void setAcc_cost(float acc_cost);
	
	public float getManhattanD();
	public boolean isInBoundingBoxLimit(Connection con);
	public String toString();
	public int hashCode();
	public RoutableType getRoutableType();
	public boolean isGlobal();
	public boolean isBounce();

}
