/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.lir.alloc.graphcoloring;

import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.isRegister;
import static org.graalvm.compiler.lir.LIRValueUtil.asVariable;
import static org.graalvm.compiler.lir.LIRValueUtil.isVariable;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;

import org.graalvm.compiler.common.PermanentBailoutException;
import org.graalvm.compiler.core.common.alloc.RegisterAllocationConfig;
import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph;
import org.graalvm.compiler.core.common.util.BitMap2D;
import org.graalvm.compiler.debug.Debug;
import org.graalvm.compiler.debug.Debug.Scope;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.ValueConsumer;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.alloc.graphcoloring.Interval.RegisterPriority;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.Register.RegisterCategory;
import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.Value;

public class Liveness {
    private RegisterArray registers;
    private int firstVariableNumber;
    private LIRGenerationResult lirGenRes;
    private RegisterAllocationConfig registerAllocationConfig;
    private AbstractBlockBase<?>[] blocks;
    private LIR lir;
    private AbstractControlFlowGraph<?> cfg;
    private BlockData[] blocksets;
    private HashMap<RegisterCategory, Number> regCatToRegCatNum;
    private int[] operandRegCatNum;
    private Interferencegraph[] graphArr;
    private BitMap2D intervalInLoop;

    private Chaitin allocator;

    public Liveness(RegisterArray registers, LIRGenerationResult lirGenRes, RegisterAllocationConfig registerAllocationConfig, Chaitin allocator) {
        this.registers = registers;
        this.firstVariableNumber = registers.size();
        this.registerAllocationConfig = registerAllocationConfig;
        this.lirGenRes = lirGenRes;
        this.lir = lirGenRes.getLIR();
        this.allocator = allocator;

        cfg = lir.getControlFlowGraph();
        blocks = cfg.getBlocks();

        regCatToRegCatNum = new HashMap<>();
        operandRegCatNum = new int[1];
        operandRegCatNum[0] = -1;

        operandRegCatNum = resizeAndInitArray(operandRegCatNum);

        blocksets = new BlockData[blocks.length];

        Debug.log("begin numberInstructions");
        numberInstructions();
        Debug.log("end numberInstructions");
        Debug.log("begin Livesets:\n");
        buildLiveSets();
        graphArr = initGraphArr();
        buildGlobalLiveSets();
        Debug.log("begin Intervals:\n");
        buildIntervals();
        Debug.log("begin graph:\n");
        rebuildGraph();

        Debug.log("end graph:\n");

    }

    boolean isIntervalInLoop(int interval, int loop) {
        return intervalInLoop.at(interval, loop);
    }

    protected void numberInstructions() {

        ValueConsumer setVariableConsumer = (value, mode, flags) -> {
            if (isVariable(value)) {
                Interval inter = allocator.addValue(asVariable(value), operandNumber(value));
                assert inter != null;
                Debug.log("NumberInstruction: %d", operandNumber(value));
            }
        };

        // Assign IDs to LIR nodes and build a mapping, lirOps, from ID to LIRInstruction node.
        int numInstructions = 0;
        for (AbstractBlockBase<?> block : cfg.getBlocks()) {
            numInstructions += allocator.getLIR().getLIRforBlock(block).size();
        }

        // initialize with correct length
        allocator.initOpIdMaps(numInstructions);

        int opId = 0;
        int index = 0;
        for (AbstractBlockBase<?> block : cfg.getBlocks()) {

            List<LIRInstruction> instructions = allocator.getLIR().getLIRforBlock(block);

            int numInst = instructions.size();
            for (int j = 0; j < numInst; j++) {
                LIRInstruction op = instructions.get(j);
                op.setId(opId);

                allocator.putOpIdMaps(index, op, block);
                assert allocator.instructionForId(opId) == op : "must match";

                op.visitEachTemp(setVariableConsumer);
                op.visitEachOutput(setVariableConsumer);

                index++;
                opId += 2; // numbering of lirOps by two
            }
        }
        assert index == numInstructions : "must match";
        assert (index << 1) == opId : "must match: " + (index << 1);
    }

    public HashMap<RegisterCategory, Number> getRegCatToRegCatNum() {
        return regCatToRegCatNum;
    }

    public Interferencegraph[] getInterferencegraph() {
        return graphArr;
    }

    private Interferencegraph[] initGraphArr() {
        Interferencegraph[] ret = new Interferencegraph[regCatToRegCatNum.size()];

        for (int i = 0; i < ret.length; i++) {
            ret[i] = new Interferencegraph(i);
        }

        return ret;

    }

