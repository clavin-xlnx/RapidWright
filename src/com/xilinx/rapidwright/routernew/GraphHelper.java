package com.xilinx.rapidwright.routernew;

import java.util.*;

public class GraphHelper{
	private List<Routable> vertices;
	private Map<Routable, Set<Routable>> routableEdges;
	Map<Routable, Boolean> visited;
	Map<Routable, Boolean> stack;
	
	Routable backEdgeStart;
	Routable backEdgeEnd;

	boolean debug = false;
	
	public GraphHelper(){
		
	}
		
	public boolean isCyclic(Netplus netp){
		this.vertices = new ArrayList<>();
		this.routableEdges = new HashMap<>();
		this.visited = new HashMap<>();
		this.stack = new HashMap<>();
		
		this.buildGraph(netp);
		
//		if(netp.getNet().getName().equals("ipr[9]"))this.debug = true;
		
		return this.isCyclic(this.vertices, this.routableEdges, this.visited, this.stack);
	}
	public void buildGraph(Netplus netp){
		for(Connection c:netp.getConnection()){
			this.addVertices(c);
			int vertexSize = c.rnodes.size();
			for(int i = vertexSize - 1; i > 0; i--){
				Routable fisrt = c.rnodes.get(i);
				Routable second = c.rnodes.get(i - 1);
				
				if(!routableEdges.containsKey(fisrt)){
					Set<Routable> redges = new HashSet<>();
					redges.add(second);
					this.routableEdges.put(fisrt, redges);
				}else{
					Set<Routable> redges = this.routableEdges.get(fisrt);
					redges.add(second);
					this.routableEdges.put(fisrt, redges);
				}
			}
		}
	}
	
	public void addVertices(Connection c){
		for(int i = c.rnodes.size() - 1; i >= 0; i-- ){
			Routable rn = c.rnodes.get(i);
			if(!this.vertices.contains(rn)){
				this.vertices.add(rn);
				this.visited.put(rn, false);
				this.stack.put(rn, false);
			}
		}
	}
	
	public boolean isCyclicKernel(Routable rn, Map<Routable, Boolean> visited, Map<Routable, Boolean> stack){
		if(stack.get(rn)){
			if(this.debug) this.printlnInfo("rn stack true " + rn.toString());
			return true;
		}
		if(visited.get(rn)){
			return false;
		}
		visited.put(rn, true);
		stack.put(rn, true);
		
		if(this.routableEdges.get(rn) != null){
			for(Routable child:this.routableEdges.get(rn)){
				
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
	
	public boolean isCyclic(List<Routable> vertices, 
			Map<Routable, Set<Routable>> routableEdges,
			Map<Routable, Boolean> visited,
			Map<Routable, Boolean> stack){
		
		for(Routable rn:this.vertices){
			if(this.debug) this.printlnInfo("starting from rn " + rn.toString());
			if(this.isCyclicKernel(rn, this.visited, this.stack))
				return true;
			}
		return false;
	}
	
	public void cutOffCycles(Netplus netp){
		boolean cycleExists = this.isCyclic(this.vertices, this.routableEdges, this.visited, this.stack);
		while(cycleExists){
			//TODO to store info of the back edge
//			System.out.println("start " + this.backEdgeStart.toString() + ", end" + this.backEdgeEnd.toString());
			// remove the back edge by removing the end from the adj list of the start
			for(Routable rn:this.routableEdges.keySet()){
				if(rn.equals(this.backEdgeStart)){
					this.routableEdges.get(rn).remove(backEdgeEnd);
					break;
				}
			}
			
			// check if the start of the back edge has any fanouts
			// if no, remove the redundant routable and remove edges related to it
			if(this.routableEdges.get(this.backEdgeStart).size() == 0){
				
				this.removeRedudantRoutable(this.backEdgeStart);
				
				for(Routable rn:this.routableEdges.keySet()){
					if(this.routableEdges.get(rn).contains(this.backEdgeStart)){
						this.routableEdges.get(rn).remove(backEdgeStart);
					}
				}
			}
			
			// reset the info of visited and stack for each routable, preparing for the next check if the graph is cyclic
			this.resetVistedAndStack();
			this.debug = false;
			cycleExists = this.isCyclic(this.vertices, this.routableEdges, this.visited, this.stack);	
			System.out.println(cycleExists);
		}
		
		this.rebuildPathsOfNetCons(netp);
	}
	
	public void removeRedudantRoutable(Routable backEdgeStart){
		if(this.debug) System.out.println("before removing " + this.vertices.size() + ", " + this.routableEdges.size() + ", " + this.visited.size() + ", " +this.stack.size());
		this.vertices.remove(backEdgeStart);
		this.routableEdges.remove(backEdgeStart);
		this.visited.remove(backEdgeStart);
		this.stack.remove(backEdgeStart);
		if(this.debug) System.out.println("after removing " + this.vertices.size() + ", " + this.routableEdges.size() + ", " + this.visited.size() + ", " +this.stack.size());
		
	}
	
	public void resetVistedAndStack(){
		for(Routable rn:this.vertices){
			this.resetVisited(rn);
			this.resetStack(rn);
		}
	}
	
	public void resetVisited(Routable rn){
		this.visited.put(rn, false);
	}
	public void resetStack(Routable rn){
		this.stack.put(rn, false);
	}
	
	public void rebuildPathsOfNetCons(Netplus netp){
		// TODO identify connections that have been impacted
		for(Connection c:netp.getConnection()){
			List<Routable> path = this.findPathBetweenTwoVertices(c.getSourceRNode(), c.getSinkRNode());
			c.rnodes.clear();
			for(int i = 0; i < path.size(); i++){
				c.rnodes.add(path.get(i));
				if(this.debug) this.printlnInfo(path.get(i).toString());
			}
		}
	}
	
	public List<Routable> findPathBetweenTwoVertices(Routable source, Routable sink){
		List<Routable> path = new ArrayList<>();
		// set visited value of each vertex as false
		for(Routable rn:this.vertices){
			this.resetVisited(rn);
		}
		if(this.debug) System.out.println("sink " + sink.toString());
		Queue<Routable> queue = new LinkedList<>();
		Map<Routable, Routable> childParent = new HashMap<>();
		this.visited.put(source, true);
		queue.add(source);
		while(!queue.isEmpty()){
			Routable rn = queue.poll();
			if(rn.equals(sink)){
				//TODO trace back to build the path
				Routable tmp = rn;
				while(tmp != null){
					path.add(tmp);
					tmp = childParent.get(tmp);
				}
			}
			if(this.debug) System.out.println(rn.toString());
			if(this.debug) System.out.println(this.routableEdges.containsKey(rn));
			if(this.routableEdges.containsKey(rn)){// SINKRR is not contained
				for(Routable child:this.routableEdges.get(rn)){
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
	
	

