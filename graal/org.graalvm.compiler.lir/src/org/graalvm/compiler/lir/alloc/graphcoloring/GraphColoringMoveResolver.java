package org.graalvm.compiler.lir.alloc.graphcoloring;

import static jdk.vm.ci.code.ValueUtil.asAllocatableValue;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.isRegister;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.debug.Debug;
import org.graalvm.compiler.debug.DebugCounter;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.LIRInsertionBuffer;
import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;

public class GraphColoringMoveResolver {
    private static final DebugCounter cycleBreakingSlotsAllocated = Debug.counter("LSRA[cycleBreakingSlotsAllocated]");

    private final List<Value> mappingFrom;
    private final List<Constant> mappingFromOpr;
    private final List<Value> mappingTo;
    private Chaitin allocator;
    private int insertIdx;
    private LIRInsertionBuffer insertionBuffer;
    private final int[] registerBlocked;

    protected GraphColoringMoveResolver(Chaitin allocator) {
        this.allocator = allocator;
        this.mappingFrom = new ArrayList<>(8);
        this.mappingFromOpr = new ArrayList<>(8);
        this.mappingTo = new ArrayList<>(8);
        this.insertIdx = -1;
        this.insertionBuffer = new LIRInsertionBuffer();
        this.registerBlocked = new int[allocator.getRegisters().size()];
    }

    public void addMapping(Value phiOut, Value phiIn) {

        if (Debug.isLogEnabled()) {
            Debug.log("add move mapping from %s to %s", phiOut, phiIn);
        }
        mappingFrom.add(phiOut);
        mappingFromOpr.add(null);
        mappingTo.add(phiIn);

    }

    public void addMapping(Constant fromOpr, Value phiIn) {
        if (Debug.isLogEnabled()) {
            Debug.log("add move mapping from %s to %s", fromOpr, phiIn);
        }

        mappingFrom.add(null);
        mappingFromOpr.add(fromOpr);
        mappingTo.add(phiIn);
    }

    private void resolveMappings() {

        try (Indent indent = Debug.logAndIndent("resolveMapping")) {
            if (Debug.isLogEnabled()) {
                printMapping();
            }

            int i;
            for (i = mappingFrom.size() - 1; i >= 0; i--) {
                Value from = mappingFrom.get(i);
                if (from != null) {
                    blockRegisters(from);
                }
            }
            int spillCandidate = -1;
            while (mappingFrom.size() > 0) {
                boolean processedValue = false;
                for (i = mappingFrom.size() - 1; i >= 0; i--) {
                    Value fromValue = mappingFrom.get(i);
                    Value toValue = mappingTo.get(i);
                    if (safeToProcessMove(fromValue, toValue)) {

                        if (fromValue != null) {
                            insertMove(fromValue, toValue);
                            unblockRegisters(fromValue);
                        } else {
                            insertMove(mappingFromOpr.get(i), toValue);

                        }
                        mappingFrom.remove(i);
                        mappingFromOpr.remove(i);
                        mappingTo.remove(i);
                        processedValue = true;

                    } else {
                        spillCandidate = i;
                        Debug.log("Try from: %s to: %s again, cannot be processed now! Candidate: %d", fromValue, toValue, spillCandidate);
                    }
                }
                if (!processedValue) {
                    breakCycle(spillCandidate);
                    Debug.log("CycleBreakNeeded! Candidate: %d", spillCandidate);
                    Value fromValue = mappingFrom.get(spillCandidate);
                    Value toValue = mappingTo.get(spillCandidate);
                    Debug.log("Mapping from: %s to: %s", fromValue, toValue);

                }

            }
        }
        assert checkEmpty();
    }

    private void breakCycle(int spillCandidate) {
        assert spillCandidate != -1 : "no interval in register for spilling found";
        Value fromValue = mappingFrom.get(spillCandidate);

        AllocatableValue spillSlot = allocator.getFrameMapBuilder().allocateSpillSlot(fromValue.getValueKind());

        cycleBreakingSlotsAllocated.increment();

        spillValue(spillCandidate, fromValue, spillSlot);

    }

    private void spillValue(int spillCandidate, Value fromValue, AllocatableValue spillSlot) {
        assert mappingFrom.get(spillCandidate).equals(fromValue);

        blockRegisters(spillSlot);

        insertMove(fromValue, spillSlot);
        mappingFrom.set(spillCandidate, spillSlot);
        unblockRegisters(fromValue);

    }

    private boolean checkEmpty() {
        assert mappingFrom.size() == 0 && mappingFromOpr.size() == 0 && mappingTo.size() == 0 : "list must be empty before and after processing";
        for (int i = 0; i < allocator.getRegisters().size(); i++) {
            assert registerBlocked[i] == 0 : "register map must be empty before and after processing";
        }
        return true;
    }

