package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteThruHelper;

public class RoutableNode implements Routable{
	public int index;
	private Node node;
	public RoutableType type;
	
	public short xlow, xhigh;
	public short ylow, yhigh;
		
	public float base_cost;
	
	public final RoutableData rnodeData;
	
	public boolean target;
	public List<RoutableNode> children;
	public boolean childrenSet;
	
	public RoutableNode(int index, SitePinInst sitePinInst, RoutableType type){
		this.index = index;
		this.type = type;
		this.node = sitePinInst.getConnectedNode();
		this.rnodeData = new RoutableData(this.index);
		this.childrenSet = false;
		this.target = false;
		this.setXY();
	}
	
	public RoutableNode(int index, Node node, RoutableType type){
		this.index = index;
		this.type = type;
		this.node = node;
		this.rnodeData = new RoutableData(this.index);
		this.childrenSet = false;
		this.target = false;
		this.setXY();
	}
	
	public int setChildren(Connection c, int globalIndex, float base_cost_fac, Map<Node, RoutableNode> createdRoutable, RouteThruHelper routethruHelper){
		this.children = new ArrayList<>();
		List<Node> allDownHillNodes = this.node.getAllDownhillNodes();
		for(Node node:allDownHillNodes){
			//TODO check if CLK_CMT_MUX_3TO1_32_CLK_OUT connected to the target node is included
			/*if(this.targetTileOfTheLocalClockNetFound(node, c)){
				System.out.println(routethruHelper.isRouteThru(this.node, node));
				System.out.println();
			}*/
			//TODO recognize available routethrus
			if(!routethruHelper.isRouteThru(this.node, node)){//routethrus are forbidden in this way
				if(!createdRoutable.containsKey(node)){
					RoutableNode child;
					child = new RoutableNode(globalIndex, node, RoutableType.INTERRR);
					child.setBaseCost(base_cost_fac);
					globalIndex++;
					this.children.add(child);
					createdRoutable.put(node, child);
				}else{
					this.children.add(createdRoutable.get(node));//the sink routable of a target has been created up-front 
				}
			}
		}
		this.childrenSet = true;
		return globalIndex++;
	}
	
	public boolean targetTileOfTheLocalClockNetFound(Node node, Connection c){
		boolean foundTargetTile = node.getTile().getName().equals("XIPHY_L_X63Y120");
		
		if(foundTargetTile){
			Tile tile = node.getTile();
			Set<Node> allNodesInTile = new HashSet<>();
			
			for(PIP pip:tile.getPIPs()){
				Node nodeStart = pip.getStartNode();
				allNodesInTile.add(nodeStart);
				Node nodeEnd = pip.getEndNode();
				allNodesInTile.add(nodeEnd);
			}
			
			Node targetNode = ((RoutableNode)c.getSinkRNode()).getNode();
			for(Node n:targetNode.getAllUphillNodes()){
				System.out.println(n.toString());
			}
		}
		
		
		return foundTargetTile;
	}
	
	//TODO how to efficiently check if the routethru is available
	public boolean containsAvailableRoutethru(Node n){
		boolean containsRouthru = false;
		for(Wire w:n.getAllWiresInNode()){
			if(w.isRouteThru()){
				containsRouthru = true;
				System.out.println(w.getWireName() + " ");
				break;
			}
		}
		return containsRouthru;
	}
	
	@Override
	public void setBaseCost(float fac) {
		this.setBaseCost();
		this.base_cost *= fac;//(this.xhigh - this.xlow) + (this.yhigh - this.ylow) + 1;
	}
	
	public void setBaseCost(){
		if(this.type == RoutableType.SOURCERR){
			base_cost = 1;
			
		}else if(this.type == RoutableType.INTERRR){
			//aver cost around 4 when using deltaX + deltaY +1 
			//(most (deltaX + deltaY +1 ) values range from 1 to 90+, maximum can be 176)
			//(deltaX + deltaY +1 ) normalized to the maximum , does not work
			base_cost = 1;
			
		}else if(this.type == RoutableType.SINKRR){//this is for faster maze expansion convergence to the sink
			base_cost = 0.95f;//virtually the same to the logic block input pin, since no alternative ipins are considered
		}else{
			base_cost = 1;
		}
	}

	@Override
	public boolean overUsed() {
		return Routable.capacity < this.rnodeData.getOccupation();
	}
	
	@Override
	public boolean used(){
		return this.rnodeData.getOccupation() > 0;
	}
	
