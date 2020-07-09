package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;

public class ChildRNodesCreation{	
	public Map<String, RNode<Wire>> rnodesCreatedWire;
	public Map<String, RNode<Node>> rnodesCreatedNode;
	public Map<String, RNode<Wire>> rnodesCreatedTimingGroup;
	
	public boolean debug = false;
	
	public ChildRNodesCreation(Map<String, RNode<Wire>> rnodesCreated){
		this.rnodesCreatedWire = rnodesCreated;	
	}
	
	public ChildRNodesCreation(Map<String, RNode<Node>> rnodesCreated, RoutingGranularityOpt opt){
		if(opt == RoutingGranularityOpt.NODE){
			this.rnodesCreatedNode = rnodesCreated;
		}
	}
	
	public void nodeBased(RNode<Node> rnode){
		//TODO
		Node rnodeNode = rnode.getNode();
		List<RNode<Node>> childRNodes = new ArrayList<>();
		for(Node node:rnodeNode.getAllDownhillNodes()){
			RNode<Node> childRNode;
			String key = node.toString();
			if(!this.rnodesCreatedNode.containsKey(key)){
				childRNode = new RNode<Node>(node, 1);
				childRNodes.add(childRNode);
				this.rnodesCreatedNode.put(key, childRNode);
			}else{
				childRNodes.add(this.rnodesCreatedNode.get(key));
			}
		}
		rnode.setChildren(childRNodes);	
	}
	
	public void wireBased(RNode<Wire> rnode){	
		//set childRNodes of rnode, and avoid creating RNodes that already exist
		Tile rnodeTile = rnode.getTile();
		List<Wire> wires = rnodeTile.getWireConnections(rnode.getWire());
		List<RNode<Wire>> childRNodes = new ArrayList<>();
		for(Wire wire:wires){
			RNode<Wire> childRNode;
			String key = wire.getTile().getName() + "/" + wire.getWireIndex();
			if(!this.rnodesCreatedWire.containsKey(key)){//TODO use Wire as the key?
				childRNode = new RNode<Wire>(wire.getTile(), wire.getWireIndex(), 1);
				childRNodes.add(childRNode);
				this.rnodesCreatedWire.put(key, childRNode);
			}else{
				childRNodes.add(this.rnodesCreatedWire.get(key));
			}
		}
		rnode.setChildren(childRNodes);	
	}
	
	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}
}
