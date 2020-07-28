package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.routernew.RNode;

public class RNetplus<E>{
	/**
	 * Netplus is a class with additional information of a net for 
	 * a PathFinder-based router development
	 */
	private List<Connection<E>> connections;
	public Net net;
	private NetType type;
	private int id;
	public int x_min_b, x_max_b;//short better
	public int y_min_b, y_max_b;
	public float x_geo, y_geo;
	public int hpwl;
	public int bbRange;
	public int fanout;
	
	public RNetplus(int id, int bbRange, Net net){
		this.id = id;
		this.bbRange = bbRange;
		this.net = net;
		this.type = net.getType();
		this.connections = new ArrayList<>();
		this.fanout = this.net.getFanOut();
		this.getBoundingXYs(net);	
	}
	//TODO some tiles has more than one sites, needed to distinguish those cases?
	//or roughly computation does not impact the accuracy a lot?
	public void getBoundingXYs(Net net){
		int x_min = 1<<20;
		int x_max = 0;
		int y_min = 1<<20;
		int y_max = 0;
		int x_geo_sum = 0;
		int y_geo_sum = 0;
		
		int numPins = net.getFanOut() + 1;
		
		int[] xArray = new int[numPins];
		int[] yArray = new int[numPins];
		
		xArray[0] = net.getSource().getTile().getColumn();
		yArray[0] = net.getSource().getTile().getRow();
		
		x_geo_sum += xArray[0];
		y_geo_sum += yArray[0];
		
		
		int iPin = 1;
		for(SitePinInst sink:net.getSinkPins()){
			xArray[iPin] = sink.getTile().getColumn();
			yArray[iPin] = sink.getTile().getRow();
			x_geo_sum += xArray[iPin];
			y_geo_sum += yArray[iPin];
			iPin++;
		}
		
		for(int i = 0; i < numPins; i++){
			if(x_max < xArray[i]){
				x_max = xArray[i];
			}
			if(x_min > xArray[i]){
				x_min = xArray[i];
			}
			if(y_max < yArray[i]){
				y_max = yArray[i];
			}
			if(y_min > yArray[i]){
				y_min = yArray[i];
			}
		}
		
		this.hpwl = (x_max - x_min + 1) + (y_max - y_min + 1);
		this.x_geo = x_geo_sum / numPins;
		this.y_geo = y_geo_sum / numPins;
		
		this.x_min_b = x_min - this.bbRange;
		this.x_max_b = x_max + this.bbRange;
		this.y_min_b = y_min - this.bbRange;
		this.y_max_b = y_max + this.bbRange;
		
	}
	
	public Net getNet(){
		return this.net;
	}
	public NetType getType() {
		return type;
	}
	
	public int getId() {
		return id;
	}
	public void addCons(Connection<E> c){
		this.connections.add(c);
	}
	public List<Connection<E>> getConnection(){
		return this.connections;
	}
	
	public RNode<E> getIllegalNode() {
		for(Connection<E> con : this.connections) {
			for(RNode<E> rnode : con.rnodes) {
				if(rnode.illegal()) {
					return rnode;
				}
			}
		}
		return null;
	}
	
	public int hashCode(){
		return this.id;
	}
}
