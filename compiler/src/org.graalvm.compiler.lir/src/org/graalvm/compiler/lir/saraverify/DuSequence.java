package org.graalvm.compiler.lir.saraverify;

import java.util.Iterator;
import java.util.LinkedList;

import jdk.vm.ci.meta.AllocatableValue;

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

    public DuPair peekLast() {
        return duPairs.peekLast();
    }

    @Override
    public String toString() {
        String values = "\nValues:";
        String instructions = "Def at pos ";

        Iterator<DuPair> duPairsIterator = duPairs.iterator();

        DuPair duPair = duPairsIterator.next();
        AllocatableValue lastValue = duPair.getValue();
        values = values + " " + lastValue;
        instructions = instructions + duPair.getOperandDefPosition() + " in: " + duPair.getDefInstruction();

        while (duPairsIterator.hasNext()) {
            duPair = duPairsIterator.next();

            lastValue = duPair.getValue();
            values = values + " -> " + lastValue;

            instructions = instructions + "\nCopy: " + duPair.getDefInstruction();
        }

        instructions = instructions + "\nUse at pos " + duPair.getOperandUsePosition() + " in: " + duPair.getUseInstruction();

        return values + "\n" + instructions;
    }

    /**
     * A DuSequence is equal to another one, if the following properties are equal:
     * <ul>
     * <li>The value that is defined in the first definition instruction.
     * <li>The value that is used in the last use instruction.
     * <li>The position of the defined value in the first definition.
     * <li>The position of the used value in the last use.
     * <li>The first definition instruction.
     * <li>The last use instruction.
     * </ul>
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DuSequence)) {
            return false;
        }

        DuSequence duSequence = (DuSequence) obj;

        DuPair thisDefInst = this.peekFirst();
        DuPair duSequenceDefInst = duSequence.peekFirst();
        DuPair thisUseInst = this.peekLast();
        DuPair duSequenceUseInst = duSequence.peekLast();

        if (!(thisDefInst.getValue().equals(duSequenceDefInst.getValue()))) {
            return false;
        }

        if (!(thisUseInst.getValue().equals(duSequenceUseInst.getValue()))) {
            return false;
        }

        if (thisDefInst.getOperandDefPosition() != duSequenceDefInst.getOperandDefPosition() ||
                        (thisUseInst.getOperandUsePosition() != duSequenceUseInst.getOperandUsePosition())) {
            return false;
        }

        return (thisDefInst.getDefInstruction().equals(duSequenceDefInst.getDefInstruction())) &&
                        (thisUseInst.getUseInstruction().equals(duSequenceUseInst.getUseInstruction()));
    }

    @Override
    public int hashCode() {
        final int prime = 37;
        int result = 0;
        result = prime * result + peekFirst().getValue().hashCode();
        result = prime * result + peekLast().getValue().hashCode();
        result = prime * result + peekFirst().getOperandDefPosition();
        result = prime * result + peekLast().getOperandUsePosition();
        result = prime * result + System.identityHashCode(peekFirst().getDefInstruction());
        result = prime * result + System.identityHashCode(peekLast().getUseInstruction());
        return result;
    }
}
