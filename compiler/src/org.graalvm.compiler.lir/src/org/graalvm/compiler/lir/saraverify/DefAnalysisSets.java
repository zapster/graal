package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

public class DefAnalysisSets {

    class Triple {
        Value location;
        DuSequenceWeb value;
        ArrayList<LIRInstruction> instructionSequence;

        public Triple(Value location, DuSequenceWeb value, LIRInstruction instruction) {
            this.location = SARAVerifyUtil.getValueIllegalValueKind(location);
            this.value = value;
            instructionSequence = new ArrayList<>();
            instructionSequence.add(instruction);
        }

        public Triple(Value location, DuSequenceWeb value, ArrayList<LIRInstruction> instructionSequence) {
            this.location = SARAVerifyUtil.getValueIllegalValueKind(location);
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
            return equalsLocationAndValue(triple) && this.instructionSequence.equals(triple.instructionSequence);
        }

        /**
         * Indicates whether some other tripe is equal to this one regarding the location and the
         * value.
         *
         * @param triple
         * @return true if this triple is the same as the triple argument regarding the location and
         *         the value, otherwise false
         */
        public boolean equalsLocationAndValue(Triple triple) {
            return this.location.equals(triple.location) && this.value.equals(triple.value);
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            return new Triple(location, value, new ArrayList<>(instructionSequence));
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
        return set.stream().anyMatch(t -> t.equalsLocationAndValue(triple));
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

    public void addLocation(Value locationValue, DuSequenceWeb value, LIRInstruction instruction) {
        location.add(new Triple(locationValue, value, instruction));
    }

    public void removeFromEvicted(Value locationValue, DuSequenceWeb value) {
        // remove all triples in the evicted set that have the location and the value from the
        // arguments
        evicted.removeIf(triple -> triple.location.equals(SARAVerifyUtil.getValueIllegalValueKind(locationValue)) //
                        && triple.value.equals(value));
    }

    public void propagateValue(AllocatableValue result, AllocatableValue input, LIRInstruction instruction) {
        // for every triple in the location set that consists of the location "input", a new triple
        // is added to the set, where the location is the argument
        // "result" and the copy instruction is added to the instruction sequence
        location.stream().filter(triple -> triple.location.equals(input))  //
                        .forEach(triple -> {
                            ArrayList<LIRInstruction> instructions = new ArrayList<>(triple.instructionSequence);
                            instructions.add(instruction);
                            location.add(new Triple(result, triple.value, instructions));
                        });

        // for every triple in the stale set that consists of the location "input", a new triple is
        // added to the set, where the location is the argument
        // "result" and the copy instruction is added to the instruction sequence
        stale.stream().filter(triple -> triple.location.equals(input))  //
                        .forEach(triple -> {
                            ArrayList<LIRInstruction> instructions = new ArrayList<>(triple.instructionSequence);
                            instructions.add(instruction);
                            stale.add(new Triple(result, triple.value, instructions));
                        });

    }

    public void destroyValuesAtLocations(List<Value> locationValues, LIRInstruction instruction) {
        // triples that have a location where the value gets destroyed
        List<Triple> destroyedTriples = location.stream().filter(triple -> locationValues.contains(triple.location)).collect(Collectors.toList());

        // remove the triples from the locations and the stale set
        location.removeAll(destroyedTriples);
        stale.removeIf(triple -> locationValues.contains(triple.location));

        // add the locations from the found triples to the evicted set
        destroyedTriples.stream().forEach(triple -> evicted.add(new Triple(triple.location, triple.value, instruction)));
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
