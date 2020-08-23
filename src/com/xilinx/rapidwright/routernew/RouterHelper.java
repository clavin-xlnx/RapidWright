package com.xilinx.rapidwright.routernew;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;

public class RouterHelper {
	//TODO a collections of methods for routing functionality
	public RouterHelper(){
		
	}
	
	//methods to check the net
	public static boolean isRegularNetToBeRouted(Net n){
		return n.getSource() != null && n.getSinkPins().size() > 0;
	}
	
	public static boolean isOnePinTypeNetWithoutPips(Net n){
		return (n.getSource() != null && n.getPins().size() == 1) || (n.getSource() == null && n.getSinkPins().size() > 0 && n.hasPIPs() == false);
	}
	
	public static boolean isNetWithInputPinsAndPips(Net n){
		return n.getSource() == null && n.getSinkPins().size() > 0 && n.hasPIPs() == true;
	}
	
	public static boolean isExternalConnectionToCout(SitePinInst source, SitePinInst sink){
		return source.getName().equals("COUT") && (!sink.getName().equals("CIN"));
	}
	
}
