package org.graalvm.compiler.lir.saraverify;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DuSequenceWeb {

    private Set<DefNode> defNodes;
    private Set<MoveNode> moveNodes;
    private Set<UseNode> useNodes;

    public DuSequenceWeb() {
        this.defNodes = new HashSet<>();
        this.moveNodes = new HashSet<>();
        this.useNodes = new HashSet<>();
    }

    public void addNodes(List<Node> nodes) {
        for (Node node : nodes) {
            if (node.isDefNode()) {
                DefNode defNode = (DefNode) node;
                defNodes.add(defNode);
            } else if (node.isUseNode()) {
                UseNode useNode = (UseNode) node;
                useNodes.add(useNode);
            } else {
                MoveNode moveNode = (MoveNode) node;
                moveNodes.add(moveNode);
            }
        }
    }

    public void addDefNodes(Set<DefNode> defNodesAdd) {
        defNodes.addAll(defNodesAdd);
    }

    public void addMoveNodes(Set<MoveNode> moveNodesAdd) {
        moveNodes.addAll(moveNodesAdd);
    }

    public void addUseNodes(Set<UseNode> useNodesAdd) {
        useNodes.addAll(useNodesAdd);
    }

    public Set<DefNode> getDefNodes() {
        return defNodes;
    }

    public Set<MoveNode> getMoveNodes() {
        return moveNodes;
    }

    public Set<UseNode> getUseNodes() {
        return useNodes;
    }

    public boolean isEmpty() {
        return defNodes.isEmpty() && moveNodes.isEmpty() && useNodes.isEmpty();
    }

    @Override
    public String toString() {
        String string = "Definition Nodes:\n";
        string = string + defNodes.stream().map(defNode -> defNode.toString()).collect(Collectors.joining(", "));

        string = string + "\nUse Nodes:\n";
        string = string + useNodes.stream().map(useNode -> useNode.toString()).collect(Collectors.joining(", "));

        return string;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((defNodes == null) ? 0 : defNodes.hashCode());
        result = prime * result + ((defNodes == null) ? 0 : moveNodes.hashCode());
        result = prime * result + ((useNodes == null) ? 0 : useNodes.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof DuSequenceWeb)) {
            return false;
        }

        DuSequenceWeb duSequenceWeb = (DuSequenceWeb) obj;

        return this.defNodes.equals(duSequenceWeb.defNodes) && this.moveNodes.equals(duSequenceWeb.moveNodes) && this.useNodes.equals(duSequenceWeb.useNodes);
    }

}