    private boolean safeToProcessMove(Value fromValue, Value toValue) {

        if (mightBeBlocked(toValue)) {
            if ((valueBlocked(toValue) > 1 || (valueBlocked(toValue) == 1 && !isMoveToSelf(fromValue, toValue)))) {
                return false;
            }
        }

        return true;

    }

    protected boolean isMoveToSelf(Value from, Value to) {
        assert to != null;
        if (to.equals(from)) {
            return true;
        }
        if (from != null && isRegister(from) && isRegister(to) && asRegister(from).equals(asRegister(to))) {
            assert LIRKind.verifyMoveKinds(to.getValueKind(), from.getValueKind()) : String.format("Same register but Kind mismatch %s <- %s", to, from);
            return true;
        }
        return false;

    }

    private void appendInsertionBuffer() {
        if (insertionBuffer.initialized()) {
            insertionBuffer.finish();
        }
        assert !insertionBuffer.initialized() : "must be uninitialized now";

        insertIdx = -1;
    }

    private void insertMove(Constant constant, Value toValue) {
        Debug.log("insertIdx: %d", insertIdx);
        assert insertIdx != -1 : "must setup insert position first";
        AllocatableValue toOpr = (AllocatableValue) toValue;
        LIRInstruction move = allocator.getSpillMoveFactory().createLoad(toOpr, constant);
        insertionBuffer.append(insertIdx, move);

        if (Debug.isLogEnabled()) {
            Debug.log("insert move from value %s to %s at %d", constant, toOpr, insertIdx);
        }

    }

    private void insertMove(Value fromValue, Value toValue) {

        assert insertIdx != -1 : "must setup insert position first";
        AllocatableValue toOpr = asAllocatableValue(toValue);
        AllocatableValue fromOpr = (AllocatableValue) fromValue;
        if (!isRegister(toOpr) && !isRegister(fromOpr)) {
            insertionBuffer.append(insertIdx, allocator.getSpillMoveFactory().createStackMove(toOpr, fromOpr));
            if (Debug.isLogEnabled()) {
                Debug.log("insert Stack move from %s to %s at %d", fromOpr, toOpr, insertIdx);
            }
        } else {

            insertionBuffer.append(insertIdx, allocator.getSpillMoveFactory().createMove(toOpr, fromOpr));
            if (Debug.isLogEnabled()) {
                Debug.log("insert move from %s to %s at %d", fromOpr, toOpr, insertIdx);
            }
        }
    }

    public boolean hasMappings() {

        return mappingFrom.size() > 0;
    }

    public void resolveAndAppendMoves() {
        if (hasMappings()) {
            resolveMappings();
        }
        appendInsertionBuffer();
    }

    public void setInsertPosition(List<LIRInstruction> instructions, int insertIdx) {
        assert this.insertIdx == -1 : "use moveInsertPosition instead of setInsertPosition when data already set";

        createInsertionBuffer(instructions);
        this.insertIdx = insertIdx;

    }

    private void createInsertionBuffer(List<LIRInstruction> instructions) {
        assert !insertionBuffer.initialized() : "overwriting existing buffer";
        insertionBuffer.init(instructions);

    }

    @SuppressWarnings("try")
    private void printMapping() {
        try (Indent indent = Debug.logAndIndent("Mapping")) {
            for (int i = mappingFrom.size() - 1; i >= 0; i--) {
                Value fromInterval = mappingFrom.get(i);
                Value toInterval = mappingTo.get(i);

                Debug.log("move %s <- %s", toInterval, fromInterval);
            }
        }
    }

    // mark assignedReg and assignedRegHi of the interval as blocked
    private void blockRegisters(Value value) {

        if (mightBeBlocked(value)) {
            // assert valueBlocked(value) == 0 : "location already marked as used: " + value;
            int direction = 1;
            setValueBlocked(value, direction);
            Debug.log("block %s", value);
        }
    }

    // mark assignedReg and assignedRegHi of the interval as unblocked
    private void unblockRegisters(Value value) {

        if (mightBeBlocked(value)) {
            assert valueBlocked(value) > 0 : "location already marked as unused: " + value;
            setValueBlocked(value, -1);
            Debug.log("unblock %s", value);
        }
    }

    protected int valueBlocked(Value location) {
        if (isRegister(location)) {
            return registerBlocked[asRegister(location).number];
        }
        throw JVMCIError.shouldNotReachHere("unhandled value " + location);
    }

    protected boolean mightBeBlocked(Value location) {
        return isRegister(location);
    }

    protected void setValueBlocked(Value location, int direction) {
        assert direction == 1 || direction == -1 : "out of bounds";
        if (isRegister(location)) {
            registerBlocked[asRegister(location).number] += direction;
        } else {
            throw JVMCIError.shouldNotReachHere("unhandled value " + location);
        }
    }

}
