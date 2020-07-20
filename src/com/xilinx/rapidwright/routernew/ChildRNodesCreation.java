package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.TimingGroup;

public class ChildRNodesCreation{	
	public Map<String, RNode<Wire>> rnodesCreatedWire;
	public Map<String, RNode<Node>> rnodesCreatedNode;
	public Map<String, RNode<TimingGroup>> rnodesCreatedTimingGroup;
	public float base_cost_fac;
	
	public boolean debug = false;
	
	public ChildRNodesCreation(Map<String, RNode<Wire>> rnodesWireCreated,
			Map<String, RNode<Node>> rnodesNodeCreated,
			Map<String, RNode<TimingGroup>> rnodesTGCreated,
			float base_cost_fac){
		this.rnodesCreatedWire = rnodesWireCreated;
		this.rnodesCreatedNode = rnodesNodeCreated;
		this.rnodesCreatedTimingGroup = rnodesTGCreated;
		this.base_cost_fac = base_cost_fac;
	}
	
	//TODO check
	public int timingGroupBased(RNode<TimingGroup> rnode, int globalRNodeIndex){
		TimingGroup rnodeNode = rnode.getTimingGroup();
		List<RNode<TimingGroup>> childRNodes = new ArrayList<>();
		for(TimingGroup timingGroup:rnodeNode.getNextTimingGroups()){
			RNode<TimingGroup> childRNode;
			String key = timingGroup.toString();
			if(!this.rnodesCreatedTimingGroup.containsKey(key)){
				childRNode = new RNode<TimingGroup>(globalRNodeIndex, timingGroup);
				childRNode.setBaseCost(this.base_cost_fac);
				globalRNodeIndex++;
				childRNodes.add(childRNode);
				this.rnodesCreatedTimingGroup.put(key, childRNode);
			}else{
				childRNodes.add(this.rnodesCreatedTimingGroup.get(key));
			}
		}
		rnode.setChildren(childRNodes);
		
		return globalRNodeIndex;
	}
	
	public int nodeBased(RNode<Node> rnode, int globalRNodeIndex){
		Node rnodeNode = rnode.getNode();
		List<RNode<Node>> childRNodes = new ArrayList<>();
		for(Node node:rnodeNode.getAllDownhillNodes()){
			RNode<Node> childRNode;
			String key = node.toString();
			if(!this.rnodesCreatedNode.containsKey(key)){
				childRNode = new RNode<Node>(globalRNodeIndex, node);
				childRNode.setBaseCost(this.base_cost_fac);
				globalRNodeIndex++;
				childRNodes.add(childRNode);
				this.rnodesCreatedNode.put(key, childRNode);
			}else{
				childRNodes.add(this.rnodesCreatedNode.get(key));
			}
		}
		rnode.setChildren(childRNodes);
		
		return globalRNodeIndex;
	}
	
	public int wireBased(RNode<Wire> rnode, int globalRNodeIndex){	
		//set childRNodes of rnode, and avoid creating RNodes that already exist
		Tile rnodeTile = rnode.getTile();
		List<Wire> wires = rnodeTile.getWireConnections(rnode.getWire());
		List<RNode<Wire>> childRNodes = new ArrayList<>();
		
		/*//check childRNodes of a source RNode, to see if they are in a unique tile
		if(rnode.type == RoutableType.SOURCERNODE){
			if(wires.size() != 1) System.out.println(wires.size());
			Set<String> downhillINTtileNames = new HashSet<>();
			for(Wire wire:wires){
//				if(wire.getTile().getName().contains("INT_"))
					downhillINTtileNames.add(wire.getTile().getName());
			}
			if(downhillINTtileNames.size() != 1){
				System.out.println("currSourceRNode " + rnode.getTile().getName() + ": ");
				for(String tilename:downhillINTtileNames){
					System.out.println(tilename);
				}
				System.out.println();
			}
		}*/
		
		for(Wire wire:wires){
			RNode<Wire> childRNode;
			String key = wire.getTile().getName() + "/" + wire.getWireIndex();
			if(!this.rnodesCreatedWire.containsKey(key)){//TODO use Wire as the key?
				childRNode = new RNode<Wire>(globalRNodeIndex, wire.getTile(), wire.getWireIndex());
				childRNode.setBaseCost(this.base_cost_fac);
				globalRNodeIndex++;
				childRNodes.add(childRNode);
				this.rnodesCreatedWire.put(key, childRNode);
			}else{
				childRNodes.add(this.rnodesCreatedWire.get(key));
			}
		}
		rnode.setChildren(childRNodes);
		
		return globalRNodeIndex;
	}
	
	public void printInfo(String s){
		System.out.println("  --- " + s + " --- ");
	}
}
