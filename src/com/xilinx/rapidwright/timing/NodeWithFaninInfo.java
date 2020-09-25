package com.xilinx.rapidwright.timing;


import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Wire;

public class NodeWithFaninInfo extends Node{

    //Node.toString

    //CountingSet<SitePinInst> sources;
    //CountingSet<Routable> parents;
    float pres_cost;
    float acc_cost;

    // Make sure you have setter/acccessor
    public void  setPresCost(float v) {pres_cost = v;}
    public float presCost()           {return pres_cost;}


    //  To convert Node to NodeWithFaninInfo.
    //	from: Node              n = axyz();
    //	to:   NodeWithFaninInfo n = NodeWithFaninInfo.create(axyz());
    public static NodeWithFaninInfo create(Node node){
        Wire wire = node.getAllWiresInNode()[0];
        return new NodeWithFaninInfo(wire);
    }

    public NodeWithFaninInfo(Wire wire){
        super(wire);
    }
}
