package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;

public class Netplus{
	/**
	 * Netplus is a class with additional information of a net for 
	 * a PathFinder-based router development
	 */
	private List<Connection> connections;
	public Net net;
	private NetType type;
	private int id;
	public short x_min_b, x_max_b;//short better
	public short y_min_b, y_max_b;
	public float x_geo, y_geo;
	public short hpwl;
	public short bbRange;
	public short fanout;
	
	public Netplus(int id, short bbRange, Net net){
		this.id = id;
		this.bbRange = bbRange;
		this.net = net;
		this.type = net.getType();
		this.connections = new ArrayList<>();
		this.fanout = (short) this.net.getFanOut();//getSinkPins().size();
		this.getBoundingXYs(net);
	}
	
	public void getBoundingXYs(Net net){
		short x_min = 1<<10;
		short x_max = 0;
		short y_min = 1<<10;
		short y_max = 0;
		short x_geo_sum = 0;
		short y_geo_sum = 0;
		
		short numPins = (short) (this.net.getFanOut() + 1); //(net.getPins().size());
		
		short[] xArray = new short[numPins];
		short[] yArray = new short[numPins];
		
		xArray[0] = (short) net.getSource().getTile().getColumn();
		yArray[0] = (short) net.getSource().getTile().getRow();
		
		x_geo_sum += xArray[0];
		y_geo_sum += yArray[0];
		
		
		int iPin = 1;
		for(SitePinInst sink:net.getSinkPins()){
			xArray[iPin] = (short) sink.getTile().getColumn();
			yArray[iPin] = (short) sink.getTile().getRow();
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
		
		this.hpwl = (short) ((x_max - x_min + 1) + (y_max - y_min + 1));
		this.x_geo = x_geo_sum / numPins;
		this.y_geo = y_geo_sum / numPins;
		
		this.x_min_b = (short) (x_min - this.bbRange);
		this.x_max_b = (short) (x_max + this.bbRange);
		this.y_min_b = (short) (y_min - this.bbRange);
		this.y_max_b = (short) (y_max + this.bbRange);
		
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
	public void addCons(Connection c){
		this.connections.add(c);
	}
	public List<Connection> getConnection(){
		return this.connections;
	}
	
	public Routable getIllegalNode() {
		for(Connection con : this.connections) {
			for(Routable rnode : con.rnodes) {
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
