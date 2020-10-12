package com.xilinx.rapidwright.timing;


import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.routernew.CountingSet;
import com.xilinx.rapidwright.routernew.Routable;

public class NodeWithFaninInfo extends Node{

    //Node.toString

    CountingSet<SitePinInst> sources;
    CountingSet<Routable> parents;
    float pres_cost;
    float acc_cost;
    public Set<Integer> entryHolders;
    
    static Map<Node, NodeWithFaninInfo> nodePairs;
    
    static{
    	nodePairs = new HashMap<>();
    }
    
    // Make sure you have setter/acccessor
    public void  setPresCost(float v) {pres_cost = v;}
    public float getPresCost()           {return pres_cost;}

    //  To convert Node to NodeWithFaninInfo.
    //	from: Node              n = axyz();
    //	to:   NodeWithFaninInfo n = NodeWithFaninInfo.create(axyz());
    public static NodeWithFaninInfo create(Node node){
    	//merge() is not desired 
    	if(nodePairs.containsKey(node)){
        	return nodePairs.get(node);
        }else{
        	Wire wire = node.getAllWiresInNode()[0];
        	NodeWithFaninInfo nodeWithFaninInfo = new NodeWithFaninInfo(wire);
        	nodePairs.put(node, nodeWithFaninInfo);
        	return nodeWithFaninInfo;
        }
    }
    
    public void initialize(){
    	this.pres_cost = 1f;
    	this.acc_cost = 1f;
    	this.sources = null;
    	this.parents = null;
    }
    
    public NodeWithFaninInfo(Wire wire){
        super(wire);
        this.entryHolders = new HashSet<>();
    }
    
    public void setAccCost(float accCost){
    	this.acc_cost = accCost;
    }
    
    public float getAccCost(){
    	return this.acc_cost;
    }
    
    public CountingSet<SitePinInst> getSourcesSet(){
    	return this.sources;
    }
    
    public CountingSet<Routable> getParentsSet(){
    	return this.parents;
    }
    
    public void removeSource(SitePinInst source){
    	this.sources.remove(source);
    	if(this.sources.isEmpty()){
    		this.sources = null;
    	}
    }
    
    public void removeParent(Routable parent){
    	this.parents.remove(parent);
    	if(this.parents.isEmpty()) {
			this.parents = null;
		}
    }
    
    public void addSource(SitePinInst source){
    	if(this.sources == null){
    		this.sources = new CountingSet<>();
    	}
//    	}else{
//    		if(this.sources.uniqueSize() > 1) System.out.println("source set exists and unique size = " + this.sources.uniqueSize());
//    	}
    	this.sources.add(source);
    }
    
    public void addParent(Routable parent){
    	if(this.parents == null){
    		this.parents = new CountingSet<>();
    	}
    	this.parents.add(parent);
    }
    
    public boolean isUsed(){
    	/*boolean check = false;
    	if(this.toString().equals("INT_X9Y96/INT_NODE_IMUX_17_INT_OUT0"))
    		check = true;*/
    	if(this.sources == null){
//    		if(check) System.out.println("INT_X9Y96/INT_NODE_IMUX_17_INT_OUT0 null sources");
    		return false;
    	}else if(this.sources.uniqueSize() > 0){
    		return true;
    	}
    	return false;
    }
    
    public boolean isOverUsed(){
//    	if()
    	if(this.sources == null){
    		return false;
    	}else if(this.sources.uniqueSize() > Routable.capacity){
    		return true;
    	}
    	return false;
    }
    
    public boolean hasMultiFanin(){
    	if(this.parents == null){
    		return false;
    	}else if(this.parents.uniqueSize() > 1){
    		return true;
    	}
    	return false;
    }
    
    public int getOcc(){
    	if(this.sources == null) {
			return 0;
		}
		return this.sources.uniqueSize();
    }
}

