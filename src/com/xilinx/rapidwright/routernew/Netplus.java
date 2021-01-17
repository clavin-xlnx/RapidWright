package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
/**
 * Netplus is a wrapper class of Net with additional information for router
 */
public class Netplus{
	private List<Connection> connections;
	public Net net;
	private int id;
	public short x_min_b, x_max_b;
	public short y_min_b, y_max_b;
	public float x_geo, y_geo;
	public short hpwl;
	public short fanout;
	
	public Netplus(int id, short bbRange, Net net){
		this.id = id;
		this.net = net;
		this.connections = new ArrayList<>();
		this.fanout = (short) this.net.getSinkPins().size();
		this.getBoundingXYs(net, bbRange);
	}
	
	public void getBoundingXYs(Net net, short bbRange){
		short x_min = 1<<10;
		short x_max = 0;
		short y_min = 1<<10;
		short y_max = 0;
		short x_geo_sum = 0;
		short y_geo_sum = 0;
		
		short numPins = (short) (this.net.getPins().size());//non-zero pin size should be guaranteed
		
		short[] xArray = new short[numPins];
		short[] yArray = new short[numPins];
		
		int iPin = 0;
		for(SitePinInst spi:net.getPins()){
			
			try{
				short x = (short) spi.																																																																																																											getTile().getColumn();
				short y = (short) spi.getTile().getRow();
				xArray[iPin] = x;
				yArray[iPin] = y;
				x_geo_sum += x;
				y_geo_sum += y;
				iPin++;
			}catch(Exception e){
				System.out.println(net.toStringFull() + ", spi = " + spi.getName() + ", is source? " + spi.isOutPin());
			}
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
		
		this.x_min_b = (short) (x_min - 2*bbRange);
		this.x_max_b = (short) (x_max + 2*bbRange);
		this.y_min_b = (short) (y_min - bbRange);
		this.y_max_b = (short) (y_max + bbRange);
		
	}
	
	public Net getNet(){
		return this.net;
	}
	public NetType getType() {
		return this.net.getType();
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
	
	public Routable getIllegalRNode() {
		for(Connection con : this.connections) {
			for(Routable rnode : con.rnodes) {
				if(rnode.illegal()) {
					return rnode;
				}
			}
		}
		return null;
	}
	
	public void extendBoundingBox() {
		this.x_min_b -= 4;
		this.x_max_b += 4;
		this.y_min_b -= 2;
		this.y_max_b += 2;
	}
	
	public int hashCode(){
		return this.id;
	}
}
