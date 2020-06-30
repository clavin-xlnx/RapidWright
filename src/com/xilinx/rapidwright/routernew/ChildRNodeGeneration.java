package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;

public class ChildRNodeGeneration {	
	public Map<String, RNode<Wire>> rnodesCreated;
	public Map<String, RNode<Node>> rnodesCreatedN;
	public Map<String, RNode<Wire>> rnodesCreatedTG;
	
	public boolean debug = false;
	
	public ChildRNodeGeneration(Map<String, RNode<Wire>> rnodesCreated){
		this.rnodesCreated = rnodesCreated;	
	}
	
	public ChildRNodeGeneration(Map<String, RNode<Node>> rnodesCreated, ExpanGranularityOpt optNode){
		this.rnodesCreatedN = rnodesCreated;
	}
	
	public void nodeBased(RNode<Node> rnode, Connection<Node> con){
		//TODO
		
	}
	
	public void wireBased(RNode<Wire> rnode, Connection<Wire> con){	
		//set childRNodes of rnode, and avoid creating RNodes that already exist
		Tile rnodeTile = rnode.getTile();
		List<Wire> wires = rnodeTile.getWireConnections(rnode.getWire());
		List<RNode<Wire>> childRNodes = new ArrayList<>();
		for(Wire wire:wires){
			RNode<Wire> childRNode;
			String name = wire.getTile().getName() + "/" + wire.getWireIndex();//use Wire wire as the key instead?
			if(!this.rnodesCreated.containsKey(name)){//TODO use Wire as the key?
				childRNode = new RNode<Wire>(wire.getTile(), wire.getWireIndex(), 1);
				childRNodes.add(childRNode);
				this.rnodesCreated.put(name, childRNode);
			}else{
				childRNodes.add(this.rnodesCreated.get(name));
			}
		}
		rnode.setChildren(childRNodes);	
	}
	
	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}
}
