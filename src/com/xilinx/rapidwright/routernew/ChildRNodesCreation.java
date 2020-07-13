package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.TimingGroup;

public class ChildRNodesCreation{	
	public Map<String, RNode<Wire>> rnodesCreatedWire;
	public Map<String, RNode<Node>> rnodesCreatedNode;
	public Map<String, RNode<TimingGroup>> rnodesCreatedTimingGroup;
	
	public boolean debug = false;
	
	public ChildRNodesCreation(Map<String, RNode<Wire>> rnodesCreated){
		this.rnodesCreatedWire = rnodesCreated;	
	}
	
	public ChildRNodesCreation(Map<String, RNode<Node>> rnodesCreated, RoutingGranularityOpt opt){
		if(opt == RoutingGranularityOpt.NODE){
			this.rnodesCreatedNode = rnodesCreated;
		}
	}
	//TODO remove previous two constructors
	public ChildRNodesCreation(Map<String, RNode<Wire>> rnodesWireCreated,
			Map<String, RNode<Node>> rnodesNodeCreated,
			Map<String, RNode<TimingGroup>> rnodesTGCreated,
			RoutingGranularityOpt opt){
		if(opt == RoutingGranularityOpt.NODE){
			this.rnodesCreatedWire = rnodesWireCreated;
		}else if(opt == RoutingGranularityOpt.NODE){
			this.rnodesCreatedNode = rnodesNodeCreated;
		}else{
			this.rnodesCreatedTimingGroup = rnodesTGCreated;
		}
	}
	
	//TODO
	public int timingGroupBased(RNode<TimingGroup> rnode, int globalRNodeIndex){
		TimingGroup rnodeNode = rnode.getTimingGroup();
		List<RNode<TimingGroup>> childRNodes = new ArrayList<>();
		for(TimingGroup timingGroup:rnodeNode.getNextTimingGroups()){
			RNode<TimingGroup> childRNode;
			String key = timingGroup.toString();
			if(!this.rnodesCreatedTimingGroup.containsKey(key)){
				childRNode = new RNode<TimingGroup>(globalRNodeIndex, timingGroup);
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
		for(Wire wire:wires){
			RNode<Wire> childRNode;
			String key = wire.getTile().getName() + "/" + wire.getWireIndex();
			if(!this.rnodesCreatedWire.containsKey(key)){//TODO use Wire as the key?
				childRNode = new RNode<Wire>(globalRNodeIndex, wire.getTile(), wire.getWireIndex());
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
