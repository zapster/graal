package org.graalvm.compiler.lir.saraverify;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.AllocatableValue;

public class DefAnalysisSets {

    class Triple {
        AllocatableValue location;
        DuSequenceWeb value;
        LinkedList<LIRInstruction> instructionSequence;

        public Triple(AllocatableValue location, DuSequenceWeb value, LIRInstruction instruction) {
            this.location = location;
            this.value = value;
            instructionSequence = new LinkedList<>();
            instructionSequence.add(instruction);
        }

        private Triple(AllocatableValue location, DuSequenceWeb value, LinkedList<LIRInstruction> instructionSequence) {
            this.location = location;
            this.value = value;
            this.instructionSequence = instructionSequence;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + instructionSequenceHashCode();
            result = prime * result + location.hashCode();
            result = prime * result + value.hashCode();
            return result;
        }

        private int instructionSequenceHashCode() {
            int hashCode = 1;
            for (LIRInstruction instruction : instructionSequence) {
                hashCode = 31 * hashCode + (instruction == null ? 0 : System.identityHashCode(instruction));
            }

            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Triple)) {
                return false;
            }

            Triple triple = (Triple) obj;
            return this.location.equals(triple.location) && this.value.equals(triple.value) && this.instructionSequence.equals(triple.instructionSequence);
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            return new Triple(location, value, new LinkedList<>(instructionSequence));
        }
    }

    /**
     * The location set records that location holds a value. The instruction sequence of a triple
     * denotes the copy operations of the du-sequence.
     */
    private Set<Triple> location;

    /**
     * The stale set records that location holds a stale value. The instruction sequence of a triple
     * denotes the non-copy instruction that made value in location become stale and the copy
     * instructions that propagate the stale value.
     */
    private Set<Triple> stale;

    /**
     * The eviction set records that the value is evicted from the location. The instruction
     * sequence consists of exactly one instruction, namely the instruction that kills the value
     * from the location.
     */
    private Set<Triple> evicted;

    public DefAnalysisSets() {
        this.location = new HashSet<>();
        this.stale = new HashSet<>();
        this.evicted = new HashSet<>();
    }

    public DefAnalysisSets(Set<Triple> location, Set<Triple> stale, Set<Triple> evicted) {
        this.location = location;
        this.stale = stale;
        this.evicted = evicted;
    }

    // TODO: rename or add method equalsLocationAndValue(Triple) to Triple
    public static boolean containsTriple(Triple triple, Set<Triple> set) {
        return set.stream().anyMatch(t -> t.location.equals(triple.location) && t.value.equals(triple.value));
    }

    public static Stream<Triple> locationUnionStream(List<DefAnalysisSets> defAnalysisSets) {
        return defAnalysisSets.stream().flatMap(sets -> sets.location.stream());
    }

    public static Set<Triple> locationIntersection(List<DefAnalysisSets> defAnalysisSets) {
        Stream<Triple> locationUnionStream = defAnalysisSets.stream().flatMap(sets -> sets.location.stream());
        Stream<Triple> locationIntersectedStream = locationUnionStream.filter(triple -> defAnalysisSets.stream().allMatch(sets -> containsTriple(triple, sets.location)));
        return locationIntersectedStream.collect(Collectors.toSet());
    }

    public static Set<Triple> staleUnion(List<DefAnalysisSets> defAnalysisSets) {
        Stream<Triple> staleUnionStream = defAnalysisSets.stream().flatMap(sets -> sets.stale.stream());
        return staleUnionStream.collect(Collectors.toSet());
    }

    public static Set<Triple> evictedUnion(List<DefAnalysisSets> defAnalysisSets) {
        Stream<Triple> evictedUnionStream = defAnalysisSets.stream().flatMap(sets -> sets.evicted.stream());
        return evictedUnionStream.collect(Collectors.toSet());
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + evicted.hashCode();
        result = prime * result + location.hashCode();
        result = prime * result + stale.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DefAnalysisSets)) {
            return false;
        }

        DefAnalysisSets defAnalysisSets = (DefAnalysisSets) obj;
        return defAnalysisSets.location.equals(location) && defAnalysisSets.stale.equals(stale) && defAnalysisSets.evicted.equals(evicted);
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return new DefAnalysisSets(cloneSet(location), cloneSet(stale), cloneSet(evicted));
    }

    private static Set<Triple> cloneSet(Set<Triple> set) throws CloneNotSupportedException {
        Set<Triple> clonedSet = new HashSet<>();
        for (Triple triple : set) {
            clonedSet.add((Triple) triple.clone());
        }

        return clonedSet;
    }

}
