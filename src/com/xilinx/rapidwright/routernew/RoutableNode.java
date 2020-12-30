package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.util.Pair;

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
	
	public RoutableNode(int index, Node node, RoutableType type){
		this.index = index;
		this.type = type;
		this.node = node;
		this.rnodeData = new RoutableData(this.index);
		this.childrenSet = false;
		this.target = false;
		this.setXY();
	}
	
	public Pair<Integer, Long> setChildren(Connection c, int globalIndex, float base_cost_fac, Map<Node, RoutableNode> createdRoutable, 
			RouteThruHelper routethruHelper, RouterTimer timer, long callingOfGetNextRoutable){
		
		timer.getNextRoutable.start();
		List<Node> allDownHillNodes = this.node.getAllDownhillNodes();
		timer.getNextRoutable.finish();
		
		timer.getNextDummy.start();
		timer.getNextDummy.finish();
		
		timer.addChildren.start();
		callingOfGetNextRoutable++;
		this.children = new ArrayList<>();
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
		timer.addChildren.finish();
		
		return new Pair(globalIndex, callingOfGetNextRoutable);
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
			base_cost = 1;
			
		}else if(this.type == RoutableType.SINKRR){//this is for faster maze expansion convergence to the sink
			base_cost = 0.95f;//virtually the same to the logic block input pin, since no alternative ipins are considered
		}else{
			base_cost = 1;
		}
	}

	@Override
	public boolean overUsed() {
		return Routable.capacity < this.getOccupancy();
	}
	
	@Override
	public boolean used(){
		return this.getOccupancy() > 0;
	}
	
	@Override
	public boolean illegal(){
		return Routable.capacity < this.rnodeData.numUniqueParents();
	}

	@Override
	public void setXY() {
		List<Short> xCoordinates = new ArrayList<>();
		List<Short> yCoordinates = new ArrayList<>();
		for(Wire w : this.node.getAllWiresInNode()){
			Tile tile = w.getTile();
			if(tile.getTileTypeEnum() == TileTypeEnum.INT){
				xCoordinates.add((short) w.getTile().getColumn());
				yCoordinates.add((short) w.getTile().getRow());
				
			}	
		}
		this.xlow = this.min(xCoordinates);
		this.xhigh = this.max(xCoordinates);
		this.ylow = this.min(yCoordinates);
		this.yhigh = this.max(yCoordinates);
	}
	
	public short max(List<Short> coordinates){
		short max = 0;
		for(short c:coordinates){
			if(c > max)
				max = c;
		}
		return max;
	}
	public short min(List<Short> coordinates){
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
		
		int occ = this.getOccupancy();
		int cap = Routable.capacity;
		
		if (occ < cap) {
			data.setPres_cost(1);
		} else {
			data.setPres_cost(1 + (occ - cap + 1) * pres_fac);
		}
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
		return this.xlow > con.net.x_min_b && this.xhigh < con.net.x_max_b && this.ylow > con.net.y_min_b && this.yhigh < con.net.y_max_b;
	}
	
	@Override
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

	@Override
	public RoutableType getRoutableType() {
		return this.type;
	}

	@Override
	public boolean isGlobal() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isBounce() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public int getOccupancy() {
		
		return this.rnodeData.getOccupancy();
	}

	@Override
	public float getPres_cost() {
		return this.rnodeData.getPres_cost();
	}

	@Override
	public void setPres_cost(float pres_cost) {	
		this.rnodeData.setPres_cost(pres_cost);
	}

	@Override
	public float getAcc_cost() {
		return this.rnodeData.getAcc_cost();
	}

	@Override
	public void setAcc_cost(float acc_cost) {
		this.rnodeData.setAcc_cost(acc_cost);	
	}

	@Override
	public float getDelay() {
		// TODO Auto-generated method stub
		return 0;
	}
	@Override
	public short getXmax() {
		return this.xhigh;
	}

	@Override
	public short getXmin() {
		return this.xlow;
	}

	@Override
	public short getYmax() {
		return this.yhigh;
	}

	@Override
	public short getYmin() {
		return this.ylow;
	}

	@Override
	public float getBase_cost() {
		return this.base_cost;
	}
	
}
