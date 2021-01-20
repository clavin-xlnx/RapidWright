package com.xilinx.rapidwright.routernew;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * CountingSet that counts how many of each object are present. 
 * Implemented using a HashMap<N,Integer>. 
 * Only objects that are present once or more are stored in the Map.
 */
//TODO replace this with HashMultiSet
public class CountingSet<E> implements Collection<E> {
	
	private Map<E,Integer> map;
	private int size;

	public CountingSet() {
		this.map = new HashMap<>();
		this.size = 0;
	}
	
	@Override
	public int size() {
		return this.size;
	}
	
	public Map<E,Integer> getMap(){
		return this.map;
	}

	@Override
	public boolean isEmpty() {
		return this.size == 0;
	}

	@Override
	public boolean contains(Object o) {
		return this.map.containsKey(o);
	}

	@Override
	public Iterator<E> iterator() {
		throw new RuntimeException();
	}

	@Override
	public Object[] toArray() {
		throw new RuntimeException();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		throw new RuntimeException();
	}

	@Override
	public boolean add(E e) {
		Integer count = this.map.get(e);
		if (count != null) {
			this.map.put(e, count + 1);
		} else {
			this.map.put(e, 1);
		}
		this.size++;
		
		return true;
	}

	@Override
	public boolean remove(Object o) {
		@SuppressWarnings("unchecked")
		E key = (E)o;
		
		Integer count = this.map.get(key);
		if (count == null) {
			return false;
		}
		
		this.size--;
		
		if (count == 1) {
			this.map.remove(key);
		} else {
			this.map.put(key, count - 1);
		}
		
		return false;
	}
	
	public String delayToString() {
		StringBuilder s = new StringBuilder();
		s.append("# unique delays: " + this.uniqueSize() + "\n");
		for(E e : this.map.keySet()) {
			s.append("  # of ");
			s.append(String.format("%-5s", e));
			s.append(" = ");
			s.append(String.format("%10s", this.map.get(e)) + "\n");
		}
		return s.toString();
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		throw new RuntimeException();
	}

	@Override
	public boolean addAll(Collection<? extends E> c) {
		throw new RuntimeException();
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		throw new RuntimeException();
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		throw new RuntimeException();
	}
	
	@Override
	public void clear() {
		this.map.clear();
		this.size = 0;
	}
	
	public int count(E n) {
		Integer count = this.map.get(n);
		if(count == null) {
			return 0;
		} else {
			return count;
		}
	}

	public int uniqueSize() {
		return this.map.size();
	}
}
