package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;

public class DuSequenceWeb {

    private ArrayList<DuSequence> duSequences;

    public DuSequenceWeb() {
        duSequences = new ArrayList<>();
    }

    public void add(DuSequence duSequence) {
        duSequences.add(duSequence);
    }

    public void remove(DuSequence duSequence) {
        duSequences.remove(duSequence);
    }

    public boolean contains(DuSequence duSequence) {
        return duSequences.contains(duSequence);
    }

    public int size() {
        return duSequences.size();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DuSequenceWeb)) {
            return false;
        }

        DuSequenceWeb duSequenceWeb = (DuSequenceWeb) obj;

        if (this.size() != duSequenceWeb.size()) {
            return false;
        }

        for (DuSequence duSequence : this.duSequences) {
            if (!(duSequenceWeb.duSequences.stream().anyMatch(x -> x.equals(duSequence)))) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