    public void rebuildGraph() {

        graphArr = initGraphArr();
        buildGraph();
    }

    int operandNumber(Value operand) {

        if (isRegister(operand)) {
            int number = asRegister(operand).number;
            assert number < firstVariableNumber;
            return number;
        }
        assert isVariable(operand) : operand;
        return firstVariableNumber + ((Variable) operand).index;
    }

    private int getCategoryNumber(Value value) {
        RegisterCategory cat;
        int ret;
        if (isVariable(value)) {
            cat = getCategoryForPlatformKind(value.getPlatformKind());

        } else {
            assert isRegister(value);
            cat = asRegister(value).getRegisterCategory();
        }

        int opNum = operandNumber(value);
        while (operandRegCatNum.length <= opNum) {
            operandRegCatNum = resizeAndInitArray(operandRegCatNum);

        }

        int regCatNum = operandRegCatNum[opNum];

        if (regCatNum == -1) {
            if (!regCatToRegCatNum.containsKey(cat)) {
                ret = regCatToRegCatNum.size();
                regCatToRegCatNum.put(cat, ret);
// System.out.println("ret: " + ret);
// System.out.println("Size= " + regCatToRegCatNum.size());
            } else {
                ret = (int) regCatToRegCatNum.get(cat);
// System.out.println("else ret: " + ret);
// System.out.println(regCatNum);
            }

            operandRegCatNum[opNum] = ret;

        } else {

            ret = operandRegCatNum[opNum];
        }

        return ret;
    }

    private static int[] resizeAndInitArray(int[] small) {
        int[] temp = new int[small.length * 2];

        for (int i = 0; i < temp.length; i++) {
            temp[i] = -1;
        }
        System.arraycopy(small, 0, temp, 0, small.length);

        return temp;
    }

    private RegisterCategory getCategoryForPlatformKind(PlatformKind kind) {
        return registerAllocationConfig.getAllocatableRegisters(kind).allocatableRegisters[0].getRegisterCategory();

    }

