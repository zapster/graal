package org.graalvm.compiler.lir.saraverify;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class DuSequenceWeb {

    private Set<DefNode> defNodes;
    private Set<UseNode> useNodes;

    public DuSequenceWeb() {
        this.defNodes = new HashSet<>();
        this.useNodes = new HashSet<>();
    }

    public DuSequenceWeb(Set<DefNode> defNodes, Set<UseNode> useNodes) {
        this.defNodes = defNodes;
        this.useNodes = useNodes;
    }

    public Set<DefNode> getDefNodes() {
        return defNodes;
    }

    public Set<UseNode> getUseNodes() {
        return useNodes;
    }

    public void defNodesAddAll(Collection<? extends DefNode> defNodesCollection) {
        defNodes.addAll(defNodesCollection);
    }

    public void useNodesAddAll(Collection<? extends UseNode> useNodesCollection) {
        useNodes.addAll(useNodesCollection);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((defNodes == null) ? 0 : defNodes.hashCode());
        result = prime * result + ((useNodes == null) ? 0 : useNodes.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DuSequenceWeb)) {
            return false;
        }

        DuSequenceWeb duSequenceWeb = (DuSequenceWeb) obj;

        return this.defNodes.equals(duSequenceWeb.defNodes) && this.useNodes.equals(duSequenceWeb.useNodes);
    }

}
