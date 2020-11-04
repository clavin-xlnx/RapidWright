package com.xilinx.rapidwright.routernew;

import java.util.*;

import com.xilinx.rapidwright.device.Node;

public class GraphHelper{
	private List<Node> vertices;
	private Map<Node, Set<Node>> nodeEdges;
	Map<Node, Boolean> visited;
	Map<Node, Boolean> stack;
	
	Node backEdgeStart;
	Node backEdgeEnd;

	boolean debug = false;
	
	public GraphHelper(){
		
	}
		
	public boolean isCyclic(Netplus netp){
		this.vertices = new ArrayList<>();
		this.nodeEdges = new HashMap<>();
		this.visited = new HashMap<>();
		this.stack = new HashMap<>();
		
		this.buildGraph(netp);
		
//		if(netp.getNet().getName().equals("ipr[9]"))this.debug = true;
		
		return this.isCyclic(this.vertices, this.nodeEdges, this.visited, this.stack);
	}
	public void buildGraph(Netplus netp){
		for(Connection c:netp.getConnection()){
			this.addVertices(c);
			int vertexSize = c.nodes.size();
			for(int i = vertexSize - 1; i > 0; i--){
				Node fisrt = c.nodes.get(i);
				Node second = c.nodes.get(i - 1);
				
				if(!nodeEdges.containsKey(fisrt)){
					Set<Node> redges = new HashSet<>();
					redges.add(second);
					this.nodeEdges.put(fisrt, redges);
				}else{
					Set<Node> redges = this.nodeEdges.get(fisrt);
					redges.add(second);
					this.nodeEdges.put(fisrt, redges);
				}
			}
		}
	}
	
	public void addVertices(Connection c){
		for(int i = c.nodes.size() - 1; i >= 0; i-- ){
			Node rn = c.nodes.get(i);
			if(!this.vertices.contains(rn)){
				this.vertices.add(rn);
				this.visited.put(rn, false);
				this.stack.put(rn, false);
			}
		}
	}
	
	public boolean isCyclicKernel(Node rn, Map<Node, Boolean> visited, Map<Node, Boolean> stack){
		if(stack.get(rn)){
			if(this.debug) this.printlnInfo("rn stack true " + rn.toString());
			return true;
		}
		if(visited.get(rn)){
			return false;
		}
		visited.put(rn, true);
		stack.put(rn, true);
		
		if(this.nodeEdges.get(rn) != null){
			for(Node child:this.nodeEdges.get(rn)){
				
				if(this.debug) this.printlnInfo("rn " + rn.toString() + ",  child " + child.toString());
				this.backEdgeStart = rn;
				if(this.debug) this.printlnInfo("back edge start " + this.backEdgeStart.toString());
				this.backEdgeEnd = child;
				if(this.debug) this.printlnInfo("back edge end " + this.backEdgeEnd.toString());
				
				if(this.isCyclicKernel(child, visited, stack)){	
					return true;
				}
			}
		}
		
		stack.put(rn, false);
		return false;
	}
	
	public boolean isCyclic(List<Node> vertices, 
			Map<Node, Set<Node>> routableEdges,
			Map<Node, Boolean> visited,
			Map<Node, Boolean> stack){
		
		for(Node rn:this.vertices){
			if(this.debug) this.printlnInfo("starting from rn " + rn.toString());
			if(this.isCyclicKernel(rn, this.visited, this.stack))
				return true;
			}
		return false;
	}
	
	public void cutOffCycles(Netplus netp){
		boolean cycleExists = true;
		while(cycleExists){
			//TODO to store info of the back edge
//			System.out.println("start " + this.backEdgeStart.toString() + ", end" + this.backEdgeEnd.toString());
			// remove the back edge by removing the end from the adj list of the start
			for(Node rn:this.nodeEdges.keySet()){
				if(rn.equals(this.backEdgeStart)){
					this.nodeEdges.get(rn).remove(backEdgeEnd);
					break;
				}
			}
			
			// check if the start of the back edge has any fanouts
			// if no, remove the redundant Node and remove edges related to it
			if(this.nodeEdges.get(this.backEdgeStart).size() == 0){
				
				this.removeRedudantRoutable(this.backEdgeStart);
				
				for(Node rn:this.nodeEdges.keySet()){
					if(this.nodeEdges.get(rn).contains(this.backEdgeStart)){
						this.nodeEdges.get(rn).remove(backEdgeStart);
					}
				}
			}
			
			// reset the info of visited and stack for each routable, preparing for the next check if the graph is cyclic
			this.resetVistedAndStack();
			this.debug = false;
			cycleExists = this.isCyclic(this.vertices, this.nodeEdges, this.visited, this.stack);
		}
		
		this.rebuildPathsOfNetCons(netp);
	}
	
	public void removeRedudantRoutable(Node backEdgeStart){
		if(this.debug) System.out.println("before removing " + this.vertices.size() + ", " + this.nodeEdges.size() + ", " + this.visited.size() + ", " +this.stack.size());
		this.vertices.remove(backEdgeStart);
		this.nodeEdges.remove(backEdgeStart);
		this.visited.remove(backEdgeStart);
		this.stack.remove(backEdgeStart);
		if(this.debug) System.out.println("after removing " + this.vertices.size() + ", " + this.nodeEdges.size() + ", " + this.visited.size() + ", " +this.stack.size());
		
	}
	
	public void resetVistedAndStack(){
		for(Node rn:this.vertices){
			this.resetVisited(rn);
			this.resetStack(rn);
		}
	}
	
	public void resetVisited(Node rn){
		this.visited.put(rn, false);
	}
	public void resetStack(Node rn){
		this.stack.put(rn, false);
	}
	
	public void rebuildPathsOfNetCons(Netplus netp){
		// TODO identify connections that have been impacted
		for(Connection c:netp.getConnection()){
			List<Node> path = this.findPathBetweenTwoVertices(c.getSourceRNode().getNode(), c.getSinkRNode().getNode());
			c.nodes.clear();
			for(int i = 0; i < path.size(); i++){
				c.nodes.add(path.get(i));
				if(this.debug) this.printlnInfo(path.get(i).toString());
			}
		}
	}
	
	public List<Node> findPathBetweenTwoVertices(Node source, Node sink){
		List<Node> path = new ArrayList<>();
		// set visited value of each vertex as false
		for(Node rn:this.vertices){
			this.resetVisited(rn);
		}
		if(this.debug) System.out.println("sink " + sink.toString());
		Queue<Node> queue = new LinkedList<>();
		Map<Node, Node> childParent = new HashMap<>();
		this.visited.put(source, true);
		queue.add(source);
		while(!queue.isEmpty()){
			Node rn = queue.poll();
			if(rn.equals(sink)){
				//TODO trace back to build the path
				Node tmp = rn;
				while(tmp != null){
					path.add(tmp);
					tmp = childParent.get(tmp);
				}
			}
			if(this.debug) System.out.println(rn.toString());
			if(this.debug) System.out.println(this.nodeEdges.containsKey(rn));
			if(this.nodeEdges.containsKey(rn)){// SINKRR is not contained
				for(Node child:this.nodeEdges.get(rn)){
					if(!this.visited.get(child)){
						this.visited.put(child, true);
						childParent.put(child, rn);
						queue.add(child);
					}
				}
			}
		}
		
		return path;
	}
	
	private void printlnInfo(String s){
		System.out.println(s);
	}
	
}
	
	