    private void buildIntervals() {
        try (Scope s = Debug.scope("Build Intervals")) {

            List<?> lirInstructuions;
            RegisterArray callerSaveRegs = registerAllocationConfig.getRegisterConfig().getCallerSaveRegisters();
            for (int i = blocks.length - 1; i >= 0; i--) {
                Debug.logAndIndent("Block: %d", i);
                AbstractBlockBase<?> block = blocks[i];

                InstructionValueConsumer useConsumer = (inst, operand, mode, flags) -> {

                    if (isVariable(operand) || isRegister(operand)) {

                        int operandNum = operandNumber(operand);

                        int blockFrom = allocator.getFirstLirInstructionId(block);
                        int catNum = getCategoryNumber(operand);

                        addUse(operand, operandNum, blockFrom, inst.id(), catNum, registerPriorityOfInputOperand(flags));

// Interferencegraph iG = graphArr[catNum];
// iG.addNode(operandNum);

                    }
                    Debug.log("Operand: %s Mode: %s", operand, mode);

                };
                InstructionValueConsumer aliveConsumer = (inst, operand, mode, flags) -> {

                    if (isVariable(operand) || isRegister(operand)) {

                        int operandNum = operandNumber(operand);

                        int blockFrom = allocator.getFirstLirInstructionId(block);
                        int catNum = getCategoryNumber(operand);
                        // addAlive(Value operand, int operandNum, int from, int to, int catNum,
                        // RegisterPriority priority)
                        addAlive(operand, operandNum, blockFrom, inst.id() + 1, catNum, registerPriorityOfInputOperand(flags));

// Interferencegraph iG = graphArr[catNum];
// iG.addNode(operandNum);

                    }
                    Debug.log("Operand: %s Mode: %s", operand, mode);

                };

                InstructionValueConsumer tempConsumer = (inst, operand, mode, flags) -> {

                    if (isVariable(operand) || isRegister(operand)) {

                        int operandNum = operandNumber(operand);
                        assert operandNum >= 0;
                        int catNum = getCategoryNumber(operand);
                        addTemp(operand, inst.id(), inst.id() + 1, catNum, RegisterPriority.MustHaveRegister);

// Interferencegraph iG = graphArr[catNum];
// iG.addNode(operandNum);

                    }
                    Debug.log("Operand: %s Mode: %s", operand, mode);

                };

                InstructionValueConsumer stateConsumer = (inst, operand, mode, flags) -> {
                    if (isVariable(operand) || isRegister(operand)) {
                        int operandNum = operandNumber(operand);

                        Interval inter = allocator.addValue(operand, operandNum);
                        int blockFrom = allocator.getFirstLirInstructionId(block);
                        int catNum = getCategoryNumber(operand);
                        inter.setCatNum(catNum);

                        addState(operand, operandNum, blockFrom, inst.id() + 1, catNum, RegisterPriority.None);
// Interferencegraph iG = graphArr[catNum];
// iG.addNode(operandNum);

                    }
                    Debug.log("Operand: %s Mode: %s", operand, mode);
                };
                InstructionValueConsumer defConsumer = (inst, operand, mode, flags) -> {
                    if (isVariable(operand) || isRegister(operand)) {

                        int operandNum = operandNumber(operand);
                        // Interval inter = allocator.addValue(operand, operandNum);

                        int catNum = getCategoryNumber(operand);
                        // addDef(operand, operandNum, inst.id(), inst, catNum,
                        // registerPriorityOfOutputOperand(inst));
                        addDef(operand, operandNum, inst, catNum, registerPriorityOfOutputOperand(inst));
// addDef(Value operand, int operandNum, int from, int to, LIRInstruction inst, int catNum)
// Interferencegraph iG = graphArr[catNum];
// iG.addNode(operandNum);

                    }
                    Debug.log("Operand: %s Mode: %s", operand, mode);
                };

                final int blockFrom = allocator.getFirstLirInstructionId(block);
                int blockTo = allocator.getLastLirInstructionId(block);

                BitSet live = blocksets[block.getId()].liveOut;
                for (int operandNum = live.nextSetBit(0); operandNum >= 0; operandNum = live.nextSetBit(operandNum + 1)) {
                    assert live.get(operandNum) : "should not stop here otherwise";
                    Value operand = allocator.intervalFor(operandNum).getOperand();
                    if (Debug.isLogEnabled()) {
                        Debug.log("live out %d: %s", operandNum, operand);
                    }

                    // addUse(operand, operandNum, blockFrom, blockTo + 2,
                    // getCategoryNumber(operand), RegisterPriority.None);
                    Interval inter = allocator.addValue(operand, operandNum);

                    inter.addLiveRange(blockFrom, blockTo + 2);
// Debug.log(1, "Add Live Range id:%d Use from: %d to %d", inter.getOpId(), blockFrom, blockTo + 2);
// addUse(operand, operandNum, blockFrom, blockTo + 2, inter.getCatNum(), RegisterPriority.None);

// /*
// * Add special use positions for loop-end blocks when the interval is used
// * anywhere inside this loop. It's possible that the block was part of a
// * non-natural loop, so it might have an invalid loop index.
// */
// if (block.isLoopEnd() && block.getLoop() != null && isIntervalInLoop(operandNum,
// block.getLoop().getIndex())) {
// allocator.intervalFor(operandNum).addUse(blockTo);
// }
                }

                lirInstructuions = lir.getLIRforBlock(block);
                for (int j = lirInstructuions.size() - 1; j >= 0; j--) {
                    LIRInstruction inst = (LIRInstruction) lirInstructuions.get(j);

                    if (inst.destroysCallerSavedRegisters()) {

                        for (Register r : callerSaveRegs) {
                            if (allocator.attributes(r).isAllocatable()) {
                                int catNum = getCategoryNumber(r.asValue());
                                addTemp(r.asValue(), inst.id(), inst.id() + 1, catNum, RegisterPriority.None);

                            }
                        }
                        if (Debug.isLogEnabled()) {
                            Debug.log("operation destroys all caller-save registers");
                        }
                    }

                    inst.visitEachOutput(defConsumer);
                    inst.visitEachTemp(tempConsumer);
                    inst.visitEachAlive(aliveConsumer);
                    inst.visitEachInput(useConsumer);
                    inst.visitEachState(stateConsumer);
                }

            }

            for (int i = 0; i < allocator.getIntervals().length; i++) {
                Interval interval = allocator.intervalFor(i);
                if (interval != null && isRegister(interval.getOperand())) {
                    interval.addLiveRange(0, 1);
// Debug.log(1, "Add Live Range id:%d start from: %d to %d", interval.getOpId(), 0, 1);
                    int regCatNum = getCategoryNumber(interval.getOperand());
                    interval.setCatNum(regCatNum);
                }

            }
        }
    }

