package com.xilinx.rapidwright.routernew;

import java.util.*;

import com.xilinx.rapidwright.device.Node;

public class GraphHelper{
	private List<NodeWithCriticality> vertices;
	private Map<NodeWithCriticality, List<NodeWithCriticality>> nodeEdges;
	private Map<Node, NodeWithCriticality> nodeMap;
	
	NodeWithCriticality backEdgeStart;
	NodeWithCriticality backEdgeEnd;

	boolean debug = false;
	
	public GraphHelper(){
		this.vertices = new ArrayList<>();
		this.nodeMap = new HashMap<>();
		this.nodeEdges = new HashMap<>();
	}
		
	public boolean isCyclic(Netplus netp){
		
		this.buildGraph(netp);
		
		return this.isCyclic(this.vertices, this.nodeEdges);
	}
	public void buildGraph(Netplus netp){	
		for(Connection c:netp.getConnection()){
			int vertexSize = c.nodes.size();
			for(int i = vertexSize - 1; i > 0; i--){
				Node fisrt = c.nodes.get(i);
				Node second = c.nodes.get(i - 1);
				
				NodeWithCriticality newFirst = this.nodeMap.containsKey(fisrt) ? this.nodeMap.get(fisrt) : new NodeWithCriticality(fisrt);
				NodeWithCriticality newSecond = this.nodeMap.containsKey(second) ? this.nodeMap.get(second) : new NodeWithCriticality(second);
				this.nodeMap.put(fisrt, newFirst);
				this.nodeMap.put(second, newSecond);
				newFirst.setCriticality(c.getCriticality());
				newSecond.setCriticality(c.getCriticality());
				
				this.addVertices(newFirst);
				this.addVertices(newSecond);
				if(i == 1) newSecond.setSink(true);
				
				List<NodeWithCriticality> redges;
				if(!this.nodeEdges.containsKey(newFirst)){
					redges = new ArrayList<>();				
				}else{
					redges = this.nodeEdges.get(newFirst);
				}
				if(!redges.contains(newSecond))
					redges.add(newSecond);
				Collections.sort(redges, NodeWithCriticality);
				this.nodeEdges.put(newFirst, redges);
			}
		}
		
//		for(NodeWithCriticality n:this.vertices){
//			System.out.println(this.nodeEdges.get(n));
//		}
	}
	
	public void addVertices(NodeWithCriticality nc){
		if(!this.vertices.contains(nc)){
			this.vertices.add(nc);
		}
	}
	
	public boolean isCyclicKernel(NodeWithCriticality rn){
		if(rn.isStacked()){
			if(this.debug) this.printlnInfo("rn stack true " + rn.toString());
			return true;
		}
		if(rn.isVisited()){
			return false;
		}
		
		rn.setVisited(true);
		rn.setStacked(true);
		
		if(this.nodeEdges.get(rn) != null){
			for(NodeWithCriticality child:this.nodeEdges.get(rn)){
				
				if(this.debug) this.printlnInfo("rn " + rn.toString() + ",  child " + child.toString());
				this.backEdgeStart = rn;
				if(this.debug) this.printlnInfo("back edge start " + this.backEdgeStart.toString());
				this.backEdgeEnd = child;
				if(this.debug) this.printlnInfo("back edge end " + this.backEdgeEnd.toString());
				
				if(this.isCyclicKernel(child)){	
					return true;
				}
			}
		}
		
		rn.setStacked(false);
		return false;
	}
	
	public boolean isCyclic(List<NodeWithCriticality> vertices, 
			Map<NodeWithCriticality, List<NodeWithCriticality>> routableEdges){
		
		for(NodeWithCriticality rn:this.vertices){
			if(this.debug) this.printlnInfo("starting from rn " + rn.toString());
			if(this.isCyclicKernel(rn))
				return true;
			}
		return false;
	}
	
	public void cutOffIllegalEdges(Netplus netp, boolean isCyclic){
		boolean cycleExists = isCyclic;
		while(cycleExists){
			// to store info of the back edge
			// remove the back edge by removing the end from the adj list of the start
			for(NodeWithCriticality rn:this.nodeEdges.keySet()){
				if(rn.equals(this.backEdgeStart)){
					this.nodeEdges.get(rn).remove(backEdgeEnd);
					break;
				}
			}
			
			// check if the start of the back edge has any fanouts
			// if no, remove the redundant Node and remove edges related to it
			if(this.nodeEdges.get(this.backEdgeStart).size() == 0){		
				this.removeRedudantRoutable(this.backEdgeStart);			
				for(NodeWithCriticality rn:this.nodeEdges.keySet()){
					if(this.nodeEdges.get(rn).contains(this.backEdgeStart)){
						this.nodeEdges.get(rn).remove(backEdgeStart);
					}
				}
			}
			
			// reset the info of visited and stack for each routable, preparing for the next check if the graph is cyclic
			this.resetVistedAndStack();
			this.debug = false;
			cycleExists = this.isCyclic(this.vertices, this.nodeEdges);
		}
		
		this.mergingMultiFaninPaths();
		//to restore paths of connections
		this.restoringConnectionPaths(netp);
	}
	
