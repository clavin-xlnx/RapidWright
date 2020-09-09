package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.ImmutableTimingGroup;

public class RouterHelper {
	//TODO a collections of methods for routing functionality
	public RouterHelper(){
		
	}
	
	//methods to check the net
	public static boolean isRegularNetToBeRouted(Net n){
		return n.getSource() != null && n.getSinkPins().size() > 0;
	}
	
	public static boolean isOneTypePinNet(Net n){
		return (n.getSource() != null && n.getSinkPins().size() == 0) || (n.getSource() == null && n.getSinkPins().size() > 0);
	}
	
	public static boolean isNoPinNets(Net n){
		return n.getPins().size() == 0;
	}
	
	public static boolean isExternalConnectionToCout(SitePinInst source, SitePinInst sink){
		return source.getName().equals("COUT") && (!sink.getName().equals("CIN"));
	}
	
	public static boolean isCyclicRoutingTree(Net n){
		boolean isCyclic = false;
		
		return isCyclic;
	}
	
	//methods for pips assignment
	public static List<PIP> conPIPs(Connection con){
		List<PIP> conPIPs = new ArrayList<>();
		
		//nodes of a connection are added starting from the sink until the source
		for(int i = con.rnodes.size() -1; i > 0; i--){
			Node nodeFormer = ((RoutableNode) (con.rnodes.get(i))).getNode();
			Node nodeLatter = ((RoutableNode) (con.rnodes.get(i-1))).getNode();
			
			PIP pip = findThePIPbetweenTwoNodes(nodeFormer.getAllWiresInNode(), nodeLatter);
			if(pip != null){
				conPIPs.add(pip);
			}else{
				System.err.println("Null PIP connecting node " + nodeFormer.toString() + " and node " + nodeLatter.toString());
			}
		}
		return conPIPs;
	}
	
	public static PIP findThePIPbetweenTwoNodes(Wire[] nodeFormerWires, Node nodeLatter){
		PIP pip = null;
		Tile pipTile = nodeLatter.getTile();
		int wire1 = nodeLatter.getWire();
		for(Wire wire:nodeFormerWires){
			if(wire.getTile().equals(pipTile)){
				pip = pipTile.getPIP(wire.getWireIndex(), wire1);
				if(pip != null){
					 break;
				}
			}	
		}
		return pip;
	}
	
	public static ImmutableTimingGroup findImmutableTimingGroup(RoutableTimingGroup rtgFirst, RoutableTimingGroup rtgSecond){
		if(rtgSecond.getSiblingsTimingGroup().getSiblings().length > 1){
			Node exitofFirst = rtgFirst.getSiblingsTimingGroup().getExitNode();
			ImmutableTimingGroup[] immus = rtgSecond.getSiblingsTimingGroup().getSiblings();
			int length = immus.length;
			for(int i = 0; i < length; i++){
				Node entryOfSecond = immus[i].entryNode();
				if(twoNodesAbutted(exitofFirst, entryOfSecond)){
					return immus[i];
				}
			}
		}else{
			return rtgSecond.getSiblingsTimingGroup().getSiblings()[0];
		}
		
		System.err.println("Null ImmutableTimingGroup found connecting two SiblingsTimingGroup");
		return null;
	}
	
	public static boolean twoNodesAbutted(Node parent, Node nodeToCheck){
		for(Node n:parent.getAllDownhillNodes()){
			if(n.equals(nodeToCheck))
				return true;
		}
		return false;
	}
	
}