    private void addUse(Value operand, int operandNum, int from, int to, int catNum, RegisterPriority priority) {
        if (!allocator.isProcessed(operand)) {
            return;
        }
        Interval inter = allocator.addValue(operand, operandNum);
// Debug.log(1, "Add Live Range id:%d Use from: %d to %d", inter.getOpId(), from, to);
        inter.addLiveRange(from, to);
        inter.addUse(to & ~1, priority);
        inter.setCatNum(catNum);
        inter.setKind(operand.getValueKind());
        inter.setPriority(priority);

    }

    private void addState(Value operand, int operandNum, int from, int to, int catNum, RegisterPriority priority) {
        if (!allocator.isProcessed(operand)) {
            return;
        }
        Interval inter = allocator.addValue(operand, operandNum);
// Debug.log(1, "Add Live Range id:%d State from: %d to %d", inter.getOpId(), from, to);
        inter.addLiveRange(from, to);
        // inter.addUse(to);
        inter.setCatNum(catNum);
        inter.setKind(operand.getValueKind());
        inter.setPriority(priority);

    }

    private void addAlive(Value operand, int operandNum, int from, int to, int catNum, RegisterPriority priority) {
        if (!allocator.isProcessed(operand)) {
            return;
        }
        Interval inter = allocator.addValue(operand, operandNum);
// Debug.log(1, "Add Live Range id:%d Alive from: %d to %d", inter.getOpId(), from, to);
        inter.addLiveRange(from, to);
        inter.addUse(to, priority);
        inter.setCatNum(catNum);
        inter.setKind(operand.getValueKind());
        inter.setPriority(priority);

    }

    // addDef(operand, operandNum, inst.id(), inst, catNum, registerPriorityOfOutputOperand(inst));
    private void addDef(Value operand, int operandNum, LIRInstruction inst, int catNum, RegisterPriority priority) {
        if (!allocator.isProcessed(operand)) {
            return;
        }
        int defPos = inst.id();
        Interval inter = allocator.addValue(operand, operandNum);
        inter.setCatNum(catNum);

        LifeRange r = inter.first();
        if (r != null && r.getFrom() <= defPos) {
            /*
             * Update the starting point (when a range is first created for a use, its start is the
             * beginning of the current block until a def is encountered).
             */
            inter.first().setFrom(defPos);
            inter.addUse(defPos, priority);
            inter.setKind(operand.getValueKind());
            inter.addDef(defPos);
            inter.setPriority(priority);
// Debug.log(1, "Life Range of: %d altered at def: from:%d to:%d", inter.getOpId(),
// inter.first().getFrom(), inter.first().getTo());

        } else {
            /*
             * Dead value - make vacuous interval also add register priority for dead intervals
             */
            inter.addLiveRange(defPos, defPos + 1);
// Debug.log(1, "Add Live Range id:%d Def (deadValue)from: %d to %d", inter.getOpId(), defPos,
// defPos + 1);
// inter.addUse(defPos);
            inter.setCatNum(catNum);
            inter.addDef(defPos);
            inter.setKind(operand.getValueKind());
            if (Debug.isLogEnabled()) {
                Debug.log("Warning: def of operand %s at %d occurs without use", operand, defPos);
            }
        }

    }

    public void addTemp(Interval inter, int pos) {

        inter.addTempRange(pos, pos);
    }

    private void addTemp(Value operand, int pos, int to, int catNum, RegisterPriority priority) {
        if (!allocator.isProcessed(operand)) {
            return;
        }
        Interval inter = allocator.addValue(operand, operandNumber(operand));
        inter.addTempRange(pos, to);
// Debug.log(1, "Add Live Range id:%d Temp from: %d to %d", inter.getOpId(), pos, to);
        inter.setCatNum(catNum);
        inter.setKind(operand.getValueKind());
        inter.setPriority(priority);
        inter.addUse(pos, priority);
    }

    /**
     * Determines the register priority for an instruction's output/result operand.
     */
    protected RegisterPriority registerPriorityOfOutputOperand(LIRInstruction op) {
        if (op instanceof LabelOp) {
            LabelOp label = (LabelOp) op;
            if (label.isPhiIn()) {
                return RegisterPriority.None;
            }
        }

        // all other operands require a register
        return RegisterPriority.MustHaveRegister;
    }

