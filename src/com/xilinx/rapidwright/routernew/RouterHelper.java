package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;

public class RouterHelper {
	//a collections of methods for routing
	public RouterHelper(){
		
	}
	
	//methods to categorize the net
	public static boolean isRoutableNetWithSourceSinks(Net n){
		return n.getSource() != null && n.getSinkPins().size() > 0;
	}
	
	public static boolean isDriverLessOrLoadLessNet(Net n){
		return (isDriverLessNet(n) || isLoadLessNet(n));
	}
	
	public static boolean isDriverLessNet(Net n) {
		return (n.getSource() == null && n.getSinkPins().size() > 0);
	}
	
	public static boolean isLoadLessNet(Net n) {
		return (n.getSource() != null && n.getSinkPins().size() == 0);
	}
	
	public static boolean isInternallyRoutedNets(Net n){
		return n.getPins().size() == 0;
	}
	
	public static boolean isExternalConnectionToCout(SitePinInst source, SitePinInst sink){
		return source.getName().equals("COUT") && (!sink.getName().equals("CIN"));
	}
	
	//methods for pips assignment
	public static List<PIP> conPIPs(Connection con){
		List<PIP> conPIPs = new ArrayList<>();
		
		//nodes of a connection are added starting from the sink until the source
		for(int i = con.rnodes.size() -1; i > 0; i--){
			Node nodeFormer = ((RoutableNode) (con.rnodes.get(i))).getNode();
			Node nodeLatter = ((RoutableNode) (con.rnodes.get(i-1))).getNode();
			
			PIP pip = findThePIPbetweenTwoNodes(nodeFormer, nodeLatter);
			if(pip != null){
				conPIPs.add(pip);
			}else{
				System.err.println("Null PIP connecting node " + nodeFormer.toString() + " and node " + nodeLatter.toString());
			}
		}
		return conPIPs;
	}
	
	public static List<PIP> conPIPs(List<Node> conNodes){
		List<PIP> conPIPs = new ArrayList<>();
		
		//nodes of a connection are added starting from the sink to the source
		for(int i = conNodes.size() -1; i > 0; i--){
			Node nodeFormer = conNodes.get(i);
			Node nodeLatter = conNodes.get(i-1);
			
			PIP pip = findThePIPbetweenTwoNodes(nodeFormer, nodeLatter);
			
			if(pip != null){
				conPIPs.add(pip);
			}else{
				System.err.println("Null PIP connecting node " + nodeFormer.toString() + " and node " + nodeLatter.toString());
			}
		}
		return conPIPs;
	}
	
	public static PIP findThePIPbetweenTwoNodes(Node nodeFormer, Node nodeLatter) {
		PIP pip = null;
		List<PIP> nodeFormerPIPs = nodeFormer.getAllDownhillPIPs();
		for(PIP p : nodeFormerPIPs) {
			if(p.getEndNode().equals(nodeLatter)) {
				return p;
			}
		}
		
		if(nodeFormer.toString().contains("BYPASS")) {
			nodeFormerPIPs = nodeFormer.getAllUphillPIPs();
			for(PIP p : nodeFormerPIPs) {
				if(p.getStartNode().equals(nodeLatter)) {
					return p;
				}
			}
		}
		
		return pip;
	}
	
}
