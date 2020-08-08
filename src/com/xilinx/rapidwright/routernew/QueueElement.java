package com.xilinx.rapidwright.routernew;

public class QueueElement {
	final Routable rnode;
	final float cost;
	
	public QueueElement(Routable rnode, float cost){
		this.rnode = rnode;
		this.cost = cost;
	}
}
