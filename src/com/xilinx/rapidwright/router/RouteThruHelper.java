package com.xilinx.rapidwright.router;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;

import com.esotericsoftware.kryo.unsafe.UnsafeInput;
import com.esotericsoftware.kryo.unsafe.UnsafeOutput;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Example of how to check if a node->node connection is a routethru
 */
public class RouteThruHelper {

    private HashMap<TileTypeEnum,HashSet<Integer>> routeThrus;
    
    private Device device; 
    
    private static final String rtName = "routeThrus";
    
    private static String getSerializedFileName(Device device) {
        String folderName = FileTools.getRapidWrightPath() + File.separator + rtName;
        FileTools.makeDirs(folderName);
        return folderName + File.separator + device.getName() + ".rt";
    }
    
    public RouteThruHelper(Device device) {
        this.device = device;
        init();
    }
    
    private void writeFile() {
        UnsafeOutput out = FileTools.getUnsafeOutputStream(getSerializedFileName(device));
        out.writeInt(routeThrus.size());
        for(Entry<TileTypeEnum, HashSet<Integer>> e : routeThrus.entrySet()) {
            out.writeString(e.getKey().toString());
            out.writeInt(e.getValue().size());
            for(Integer i : e.getValue()) {
                out.writeInt(i);
            }
        }
        out.close();
    }
    
    private void readFile(){
        routeThrus = new HashMap<TileTypeEnum, HashSet<Integer>>();
        UnsafeInput in = FileTools.getUnsafeInputStream(getSerializedFileName(device));
        int count = in.readInt();
        for(int i=0; i < count; i++) {
            TileTypeEnum type = TileTypeEnum.valueOf(in.readString());
            int count2 = in.readInt();
            HashSet<Integer> pips = new HashSet<Integer>(count2);
            for(int j=0; j < count2; j++) {
                pips.add(in.readInt());
            }
            routeThrus.put(type, pips);
        }
    }
    
    private void init() {
        String serializedFileName = getSerializedFileName(device);
        routeThrus = new HashMap<TileTypeEnum,HashSet<Integer>>();
        if(new File(serializedFileName).exists()) {
            readFile();
            return;
        }
        for(Tile tile : device.getAllTiles()) {
            if(routeThrus.containsKey(tile.getTileTypeEnum())) continue;
            HashSet<Integer> rtPIPs = new HashSet<Integer>();
            for(PIP p : tile.getPIPs()) {
                if(p.isRouteThru()) {
                    int startEndWirePair = (p.getStartWireIndex() << 16) | p.getEndWireIndex();
                    rtPIPs.add(startEndWirePair);
                }
            }
            if(rtPIPs.size() > 0) routeThrus.put(tile.getTileTypeEnum(), rtPIPs);
        }
        writeFile();
    }
    
    public boolean isRouteThru(Tile tile, int startWire, int endWire) {
        HashSet<Integer> rtPairs = routeThrus.get(tile.getTileTypeEnum());
        if(rtPairs == null) return false;
        return rtPairs.contains(startWire << 16 | endWire);
    }
    
    public boolean isRouteThru(Node start, Node end) {
        Tile tile = end.getTile();
        int endWire = end.getWire();
        Wire[] wiresInStartNode = start.getAllWiresInNode();
        HashSet<Integer> rtPairs = routeThrus.get(tile.getTileTypeEnum());
        if(rtPairs == null) return false;
        for(Wire w : wiresInStartNode) {
            if(w.getTile().equals(tile)) {
                if(rtPairs.contains((w.getWireIndex() << 16) | endWire)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private void printRouteThrusByTileType() {
        HashSet<TileTypeEnum> visited = new HashSet<>();
        for(Tile tile : device.getAllTiles()) {
            if(visited.contains(tile.getTileTypeEnum())) continue;
            visited.add(tile.getTileTypeEnum());
            HashSet<Integer> rtPairs = routeThrus.get(tile.getTileTypeEnum());
            if(rtPairs == null) continue; 
            System.out.println(tile.getTileTypeEnum() + "(" + tile.getName() + "):");
            for(Integer i : rtPairs) {
                int startWire = i >>> 16;
                int endWire = i & 0xffff;
                System.out.println("  " + tile.getWireName(startWire) + " -> " + tile.getWireName(endWire));
            }
        }        
    }
    
    public static void main(String[] args) {
        RouteThruHelper rtHelper = new RouteThruHelper(Device.getDevice(Device.AWS_F1));

        //rtHelper.printRouteThrusByTileType();
        
        for(Tile tile : rtHelper.device.getAllTiles()) {
            if(tile.getTileTypeEnum() == TileTypeEnum.INT) {
                for(String wireName : tile.getWireNames()) {
                    Node node = new Node(tile, wireName);
                    for(Node downhill : node.getAllDownhillNodes()) {
                        if(rtHelper.isRouteThru(node, downhill)) {
                            System.out.println(node + " " + downhill);
                        }
                    }
                }
                break;
            }
        }
        
    }
}