	@Override
	public boolean illegal(){
		return Routable.capacity < this.rnodeData.numUniqueParents();
	}

	@Override
	public void setXY() {
		int length = this.node.getAllWiresInNode().length;
		short[] xCoordinates = new short[length];
		short[] yCoordinates = new short[length];
		short id = 0;
		for(Wire w : this.node.getAllWiresInNode()){
			xCoordinates[id] = (short) w.getTile().getColumn();
			yCoordinates[id] = (short) w.getTile().getRow();
			id++;
		}
		
		if(length == 1){
			this.xlow = xCoordinates[0];
			this.xhigh = this.xlow;
			this.ylow = yCoordinates[0];
			this.yhigh = this.ylow;
		}else if(length == 2){
			if(xCoordinates[0] < xCoordinates[1]){
				this.xlow = xCoordinates[0];
				this.xhigh = xCoordinates[1];
			}else{
				this.xlow = xCoordinates[1];
				this.xhigh = xCoordinates[0];
			}
			if(yCoordinates[0] < yCoordinates[1]){
				this.ylow = yCoordinates[0];
				this.yhigh = yCoordinates[1];
			}else{
				this.ylow = yCoordinates[1];
				this.yhigh = yCoordinates[0];
			}
		}else{
			this.xlow = this.min(xCoordinates);
			this.xhigh = this.max(xCoordinates);
			this.ylow = this.min(yCoordinates);
			this.yhigh = this.max(yCoordinates);
		}
	}
	
	public short max(short[] coordinates){
		short max = 0;
		for(short c:coordinates){
			if(c > max)
				max = c;
		}
		return max;
	}
	public short min(short[] coordinates){
		short min = 10000;
		for(short c:coordinates){
			if(c < min)
				min = c;
		}
		return min;
	}
	
	@Override
	public void updatePresentCongestionPenalty(float pres_fac) {
		
		RoutableData data = this.rnodeData;
		
		int occ = data.numUniqueSources();
		int cap = Routable.capacity;
		
		if (occ < cap) {
			data.setPres_cost(1);
		} else {
			data.setPres_cost(1 + (occ - cap + 1) * pres_fac);
		}

		data.setOccupation(occ);
	}

	@Override
	public float getCenterX() {
		return (this.xhigh + this.xlow) / 2;
	}

	@Override
	public float getCenterY() {
		return (this.yhigh + this.ylow) / 2;
	}
	
	@Override
	public String toString(){
		String coordinate = "";
		if(this.xlow == this.xhigh && this.ylow == this.yhigh) {
			coordinate = "(" + this.xlow + "," + this.ylow + ")";
		} else {
			coordinate = "(" + this.xlow + "," + this.ylow + ") to (" + this.xhigh + "," + this.yhigh + ")";
		}
		
		StringBuilder s = new StringBuilder();
		s.append("id = " + this.index);
		s.append(", ");
		s.append("node " + this.node.toString());
		s.append(", ");
		s.append(coordinate);
//		s.append(String.format("basecost = %.2e", this.base_cost));
//		s.append(", ");
//		s.append(String.format("capacity = %d", Routable.capacity));
//		s.append(", ");
//		s.append(String.format("occupation = %d", this.rnodeData.getOccupation()));
//		s.append(", ");
//		s.append(String.format("num_unique_sources = %d", this.rnodeData.numUniqueSources()));
//		s.append(", ");
//		s.append(String.format("num_unique_parents = %d", this.rnodeData.numUniqueParents()));
//		s.append(", ");
//		s.append(String.format("level = %d", this.rnodeData.getLevel()));
		s.append(", ");
		s.append(String.format("type = %s", this.type));
		
		return s.toString();
	}
	
	@Override
	public int hashCode(){
		return this.index;
	}

	@Override
	public float getManhattanD() {
		float md = 0;
		if(this.rnodeData.getPrev() != null){
			md = Math.abs(this.rnodeData.getPrev().getCenterX() - this.getCenterX()) + Math.abs(this.rnodeData.getPrev().getCenterY() - this.getCenterY());
		}
		return md;
	}

	
	public boolean isInBoundingBoxLimit(Connection con) {		
		return this.xlow < con.net.x_max_b && this.xhigh > con.net.x_min_b && this.ylow < con.net.y_max_b && this.yhigh > con.net.y_min_b;
	}

	public Node getNode() {
		return this.node;
	}

	@Override
	public boolean isTarget() {
		return this.target;
	}

	@Override
	public void setTarget(boolean isTarget) {
		this.target = isTarget;	
	}
	
}