	public void restoringConnectionPaths(Netplus netp){
		for(Connection c:netp.getConnection()){
			c.nodes.clear();
			NodeWithCriticality nc = this.nodeMap.get(c.getSinkRNode().getNode());
			while(nc != null){
				c.addNode(nc.getNode());
				nc = nc.getDriver();
			}
		}
	}
	
	public void mergingMultiFaninPaths(){
		//by setting only one driver with the maximum criticality to each non-source node, the multiple fan-in problem will be solved
		for(NodeWithCriticality n : this.vertices){
			List<NodeWithCriticality> nextNCs = this.nodeEdges.get(n);
			if(nextNCs != null){
				for(NodeWithCriticality nextn : nextNCs){
					nextn.setDriver(n);
				}
			}
		}
	}
	
	//removing redudant edges
	public void removeRedudantRoutable(NodeWithCriticality backEdgeStart){
		if(this.debug) System.out.println("before removing " + this.vertices.size() + ", " + this.nodeEdges.size());
		this.vertices.remove(backEdgeStart);
		this.nodeEdges.remove(backEdgeStart);
		if(this.debug) System.out.println("after removing " + this.vertices.size() + ", " + this.nodeEdges.size());
		
	}
	
	public void resetVistedAndStack(){
		for(NodeWithCriticality rn:this.vertices){
			rn.setVisited(false);
			rn.setStacked(false);
		}
	}
	
	public static Comparator<NodeWithCriticality> NodeWithCriticality = new Comparator<NodeWithCriticality>() {
    	@Override
    	public int compare(NodeWithCriticality a, NodeWithCriticality b) {
    		if(a.getCriticality() < b.getCriticality()){
    			return 1;
    		}else if(Math.abs(a.getCriticality() - b.getCriticality()) < 1e-6) {
    			if(a.hashCode() > b.hashCode()){
    				return 1;
    			}else if(a.hashCode() < b.hashCode()){
    				return -1;
    			}else{
    				if(a != b) System.out.println("Failure: Error while comparing 2 NodeWithCriticality. HashCode of Two Connections was identical");
    				return 0;
    			}
    		}else{
    			return -1;
    		}
    	}
    };
	
	private void printlnInfo(String s){
		System.out.println(s);
	}
	
	class NodeWithCriticality{
		private Node node;
		private float criticality;
		private boolean isSink;
		private NodeWithCriticality driver;
		private boolean visited;
		private boolean stacked;
		
		public NodeWithCriticality(Node node){
			this.node = node;
			this.criticality = -1;//unset
			this.isSink = false;
			this.driver = null;
			this.visited = false;
			this.stacked = false;
		}

		public boolean isVisited() {
			return visited;
		}

		public void setVisited(boolean visited) {
			this.visited = visited;
		}

		public boolean isStacked() {
			return stacked;
		}

		public void setStacked(boolean stacked) {
			this.stacked = stacked;
		}

		public NodeWithCriticality getDriver() {
			return this.driver;
		}
		
		public void setDriver(NodeWithCriticality driver) {
			if(this.driver == null){
				this.driver = driver;
			}else if(driver.getCriticality() > this.driver.getCriticality()){
				this.driver = driver;
			}
		}
		
		public boolean isSink() {
			return isSink;
		}

		public void setSink(boolean isSink) {
			this.isSink = isSink;
		}

		public float getCriticality() {
			return criticality;
		}
		
		public Node getNode(){
			return this.node;
		}
		
		public void setCriticality(float criticality) {
			if(Math.abs(this.criticality - (-1)) < 1e-6)
				this.criticality = criticality;
			else{
				if(this.criticality < criticality) this.criticality = criticality;
			}
		}
		
		@Override
		public int hashCode(){
			return this.node.hashCode();
		}
		
		@Override
		public String toString(){
			return this.node.toString() + ", criti = " + this.criticality + ", sink? " + this.isSink;
		}
	}
	
}
	
	

