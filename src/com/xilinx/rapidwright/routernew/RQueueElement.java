package com.xilinx.rapidwright.routernew;

public class RQueueElement {
	final Routable rnode;
	final float cost;
	
	public RQueueElement(Routable rnode, float cost){
		this.rnode = rnode;
		this.cost = cost;
	}
}