    /**
     * Determines the priority which with an instruction's input operand will be allocated a
     * register.
     */
    protected static RegisterPriority registerPriorityOfInputOperand(EnumSet<OperandFlag> flags) {
        if (flags.contains(OperandFlag.STACK)) {
            return RegisterPriority.ShouldHaveRegister;
        }

        // all other operands require a register
        return RegisterPriority.MustHaveRegister;
    }

// private static boolean optimizeMethodArgument(Value value) {
// /*
// * Object method arguments that are passed on the stack are currently not optimized because
// * this requires that the runtime visits method arguments during stack walking.
// */
// // TODO: revisit old: return isStackSlot(value) && asStackSlot(value).isInCallerFrame() &&
// // value.getLIRKind().isValue();
// return isStackSlot(value) && asStackSlot(value).isInCallerFrame();
// }

    private void buildLiveSets() {
        intervalInLoop = new BitMap2D(allocator.operandSize(), allocator.numLoops());

        for (AbstractBlockBase<?> block : cfg.getBlocks()) {

            final BitSet liveGen = new BitSet();
            final BitSet liveKill = new BitSet();

            ValueConsumer useConsumer = (operand, mode, flags) -> {

                if (isVariable(operand) || isRegister(operand)) {

                    int operandNum = operandNumber(operand);
                    if (!liveKill.get(operandNum)) {
                        liveGen.set(operandNum);
                    }
                    int regCatNum = getCategoryNumber(operand);
                    assert regCatNum >= 0;
                    allocator.addValue(operand, operandNum);
                    if (block.getLoop() != null) {
                        intervalInLoop.setBit(operandNum, block.getLoop().getIndex());
                    }

                }
            };

            ValueConsumer stateConsumer = (operand, mode, flags) -> {
                if (isVariable(operand) || isRegister(operand)) {
                    int operandNum = operandNumber(operand);
                    if (!liveKill.get(operandNum)) {
                        liveGen.set(operandNum);
                    }
                    int regCatNum = getCategoryNumber(operand);
                    assert regCatNum >= 0;
                    allocator.addValue(operand, operandNum);

                }
            };
            ValueConsumer defConsumer = (operand, mode, flags) -> {
                if (isVariable(operand) || isRegister(operand)) {
                    int varNum = operandNumber(operand);
                    liveKill.set(varNum);
                    int regCatNum = getCategoryNumber(operand);
                    assert regCatNum >= 0;
                    allocator.addValue(operand, varNum);
                    if (block.getLoop() != null) {
                        intervalInLoop.setBit(varNum, block.getLoop().getIndex());
                    }

                }
            };

            for (LIRInstruction inst : lir.getLIRforBlock(block)) {
                // System.out.println("Instruction: " + inst);
                // try (Indent ident1 = Debug.logAndIndent("inst: %s", inst)) {

                inst.visitEachInput(useConsumer);
                inst.visitEachAlive(useConsumer);
                inst.visitEachOutput(defConsumer);
                inst.visitEachTemp(defConsumer);
                inst.visitEachState(stateConsumer);

                // }
            }
            BlockData data = new BlockData();

            // System.out.println("Block id: " + block.getId() + "\n");

            data.liveGen = liveGen;
            data.liveKill = liveKill;
            data.liveIn = new BitSet();
            data.liveOut = new BitSet();

            blocksets[block.getId()] = data;

        }// for (AbstractBlockBase<?> block : cfg.getBlocks())

    }

