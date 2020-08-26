package com.xilinx.rapidwright.routernew;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;

public class GraphVisualization extends JFrame{
	
	public void visualizeARoutingTree(Netplus net){
		
	}
	
	public class Graph{
		public List<Vertex> vertices = new ArrayList<>();
		
		public void addVertex(Vertex v){
			if(!this.vertices.contains(v)){
				this.vertices.add(v);
			}
		}
		public boolean idAvailable(){
			return this.vertices.size() > 0;
		}
	}
	
	public class Vertex{
		public int id;
		public List<Edge> neighbors;
		
		public Vertex(int id){
			this.id = id;
			this.neighbors = new ArrayList<>();
		}
		
		public void add(Edge e){
			if(!this.neighbors.contains(e)){
				this.neighbors.add(e);
			}else{
				System.err.println("Edge added previously");
			}
		}
		public void neighborsInfo(){
			printlnInfo("All edges of the vertex " + this.id + ":");
			printlnInfo("----------------------------------------");
			for(Edge e:this.neighbors){
				printlnInfo("E = " + e.id + ", start V = " + e.getIdOfStartVertex() 
								+ ", end V = " + e.getIdOfEndtVertex());				
			}
		}
	}
	
	public class Edge{
		public Vertex start;
		public Vertex end;
		public int id;
		
		public Edge(int id, Vertex start, Vertex end){
			this.id = id;
			this.start = start;
			this.end = end;
		}
		
		public int getIdOfStartVertex(){
			return this.start.id;
		}
		public int getIdOfEndtVertex(){
			return this.start.id;
		}
	}
	
	public void printlnInfo(String s){
		System.out.println(s);
	}
}
