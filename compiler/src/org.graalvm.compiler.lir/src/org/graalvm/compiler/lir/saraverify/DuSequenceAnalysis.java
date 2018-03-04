package org.graalvm.compiler.lir.saraverify;

import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.StandardOp.LoadConstantOp;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.ssa.SSAUtil;
import org.graalvm.compiler.lir.ssa.SSAUtil.PhiValueVisitor;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterAttributes;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class DuSequenceAnalysis {
// test
    public static class DummyConstDef extends LIRInstruction {
        public static final LIRInstructionClass<DummyConstDef> TYPE = LIRInstructionClass.create(DummyConstDef.class);
        protected ConstantValue value;

        public DummyConstDef(ConstantValue value) {
            super(TYPE);
            this.value = value;
        }

        public ConstantValue getValue() {
            return value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }

    }

    public static class DummyRegDef extends LIRInstruction {
        public static final LIRInstructionClass<DummyRegDef> TYPE = LIRInstructionClass.create(DummyRegDef.class);
        @Def({REG}) protected AllocatableValue value;

        public DummyRegDef(AllocatableValue value) {
            super(TYPE);
            this.value = value;
        }

        public void setValue(AllocatableValue value) {
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    private int defOperandPosition;
    private int useOperandPosition;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    public static final String ERROR_MSG_PREFIX = "SARA verify error: ";

    public static final CounterKey skippedCompilationUnits = DebugContext.counter("SARAVerify[skipped]");
    public static final CounterKey executedCompilationUnits = DebugContext.counter("SARAVerify[executed]");

    private static void logInstructions(LIR lir) {
        DebugContext debug = lir.getDebug();

        try (Indent i1 = debug.indent()) {
            for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
                debug.log(3, "Visiting Block: " + block.getId());
                try (Indent i2 = debug.indent()) {
                    for (LIRInstruction instr : lir.getLIRforBlock(block)) {
                        debug.log(3, instr.toString());
                    }
                }
            }
        }
    }

    public AnalysisResult determineDuSequences(LIRGenerationResult lirGenRes, RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        LIR lir = lirGenRes.getLIR();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();
        AbstractBlockBase<?> startBlock = lir.getControlFlowGraph().getStartBlock();
        BitSet blockQueue = new BitSet(blocks.length);

        if (!(lir.getControlFlowGraph().getLoops().isEmpty())) {
            // control flow contains one or more loops
            skippedCompilationUnits.increment(lir.getDebug());
            return null;
        }
        executedCompilationUnits.increment(lir.getDebug());

        initializeCollections();
        logInstructions(lir);

        // start with leaf blocks
        for (AbstractBlockBase<?> block : blocks) {
            if (block.getSuccessorCount() == 0) {
                blockQueue.set(block.getId());
            }
        }

        BitSet visitedBlocks = new BitSet(blocks.length);
        HashMap<AbstractBlockBase<?>, Map<Value, List<Node>>> blockUnfinishedDuSequences = new HashMap<>();
        Map<Value, List<DefNode>> duSequences = new HashMap<>();

        while (!blockQueue.isEmpty()) {
            // get any block, whose successors have already been visited, remove it from the queue and add its
            // predecessors to the queue
            int blockId = blockQueue.stream().filter(id -> Arrays.asList(blocks[id].getSuccessors()).stream().allMatch(b -> visitedBlocks.get(b.getId()))).findFirst().getAsInt();
            blockQueue.clear(blockId);
            visitedBlocks.set(blockId);

            AbstractBlockBase<?> block = blocks[blockId];
            for (AbstractBlockBase<?> predecessor : block.getPredecessors()) {
                blockQueue.set(predecessor.getId());
            }

            // get instructions of block
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

            // merging of value uses from multiple predecessors
            Map<Value, List<Node>> mergedUnfinishedDuSequences = mergeMaps(blockUnfinishedDuSequences,
                            block.getSuccessors());
            blockUnfinishedDuSequences.put(block, mergedUnfinishedDuSequences);

            determineDuSequences(lir, block, instructions, duSequences, mergedUnfinishedDuSequences, startBlock);
        }
        assert visitedBlocks.length() == visitedBlocks.cardinality() && visitedBlocks.cardinality() == blocks.length && visitedBlocks.stream().allMatch(id -> id < blocks.length);

        // analysis of dummy instructions
        analyseUndefinedValues(duSequences, blockUnfinishedDuSequences.get(blocks[0]), registerAttributes, dummyRegDefs, dummyConstDefs);

        return new AnalysisResult(duSequences, instructionDefOperandCount,
                        instructionUseOperandCount, dummyRegDefs, dummyConstDefs);
    }

    public AnalysisResult determineDuSequences(List<LIRInstruction> instructions, RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        initializeCollections();

        // analysis of given instructions
        Map<Value, List<DefNode>> duSequences = new HashMap<>();
        Map<Value, List<Node>> unfinishedDuSequences = new HashMap<>();
        determineDuSequences(null, null, instructions, duSequences, unfinishedDuSequences, null);

        // analysis of dummy instructions
        analyseUndefinedValues(duSequences, unfinishedDuSequences, registerAttributes,
                        dummyRegDefs, dummyConstDefs);

        return new AnalysisResult(duSequences, instructionDefOperandCount, instructionUseOperandCount, dummyRegDefs, dummyConstDefs);
    }

    private void determineDuSequences(LIR lir, AbstractBlockBase<?> block, List<LIRInstruction> instructions, Map<Value, List<DefNode>> duSequences,
                    Map<Value, List<Node>> unfinishedDuSequences, AbstractBlockBase<?> startBlock) {
        DefInstructionValueConsumer defConsumer = new DefInstructionValueConsumer(duSequences, unfinishedDuSequences);
        UseInstructionValueConsumer useConsumer = new UseInstructionValueConsumer(unfinishedDuSequences);

        List<LIRInstruction> reverseInstructions = new ArrayList<>(instructions);
        Collections.reverse(reverseInstructions);

        for (LIRInstruction inst : reverseInstructions) {
            defOperandPosition = 0;
            useOperandPosition = 0;

            if (inst instanceof JumpOp) {
                // jump
                JumpOp jumpInst = (JumpOp) inst;

                if (jumpInst.getPhiSize() > 0) {
                    // phi
                    PhiValueVisitor visitor = new SARAVerifyPhiValueVisitor(unfinishedDuSequences, inst);
                    SSAUtil.forEachPhiValuePair(lir, block.getSuccessors()[0], block, visitor);
                }
            } else if (inst.isValueMoveOp()) {
                // value move
                ValueMoveOp moveInst = (ValueMoveOp) inst;
                insertMoveNode(unfinishedDuSequences, moveInst.getResult(), moveInst.getInput(), inst);
            } else if (inst.isLoadConstantOp()) {
                // load constant
                LoadConstantOp loadConstantOp = (LoadConstantOp) inst;
                insertMoveNode(unfinishedDuSequences, loadConstantOp.getResult(), new ConstantValue(ValueKind.Illegal, loadConstantOp.getConstant()), inst);
            } else if (block == startBlock || !(inst instanceof LabelOp)) {
                visitValues(inst, defConsumer, useConsumer, useConsumer);

                instructionDefOperandCount.put(inst, defOperandPosition);
                instructionUseOperandCount.put(inst, useOperandPosition);
            }
        }
    }

    private void insertMoveNode(Map<Value, List<Node>> unfinishedDuSequences, Value result, Value input, LIRInstruction instruction) {
        MoveNode moveNode = new MoveNode(result, input, instruction, 0, 0);

        List<Node> resultNodes = unfinishedDuSequences.get(result);

        defOperandPosition = 1;
        useOperandPosition = 1;

        if (resultNodes == null) {
            // no usage for copied value
            return;
        }

        assert resultNodes.stream().allMatch(node -> !node.isDefNode());

        moveNode.addAllNextNodes(resultNodes);
        unfinishedDuSequences.remove(result);

        List<Node> inputNodes = getOrCreateList(unfinishedDuSequences, input);
        inputNodes.add(moveNode);
    }

    private void initializeCollections() {
        instructionDefOperandCount = new IdentityHashMap<>();
        instructionUseOperandCount = new IdentityHashMap<>();
    }

    public static <KeyType, ListType> List<ListType> getOrCreateList(Map<KeyType, List<ListType>> map, KeyType key) {
        List<ListType> list = map.get(key);

        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }

        return list;
    }

    public static <T, U, V> Map<U, List<V>> mergeMaps(Map<T, Map<U, List<V>>> map, T[] mergeKeys) {
        Map<U, List<V>> mergedMap = new HashMap<>();

        for (T mergeKey : mergeKeys) {
            Map<U, List<V>> mergeValueMap = map.get(mergeKey);

            for (Entry<U, List<V>> entry : mergeValueMap.entrySet()) {
                List<V> mergedMapValue = mergedMap.get(entry.getKey());

                if (mergedMapValue == null) {
                    mergedMapValue = new ArrayList<>();
                    mergedMap.put(entry.getKey(), mergedMapValue);
                }

                List<V> newValues = entry.getValue().stream().filter(x -> !(mergedMap.get(entry.getKey()).contains(x))).collect(Collectors.toList());
                mergedMapValue.addAll(newValues);
            }
        }
        return mergedMap;
    }

    private static void analyseUndefinedValues(Map<Value, List<DefNode>> duSequences, Map<Value, List<Node>> unfinishedDuSequences,
                    RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs) {

        for (Entry<Value, List<Node>> entry : unfinishedDuSequences.entrySet()) {
            Value value = entry.getKey();
            LIRInstruction dummyDef;

            if (LIRValueUtil.isConstantValue(value)) {
                // constant
                ConstantValue constantValue = LIRValueUtil.asConstantValue(value);
                Constant constant = constantValue.getConstant();
                DummyConstDef dummyConstDef = dummyConstDefs.get(constant);
                if (dummyConstDef == null) {
                    dummyConstDef = new DummyConstDef(constantValue);
                    dummyConstDefs.put(constant, dummyConstDef);
                }
                dummyDef = dummyConstDef;
            } else {
                if (!ValueUtil.isRegister(value)) {
                    // other than register or constant
                    GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used value " + value + " is not defined.");
                }

                // register
                Register register = ValueUtil.asRegister(value);
                if (registerAttributes[register.number].isAllocatable()) {
                    // register is allocatable
                    GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used register " + register + " is not defined.");
                }

                DummyRegDef dummyRegDef = dummyRegDefs.get(register);
                if (dummyRegDef == null) {
                    dummyRegDef = new DummyRegDef(register.asValue());
                    dummyRegDefs.put(register, dummyRegDef);
                }
                dummyDef = dummyRegDef;
            }

            DefNode dummyDefNode = new DefNode(value, dummyDef, 0);
            dummyDefNode.addAllNextNodes(entry.getValue());
            duSequences.put(value, Arrays.asList(dummyDefNode));
        }

        unfinishedDuSequences.clear();
    }

    private static void visitValues(LIRInstruction instruction, InstructionValueConsumer defConsumer,
                    InstructionValueConsumer useConsumer, InstructionValueConsumer aliveConsumer) {
        // TODO: instruction.isMoveOp

        instruction.visitEachAlive(aliveConsumer);
        // TODO: instruction.forEachState(proc); alive

        // TODO: instruction.visitEachTemp(proc);
        // TODO: instruction.destroysCallerSavedRegisters()
        // TODO: for each caller saved register a dummy def
        instruction.visitEachOutput(defConsumer);
        instruction.visitEachInput(useConsumer);
    }

    class DefInstructionValueConsumer implements InstructionValueConsumer {

        Map<Value, List<DefNode>> duSequences;
        Map<Value, List<Node>> unfinishedDuSequences;

        public DefInstructionValueConsumer(Map<Value, List<DefNode>> duSequences, Map<Value, List<Node>> unfinishedDuSequences) {
            this.duSequences = duSequences;
            this.unfinishedDuSequences = unfinishedDuSequences;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value)) {
                // value is part of a composite value
                defOperandPosition++;
                return;
            }

            List<Node> unfinishedNodes = unfinishedDuSequences.get(value);
            if (unfinishedNodes == null) {
                // definition of a value, which is not used
                defOperandPosition++;
                return;
            }

            AllocatableValue allocatableValue = ValueUtil.asAllocatableValue(value);

            assert unfinishedNodes.stream().allMatch(node -> !node.isDefNode());

            DefNode defNode = new DefNode(allocatableValue, instruction, defOperandPosition);
            defNode.addAllNextNodes(unfinishedNodes);
            unfinishedDuSequences.remove(value);

            List<DefNode> nodes = getOrCreateList(duSequences, value);
            nodes.add(defNode);

            defOperandPosition++;
        }

    }

    class UseInstructionValueConsumer implements InstructionValueConsumer {

        private Map<Value, List<Node>> unfinishedDuSequences;

        public UseInstructionValueConsumer(Map<Value, List<Node>> unfinishedDuSequences) {
            this.unfinishedDuSequences = unfinishedDuSequences;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value) || flags.contains(OperandFlag.UNINITIALIZED) || LIRValueUtil.isConstantValue(value)) {
                // value is part of a composite value, uninitialized or a constant
                useOperandPosition++;
                return;
            }

            List<Node> nodes = getOrCreateList(unfinishedDuSequences, value);

            nodes.add(new UseNode(value, instruction, useOperandPosition));

            useOperandPosition++;
        }

    }

    class SARAVerifyPhiValueVisitor implements PhiValueVisitor {

        private Map<Value, List<Node>> unfinishedDuSequences;
        private LIRInstruction instruction;

        public SARAVerifyPhiValueVisitor(Map<Value, List<Node>> unfinishedDuSequences, LIRInstruction instruction) {
            this.unfinishedDuSequences = unfinishedDuSequences;
            this.instruction = instruction;
        }

        @Override
        public void visit(Value phiIn, Value phiOut) {
            insertMoveNode(unfinishedDuSequences, phiIn, phiOut, instruction);
        }

    }
}