    private void buildGlobalLiveSets() {

        int numBlocks = blocks.length;
        boolean changeOccurred;
        boolean changeOccurredInBlock;
        int iterationCount = 0;
        BitSet liveOut = new BitSet(); // scratch set for calculations

        /*
         * Perform a backward dataflow analysis to compute liveOut and liveIn for each block. The
         * loop is executed until a fixpoint is reached (no changes in an iteration).
         */
        do {
            changeOccurred = false;

            // iterate all blocks in reverse order
            for (int i = numBlocks - 1; i >= 0; i--) {
                AbstractBlockBase<?> block = blocks[i];
                BlockData liveSets = blocksets[block.getId()];

                changeOccurredInBlock = false;

                /* liveOut(block) is the union of liveIn(sux), for successors sux of block. */
                int n = block.getSuccessorCount();
                if (n > 0) {
                    liveOut.clear();
                    // block has successors
                    if (n > 0) {
                        for (AbstractBlockBase<?> successor : block.getSuccessors()) {
                            liveOut.or(blocksets[successor.getId()].liveIn);
                        }
                    }

                    if (!liveSets.liveOut.equals(liveOut)) {
                        /*
                         * A change occurred. Swap the old and new live out sets to avoid copying.
                         */
                        BitSet temp = liveSets.liveOut;
                        liveSets.liveOut = liveOut;
                        liveOut = temp;

                        changeOccurred = true;
                        changeOccurredInBlock = true;
                    }
                }

                if (iterationCount == 0 || changeOccurredInBlock) {
                    /*
                     * liveIn(block) is the union of liveGen(block) with (liveOut(block) &
                     * !liveKill(block)).
                     *
                     * Note: liveIn has to be computed only in first iteration or if liveOut has
                     * changed!
                     */
                    BitSet liveIn = liveSets.liveIn;
                    liveIn.clear();
                    liveIn.or(liveSets.liveOut);
                    liveIn.andNot(liveSets.liveKill);
                    liveIn.or(liveSets.liveGen);

                    if (Debug.isLogEnabled()) {
// Debug.log("block %d: livein = %s, liveout = %s", block.getId(), liveIn, liveSets.liveOut);

                    }

                }
            }
            iterationCount++;

            if (changeOccurred && iterationCount > 50) {
                throw new PermanentBailoutException("too many iterations in computeGlobalLiveSets");
            }

        } while (changeOccurred);

    }

    String getCompilationUnitName() {
        return lirGenRes.getCompilationUnitName();
    }

    public String toString(int opId) {

        if (opId < firstVariableNumber) {
            return "opId: " + opId + " " + registers.get(opId).toString();
        }

        return "opId: " + opId + " v" + (opId - firstVariableNumber);

    }

    public String toStringGraph(int opId) {

        if (opId < firstVariableNumber) {
            return registers.get(opId).toString();
        }

        return "v" + (opId - firstVariableNumber);

    }

    private void buildGraph() {
        Interval[] intervalList = allocator.getIntervals();
        Interferencegraph iG;
        Interval currentInter;
        Interval tempInter;
        for (int i = 0; i < intervalList.length; i++) {
            currentInter = intervalList[i];

            if (currentInter != null && (currentInter.getOpId() >= firstVariableNumber || allocator.isAllocateable(currentInter.getOpId()))) {
                Debug.log("BuildGraph: CurrentInterval: %s catNum: %d", toString(currentInter.getOpId()), currentInter.getCatNum());
                iG = graphArr[currentInter.getCatNum()];
                iG.addNode(currentInter.getOpId());
                for (int j = i + 1; j < intervalList.length; j++) {
                    tempInter = intervalList[j];

                    if (tempInter != null && ((tempInter.getOpId() >= firstVariableNumber || allocator.isAllocateable(tempInter.getOpId())) && currentInter.getCatNum() == tempInter.getCatNum())) {

                        iG.addNode(tempInter.getOpId());

                        for (LifeRange range : currentInter.getLifeRanges()) {

                            boolean interference = tempInter.hasInterference(range, currentInter.isSpilled());

                            if (interference) {
                                iG.setEdge(currentInter.getOpId(), tempInter.getOpId(), true);
                                break;
                            }
                        }
                    }
                }
            }

        }

    }

// private String printBitset(BitSet set) {
// String ret = "{";
//
// for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
// ret = ret + toString(i) + ", ";
//
// }
//
// return ret + "}";
// }

    private class BlockData {
        /**
         * Bit map specifying which operands are live upon entry to this block. These are values
         * used in this block or any of its successors where such value are not defined in this
         * block. The bit index of an operand is its {@linkplain #operandNumber(Value) operand
         * number}.
         */
        public BitSet liveIn;

        /**
         * Bit map specifying which operands are live upon exit from this block. These are values
         * used in a successor block that are either defined in this block or were live upon entry
         * to this block. The bit index of an operand is its {@linkplain #operandNumber(Value)
         * operand number}.
         */
        public BitSet liveOut;

        /**
         * Bit map specifying which operands are used (before being defined) in this block. That is,
         * these are the values that are live upon entry to the block. The bit index of an operand
         * is its {@linkplain #operandNumber(Value) operand number}.
         */
        public BitSet liveGen;

        /**
         * Bit map specifying which operands are defined/overwritten in this block. The bit index of
         * an operand is its {@linkplain #operandNumber(Value) operand number}.
         */
        public BitSet liveKill;
    }

}
