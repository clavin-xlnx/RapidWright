package com.xilinx.rapidwright.routernew;

public class QueueElement<E> {
	final RNode<E> rnode;
	final float cost;
	
	public QueueElement(RNode<E> rnode, float cost){
		this.rnode = rnode;
		this.cost = cost;
	}
}
