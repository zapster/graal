package org.graalvm.compiler.lir.saraverify;

import java.util.LinkedList;
import java.util.ListIterator;

public class DuSequence {

    private LinkedList<DuPair> duPairs;

    public DuSequence(DuPair duPair) {
        duPairs = new LinkedList<>();
        duPairs.add(duPair);
    }

    public void addFirst(DuPair duPair) {
        duPairs.addFirst(duPair);
    }

    public DuPair peekFirst() {
        return duPairs.peekFirst();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DuSequence)) {
            return false;
        }

        DuSequence duSequence = (DuSequence) obj;
        if (duSequence.duPairs.size() != this.duPairs.size()) {
            return false;
        }

        ListIterator<DuPair> duPairsIterator = this.duPairs.listIterator();

        while (duPairsIterator.hasNext()) {
            DuPair duPair1 = duSequence.duPairs.get(duPairsIterator.nextIndex());
            DuPair duPair2 = duPairsIterator.next();

            if (!(duPair1.equals(duPair2))) {
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
