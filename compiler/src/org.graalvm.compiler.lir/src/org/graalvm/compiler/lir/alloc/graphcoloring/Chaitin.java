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

import static jdk.vm.ci.code.CodeUtil.isEven;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.isRegister;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Vector;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.alloc.RegisterAllocationConfig;
import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInsertionBuffer;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.VirtualStackSlot;
import org.graalvm.compiler.lir.alloc.graphcoloring.GraphColoringPhase.Options;
import org.graalvm.compiler.lir.alloc.graphcoloring.Interval.RegisterPriority;
import org.graalvm.compiler.lir.alloc.graphcoloring.Interval.UsePosition;
import org.graalvm.compiler.lir.debug.IntervalDumper;
import org.graalvm.compiler.lir.framemap.FrameMapBuilder;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool.MoveFactory;

import jdk.vm.ci.code.BailoutException;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.Register.RegisterCategory;
import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.RegisterAttributes;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

public class Chaitin implements IntervalDumper {
    private LIRGenerationResult lirGenRes;
    private MoveFactory spillMoveFactory;
    protected final RegisterAllocationConfig registerAllocationConfig;
    private RegisterArray registers;
    private Liveness lifeTimeAnalysis;
    private Interferencegraph[] graphArr;
    private ArrayList<ArrayDeque<StackObject>> stackArr;
    private RegisterArray[] allocRegs;
    private ArrayList<ArrayDeque<StackObject>> spillStackArr;
    private ArrayList<ArrayDeque<StackObject>> spilledStackArr;
    private HashMap<RegisterCategory, Number> regCatToRegCatNum;
    private int[][] colorArr;
    private int firstVariableNumber;
    private LIR lir;
    private AbstractBlockBase<?>[] blocks;
// private ArrayList<Interval> ValueList;
    private Interval[] intervals;
    private LIRInstruction[] opIdToInstructionMap;
    private AbstractBlockBase<?>[] opIdToBlockMap;
    private LIRInsertionBuffer insertionBuffer;
    private RegisterArray allocatableRegs;
    private BitSet allocatable;
    private boolean foundColor;
    private ArrayList<LifeRange> spilledRegions;
    protected final DebugContext debug;

    public Chaitin(TargetDescription target, LIRGenerationResult lirGenRes, MoveFactory spillMoveFactory, RegisterAllocationConfig registerAllocationConfig) {

        this.lirGenRes = lirGenRes;
        this.lir = lirGenRes.getLIR();
        blocks = lirGenRes.getLIR().linearScanOrder();

        this.spillMoveFactory = spillMoveFactory;
        this.registerAllocationConfig = registerAllocationConfig;
        this.registers = target.arch.getRegisters();
        this.firstVariableNumber = registers.size();
        this.allocatableRegs = registerAllocationConfig.getAllocatableRegisters();
        this.allocatable = new BitSet(registers.size());
// ValueList = new ArrayList<>();
        intervals = new Interval[10];
        initAllocRegs();
        this.lifeTimeAnalysis = new Liveness(registers, lirGenRes, registerAllocationConfig, this);
        this.graphArr = lifeTimeAnalysis.getInterferencegraph();
        this.colorArr = new int[graphArr.length][];
        this.insertionBuffer = new LIRInsertionBuffer();
        this.debug = lir.getDebug();
        initStack();
        fillAllocRegs();
        this.regCatToRegCatNum = lifeTimeAnalysis.getRegCatToRegCatNum();
        debug.log(lifeTimeAnalysis.getCompilationUnitName());
        debug.log("Graph size: %d", graphArr[0].size());

        if (debug.isLogEnabled()) {

            printGraph("Before Coloring");
            debug.dump(1, lir, "Before Coloring");
            debug.dump(1, this, "Before Coloring");
        }
//
// if
// (lirGenRes.getCompilationUnitName().equals("com.oracle.graal.jtt.loop.SpillLoopPhiVariableAtDefinition.test(int)"))
// {
// printGraph("After build graph");
// }

        colorGraph();
        // dumpLIR();
        int tries = 0;
        while (!foundColor && !isSpillStackEmpty() && tries < 500) {

            tries++;
            debug.log("CompilationUnitName: %s", lifeTimeAnalysis.getCompilationUnitName());
            debug.dump(1, lir, "before spillntries: %d", tries);
            debug.dump(1, this, "before spillntries: %d", tries);
            debug.log("Spill beginn: ");
            if (Options.LIROptGcIrSpilling.getValue(getLIR().getOptions())) {
                interferenceRegionSpill();
            } else {
                spill();
            }
            debug.log("Spill end: ");

            colorGraph();

            debug.dump(1, lir, "After spillntries: %d", tries);
            debug.dump(1, this, "After spillntries: %d", tries);
            spilledRegions = null;
            if (debug.isLogEnabled()) {

                printGraph("After spillntries: " + tries);
                printColors();

            }

            // throw JVMCIError.shouldNotReachHere("Stop test here!");
        }
// if
// (lirGenRes.getCompilationUnitName().equals("com.oracle.graal.jtt.loop.SpillLoopPhiVariableAtDefinition.test(int)"))
// {
// if (foundColor) {
// printGraph("After Coloring");
// printColors();
// }
// }
        if (!foundColor) {
            if (debug.isLogEnabled(1)) {
                // LifeTimeAnalysis.rebuildGraph();
                printGraph("After Coloring");
                printColors();
            }
            System.out.println(lirGenRes.getCompilationUnitName());
            for (int i = 0; i < spillStackArr.size(); i++) {
                if (spillStackArr.get(i) != null) {
                    if (!spillStackArr.get(i).isEmpty()) {
                        ArrayDeque<StackObject> stack = spillStackArr.get(i);
                        for (StackObject o : stack) {
                            System.out.println(lifeTimeAnalysis.toStringGraph(o.id));
                        }
                    }
                }
            }
            System.out.println("");
            throw new BailoutException("Every SpillCandidate spilled, no color found!");

        }

        debug.log("\nbeginn Assignment: ");
        AssignLocations assignLocations = new AssignLocations(registerAllocationConfig, colorArr, allocRegs, lirGenRes, lifeTimeAnalysis, this);
        assignLocations.run();
        debug.log("end Assignment: ");
        debug.dump(1, lir, "After AssignLocations");
        debug.log("Resolve DataFlow beginn: ");
        resolveDataFlow();
        debug.log("Resolve DataFlow end: ");

    }

    LIRGenerationResult getLirGenRes() {
        return lirGenRes;
    }

    private void initAllocRegs() {
        for (Register r : allocatableRegs) {
            allocatable.set(r.number);
        }

    }

    private void colorGraph() {
        colorArr = new int[graphArr.length][];
        initStack();
        foundColor = true;
        debug.log("begin simplify: ");
        simplify();
        debug.log("end simplify: ");
        if (debug.isLogEnabled()) {

            printGraph("After simplify");
        }

        debug.log("\nbeginn select: ");
        select();
        debug.log("end select: ");

        debug.log("Graph size: %d", graphArr[0].size());
        if (debug.isLogEnabled()) {

            printGraph("After select:");
            printColors();
        }

    }

    private void interferenceRegionSpill() {
        Interval spilledInterval = null;

        for (ArrayDeque<StackObject> s : spillStackArr) {
            while (!s.isEmpty()) {
                StackObject o = s.pop();
                Interval inter = intervalFor(o.id);
                assert o.spillCandidate;
                assert inter != null;

                spilledInterval = inter;

                debug.dump(1, lir, "Before spilling: %s", lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                debug.log("Choose %s for spilling", lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));

                int def = spilledInterval.getDef();

                ArrayList<UsePosition> usePositions = spilledInterval.getUsePositions();

                ArrayList<ArrayList<LifeRange>> interferenceRegions = getInterferceRegions(spilledInterval, o);
                spilledInterval.setInterferenceRegions(interferenceRegions);
                int neighbour = chooseNeighbour(spilledInterval);

                spilledInterval.setNeighbourSpilled(neighbour, true);

                spilledRegions = interferenceRegions.get(neighbour);

                createNewLifeRanges(spilledInterval, spilledRegions);

                debug.dump(1, lir, "After spilling: %s", lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                debug.dump(1, this, "After spilling: %s", lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                debug.log(1, "Spilling %s def: %d priority: %s", getVarName(spilledInterval.getOpId()), def, usePositions.get(usePositions.size() - 1).getPriority());

                if (debug.isLogEnabled(1)) {

                    ArrayList<LifeRange> lifeRanges = spilledInterval.getLifeRanges();
                    debug.log(1, "%s was spilled. New LifeRanges:", lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                    for (LifeRange range : lifeRanges) {
                        debug.log(1, "Range from: %d to: %d", range.getFrom(), range.getTo());
                    }

                }

            }
        }
        lifeTimeAnalysis.rebuildGraph();
        graphArr = lifeTimeAnalysis.getInterferencegraph();

    }

    private void createNewLifeRanges(Interval spilledInterval, ArrayList<LifeRange> spilledRanges) {
        int def = spilledInterval.getDef();
        AbstractBlockBase<?> spillBlock;
        int spillPos;
        boolean firstspill = false;

        ArrayList<LifeRange> lifeRanges = spilledInterval.getLifeRanges();
        ArrayList<UsePosition> usePositions = spilledInterval.getUsePositions();

        spilledInterval.delRanges();

        if (!spilledInterval.isSpilled()) {
            FrameMapBuilder frameMapBuilder = lirGenRes.getFrameMapBuilder();
            VirtualStackSlot slot = frameMapBuilder.allocateSpillSlot(spilledInterval.getKind());
            spilledInterval.setSlot(slot);
            firstspill = true;
        }

        int lifeRangePointer = 0;
        int usePositionPointer = 0;
        int spilledRegionPointer = 0;

        UsePosition currentPos;
        LifeRange currentRange;
        LifeRange currentSpilledRegion;

        int rangeEnd = 0;

        loop: for (int i = usePositions.get(0).getPos(); i >= usePositions.get(usePositions.size() - 1).getPos(); i--) {

            currentPos = usePositions.get(usePositionPointer);
            currentRange = lifeRanges.get(lifeRangePointer);

            currentSpilledRegion = spilledRanges.get(spilledRegionPointer);

            while (currentSpilledRegion.getFrom() > currentRange.getTo() || currentSpilledRegion.getFrom() >= currentPos.getPos()) {
                spilledRegionPointer++;
                if (spilledRegionPointer >= spilledRanges.size()) {
                    spilledInterval.addLiveRange(currentRange.getFrom(), currentRange.getTo(), debug);
                    lifeRangePointer++;
                    break loop;
                }

                currentSpilledRegion = spilledRanges.get(spilledRegionPointer);

            }
            if (currentRange.getFrom() >= currentSpilledRegion.getTo()) { // SpilledRegion not in
                                                                          // lifeRange
                spilledInterval.addLiveRange(currentRange.getFrom(), currentRange.getTo(), debug);
                lifeRangePointer++;
                continue;
            }

            // SpilledRegion in liveRange

            rangeEnd = currentRange.getTo();
            // find usePos closest to spilledRegion To.

            UsePosition temp = currentPos;
            for (int tempIndex = usePositionPointer; temp.getPos() >= currentSpilledRegion.getTo(); temp = usePositions.get(tempIndex)) {
                if (temp.getPriority() == RegisterPriority.MustHaveRegister) {
                    currentPos = temp;
                    usePositionPointer = tempIndex;
                }
                tempIndex++;
                if (tempIndex >= usePositions.size()) {
                    break;
                }
                temp = usePositions.get(tempIndex);
            }
            assert currentPos == usePositions.get(usePositionPointer) : "";
            assert currentPos.getPos() >= currentSpilledRegion.getTo();

            if (currentPos.getPos() > currentRange.getTo()) {
                // no use pos in current life range, do nothing
            } else {
                if (currentPos.getPriority() == RegisterPriority.MustHaveRegister) {
                    spilledInterval.addLiveRange(currentPos.getPos(), rangeEnd, debug);
                } // if only shouldHaveRegister remains, every Position after spill is
                  // shoudHaveRegister
                spillPosition(spilledInterval, currentPos);
            }
            // find usePos before spilled region but closest to it.
            {
                int tempIndex = usePositionPointer;
                do {

                    tempIndex++;
                    if (tempIndex >= usePositions.size()) {
                        break;
                    }

                    temp = usePositions.get(tempIndex);
                    if (temp.getPriority() == RegisterPriority.MustHaveRegister) {
                        currentPos = temp;
                        usePositionPointer = tempIndex;
                        if (currentPos.getPos() > currentSpilledRegion.getFrom()) {
                            // current usePosition is MustHaveRegister and in spilled Region
                            spillPosition(spilledInterval, currentPos);
                        }
                    }

                } while (currentPos.getPos() > currentSpilledRegion.getFrom());
            }

            if (currentPos.getPos() < currentRange.getFrom()) { // usePos befor start of
                                                                // liveRange
                lifeRangePointer++;
                continue;
            }
            if (currentPos.getPos() > currentSpilledRegion.getTo()) { // usePos still after
                                                                      // spilled Region -> only
                                                                      // should have register
                                                                      // until def.
                break;
            }
            if (currentPos.getPos() == def) { // def is closest use pos before spilled Region
                break;
            }
            // use pos before spilled Region
            currentRange.setTo(currentPos.getPos());
        }// loop end

        while (lifeRangePointer < lifeRanges.size() - 1) {
            currentRange = lifeRanges.get(lifeRangePointer);
            spilledInterval.addLiveRange(currentRange.getFrom(), currentRange.getTo(), debug);
            lifeRangePointer++;
        }

        if (usePositions.get(usePositions.size() - 1).getPriority() == RegisterPriority.MustHaveRegister)

        {
            if (firstspill) {
                spillBlock = getSpillBlock(def);
                insertionBuffer.init(lir.getLIRforBlock(spillBlock));
                spillPos = getSpillPos(spillBlock, spilledInterval.getDef());
                spillPos++;
                debug.log("Spilled def instId: %d at index: %d", spilledInterval.getDef(), spillPos);

                assert spillPos != -1 : "Spill Position not found!";

                lifeTimeAnalysis.addTemp(spilledInterval, def);

                insertionBuffer.append(spillPos, spillMoveFactory.createMove(spilledInterval.getSlot(),
                                spilledInterval.getOperand()));
                insertionBuffer.finish();
            } else {
                lifeTimeAnalysis.addTemp(spilledInterval, def);
            }

        }

    }

    private void spillPosition(Interval spilledInterval, UsePosition pos) {

        if (pos.getPriority() == RegisterPriority.MustHaveRegister) {
            if (!pos.isRestored()) {
                pos.setRestored(true);
                int current = pos.getPos();

                if ((current & 1) != 0) {
                    current--;
                    lifeTimeAnalysis.addTemp(spilledInterval, current);
                }

                debug.log("ID: %s Def pos: %d spillPos: %d Must have register",
                                lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()), spilledInterval.getDef(), current);

                AbstractBlockBase<?> spillBlock = getSpillBlock(current);
                int spillPos = getSpillPos(spillBlock, current);

                assert spillPos != -1 : "Spill Position not found!";
                insertionBuffer.init(lir.getLIRforBlock(spillBlock));
                insertionBuffer.append(spillPos, spillMoveFactory.createMove((AllocatableValue) spilledInterval.getOperand(), spilledInterval.getSlot()));
                insertionBuffer.finish();
                lifeTimeAnalysis.addTemp(spilledInterval, pos.getPos());
            } else {
                int current = pos.getPos();

                if ((current & 1) != 0) {
                    current--;
                    lifeTimeAnalysis.addTemp(spilledInterval, current);
                }
                lifeTimeAnalysis.addTemp(spilledInterval, pos.getPos());
            }
        }

    }

    private int chooseNeighbour(Interval spilledInterval) {
        ArrayList<UsePosition> usePositions = spilledInterval.getUsePositions();
        ArrayList<ArrayList<LifeRange>> regions = spilledInterval.getInterferenceRegions();
        double[] costs = new double[regions.size()];

        for (UsePosition pos : usePositions) {
            AbstractBlockBase<?> block = getSpillBlock(pos.getPos());
            double cost = 0.0;
            if (block != null) {
                cost = block.probability();
            }
            for (int i = 0; i < regions.size(); i++) {
                for (LifeRange range : regions.get(i)) {
                    if (pos.getPos() <= range.getTo() && pos.getPos() >= range.getFrom()) {
                        costs[i] += cost;
                    }
                }

            }

        }

        int neighbour = 0;
        boolean found = false;
        for (int i = 0; i < regions.size(); i++) {
            if (!regions.get(i).isEmpty()) {
                if (costs[i] < costs[neighbour] && spilledInterval.isNeighbourSpilled(i) == false) {
                    neighbour = i;
                    found = true;
                }
            }
        }
        if (!found) {
            for (int i = 0; i < regions.size(); i++) {
                if (!regions.get(i).isEmpty()) {
                    if (costs[i] <= costs[neighbour]) {
                        neighbour = i;
                        found = true;
                    }
                }
            }
        }
        assert found == true : "must find edge to spill!";

        return neighbour;

    }

    private ArrayList<ArrayList<LifeRange>> getInterferceRegions(Interval spilledInterval, StackObject o) {

        Vector<Integer> neighbours = o.edges;

        ArrayList<ArrayList<LifeRange>> regions = new ArrayList<>();

        for (LifeRange interRange : spilledInterval.getLifeRanges()) {

            int interFrom = interRange.getFrom();
            int interTo = interRange.getTo();

            for (Integer i : neighbours) {

                while (i >= regions.size()) {
                    regions.add(new ArrayList<>());

                }

                Interval tempInter = intervalFor(i);

                ArrayList<LifeRange> tempRanges = tempInter.getLifeRanges();

                for (LifeRange tempRange : tempRanges) {

                    int tempFrom = tempRange.getFrom();
                    int tempTo = tempRange.getTo();
                    LifeRange interferenceRange;

                    if (tempFrom <= interTo && interFrom <= tempTo) { // overlap of life Ranges

                        if (tempFrom >= interFrom) { // tempFrom between interFrom and interTo
                            if (tempTo >= interTo) { // tempTo after interTo
                                interferenceRange = new LifeRange(i, tempFrom, interTo, null);

                            } else { // tempTo between interFrom and InterTo
                                interferenceRange = new LifeRange(i, tempFrom, tempTo, null);

                            }
                        } else {
                            if (tempTo >= interTo) { // tempTo after interTo
                                interferenceRange = new LifeRange(i, interFrom, tempTo, null);

                            } else {
                                interferenceRange = new LifeRange(i, interFrom, interTo, null);

                            }
                        }
                        ArrayList<LifeRange> interferenceRanges = regions.get(i);
                        if (interferenceRanges.size() > 0) {
                            LifeRange last = interferenceRanges.get(interferenceRanges.size() - 1);
                            if (interferenceRange.getFrom() >= last.getTo()) {
                                last.setTo(interferenceRange.getFrom());
                            } else {
                                interferenceRanges.add(interferenceRange);
                            }
                        } else {
                            interferenceRanges.add(interferenceRange);

                        }

                    }
                }
            }
        }

        return regions;

    }

    private void spill() {

        Interval spilledInterval = null;

        for (ArrayDeque<StackObject> s : spillStackArr) {
            while (!s.isEmpty()) {
                StackObject o = s.pop();
                Interval inter = intervalFor(o.id);
                assert o.spillCandidate;
                assert inter != null;

                spilledInterval = inter;

                assert !spilledInterval.isSpilled() : "Interval already spilled";
                debug.dump(1, lir, "Before spilling: %s", lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                debug.log("Choose %s for spilling", lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                FrameMapBuilder frameMapBuilder = lirGenRes.getFrameMapBuilder();
                VirtualStackSlot slot = frameMapBuilder.allocateSpillSlot(spilledInterval.getKind());
                spilledInterval.setSlot(slot);
                int def = spilledInterval.getDef();
                AbstractBlockBase<?> spillBlock;
                int spillPos;
                ArrayList<UsePosition> usePositions = spilledInterval.getUsePositions();
                spilledInterval.delRanges();

                debug.log(1, "Spilling %s def: %d priority: %s", getVarName(spilledInterval.getOpId()), def, usePositions.get(usePositions.size() - 1).getPriority());
                if (usePositions.get(usePositions.size() - 1).getPriority() == RegisterPriority.MustHaveRegister) {
                    spillBlock = getSpillBlock(def);
                    insertionBuffer.init(lir.getLIRforBlock(spillBlock));
                    spillPos = getSpillPos(spillBlock, spilledInterval.getDef());
                    spillPos++;
                    debug.log("Spilled def instId: %d at index: %d", spilledInterval.getDef(), spillPos);

                    assert spillPos != -1 : "Spill Position not found!";

                    lifeTimeAnalysis.addTemp(spilledInterval, def);

                    insertionBuffer.append(spillPos, spillMoveFactory.createMove(slot, spilledInterval.getOperand()));
                    insertionBuffer.finish();

                }

                for (int i = usePositions.size() - 1; i >= 0; i--) {
                    UsePosition pos = usePositions.get(i);

                    if (pos.getPriority() == RegisterPriority.MustHaveRegister) {
                        int current = pos.getPos();

                        if (current != def) {

                            if ((current & 1) != 0) {
                                current--;
                                lifeTimeAnalysis.addTemp(spilledInterval, current);
                            }

                            debug.log("ID: %s Def pos: %d spillPos: %d Must have register", lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()), spilledInterval.getDef(), current);

                            spillBlock = getSpillBlock(current);
                            spillPos = getSpillPos(spillBlock, current);

                            assert spillPos != -1 : "Spill Position not found!";
                            insertionBuffer.init(lir.getLIRforBlock(spillBlock));
                            insertionBuffer.append(spillPos, spillMoveFactory.createMove((AllocatableValue) spilledInterval.getOperand(), slot));
                            insertionBuffer.finish();

                            lifeTimeAnalysis.addTemp(spilledInterval, pos.getPos());

                        }
                    }
                }

                if (debug.isLogEnabled(1)) {

                    ArrayList<LifeRange> lifeRanges = spilledInterval.getLifeRanges();
                    debug.log(1, "%s was spilled. New LifeRanges:", lifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                    for (LifeRange range : lifeRanges) {
                        debug.log(1, "Range from: %d to: %d", range.getFrom(), range.getTo());
                    }

                }

            }
        }
        lifeTimeAnalysis.rebuildGraph();
        graphArr = lifeTimeAnalysis.getInterferencegraph();

    }

    private int getSpillPos(AbstractBlockBase<?> block, int instId) {

        List<LIRInstruction> instructions = lir.getLIRforBlock(block);

        for (int i = 0; i < instructions.size(); i++) {
            debug.log("get Spill position index: %d current instId: %d looking for instId: %d ", i, instructions.get(i).id(), instId);
            if (instructions.get(i).id() == instId || instructions.get(i).id() == instId + 1) {
                debug.log("Spill Pos found: %d", i);
                return i;
            }

        }

        debug.log("Spill Pos not found: %d", instId);
        return -1;
    }

    private AbstractBlockBase<?> getSpillBlock(int instId) {
        int first = -1;
        int last = -1;

        for (AbstractBlockBase<?> block : blocks) {
            first = getFirstLirInstructionId(block);
            last = getLastLirInstructionId(block);

            if (instId >= first && instId <= last) {
                return block;
            }
        }

        return null;
    }

    private boolean isSpillStackEmpty() {

        for (int i = 0; i < spillStackArr.size(); i++) {
            if (spillStackArr.get(i) != null) {
                if (!spillStackArr.get(i).isEmpty()) {
                    debug.log("foundColoring: SpillStack size: %d", spillStackArr.get(i).size());
                    return false;
                }
            }
        }

        return true;

    }

    private void initStack() {
        stackArr = new ArrayList<>(graphArr.length);
        spillStackArr = new ArrayList<>(graphArr.length);
        spilledStackArr = new ArrayList<>(graphArr.length);
        for (int i = 0; i < graphArr.length; i++) {

            stackArr.add(i, new ArrayDeque<>());
            spillStackArr.add(i, new ArrayDeque<>());
            spilledStackArr.add(i, new ArrayDeque<>());
            int[] temp = new int[1];
            temp[0] = -1;
            colorArr[i] = temp;
        }

    }

    private static int[] resizeArray(int[] small) {

        int[] temp = new int[small.length * 2];

        for (int i = 0; i < temp.length; i++) {
            temp[i] = -1;
        }

        System.arraycopy(small, 0, temp, 0, small.length);

        return temp;
    }

    private static Interval[] resizeArray(Interval[] small) {

        Interval[] temp = new Interval[small.length * 2];

        for (int i = 0; i < temp.length; i++) {
            temp[i] = null;
        }

        System.arraycopy(small, 0, temp, 0, small.length);

        return temp;
    }

    private void fillAllocRegs() {
        regCatToRegCatNum = lifeTimeAnalysis.getRegCatToRegCatNum();

        RegisterArray temp = registerAllocationConfig.getAllocatableRegisters();

        allocRegs = new RegisterArray[regCatToRegCatNum.size()];

        ArrayList<ArrayList<Register>> tempList = new ArrayList<>(allocRegs.length);
        for (int i = 0; i < regCatToRegCatNum.size(); i++) {
            tempList.add(i, new ArrayList<>());
        }

        for (int i = 0; i < temp.size(); i++) {
            Register r = temp.get(i);
            RegisterCategory cat = r.getRegisterCategory();

            if (regCatToRegCatNum.containsKey(cat)) {
                int catNum = (int) regCatToRegCatNum.get(cat);
                tempList.get(catNum).add(r);
            }
        }
        for (int i = 0; i < regCatToRegCatNum.size(); i++) {
            allocRegs[i] = new RegisterArray(tempList.get(i));
        }

    }

    private void simplify() {

        for (int i = 0; i < graphArr.length; i++) {

            int nRegs = 0;
            Interferencegraph graph = graphArr[i];
            ArrayDeque<StackObject> stack = stackArr.get(i);

            debug.log("Before simplify: graph.size():%d nRegs: %d", graph.size(), nRegs);

            RegisterArray regs = allocRegs[i];
            int[] colors = colorArr[i];
            int k = regs.size();

            BitSet[] nodes = graph.getNodeList();

            for (int j = 0; j < firstVariableNumber && j < nodes.length; j++) {
                if (nodes[j] != null) {
                    while (colors.length < j + 1) {

                        colors = resizeArray(colors);

                    }

                    int reg = regNumtoAllocRegNum(j, regs);
                    debug.log(" isRegister: %s regnum: %d", lifeTimeAnalysis.toString(j), reg);

                    if (reg == -1) {
                        debug.log("Fixed register not in allocatable Registers!");
                        debug.log(lifeTimeAnalysis.toString(j));

                    }

                    colors[j] = reg;

                    colorArr[i] = colors;
                    nRegs++;
                }

            }

            while (graph.size() > nRegs) {

                removeNodes(graph, stack, k);
                if (graph.size() > nRegs) {
                    chooseSpillCandidate(graph, stack, k);
                }

            }
            if (graph.size() != nRegs) {
                String s = "";
                int number = 0;
                nodes = graph.getNodeList();
                for (int v = 0; v < nodes.length; v++) {
                    if (nodes[v] != null) {
                        s += lifeTimeAnalysis.toStringGraph(v) + " ";
                        number++;
                    }
                }
                debug.log("%s size: %d ", s, number);
                debug.log("to many nodes still in Graph");
            }
            debug.log("After simplify: graph.size(): %d nRegs: %d", graph.size(), nRegs);

        }

    }

    private void removeNodes(Interferencegraph graph, ArrayDeque<StackObject> stack, int k) {
        BitSet[] nodes = graph.getNodeList();
        boolean nodeRemoved = true;
        while (nodeRemoved) {
            nodeRemoved = false;
            for (int j = firstVariableNumber; j < nodes.length; j++) {
                if (nodes[j] != null) {
                    Vector<Integer> edges = graph.getEdges(j);

                    if (edges.size() < k) {

                        stack.push(new StackObject(j, edges));
                        graph.removeNode(j);
                        nodeRemoved = true;
                    }
                }

            }
        }
    }

    private void chooseSpillCandidate(Interferencegraph graph, ArrayDeque<StackObject> stack, int k) {

        BitSet[] nodes = graph.getNodeList();
        int min = -1;
        int spillNode = -1;

        for (int j = firstVariableNumber; j < nodes.length; j++) {
            if (nodes[j] != null) {
                Vector<Integer> edges = graph.getEdges(j);
                assert edges.size() >= k;

                Interval inter = intervalFor(j);
                ArrayList<UsePosition> pos = inter.getUsePositions();

                int ratio = pos.size() / edges.size();

                if ((min < 0 || ratio < min) && (!intervalFor(j).isSpilled())) {
                    min = ratio;
                    spillNode = j;
                }

            }
        }
        if (spillNode == -1) {
            min = -1;
            spillNode = -1;

            for (int j = firstVariableNumber; j < nodes.length; j++) {
                if (nodes[j] != null) {
                    Vector<Integer> edges = graph.getEdges(j);
                    assert edges.size() >= k;

                    Interval inter = intervalFor(j);
                    ArrayList<UsePosition> pos = inter.getUsePositions();

                    int ratio = pos.size() / edges.size();

                    if ((min < 0 || ratio < min)) {
                        min = ratio;
                        spillNode = j;
                    }

                }
            }
        }

        assert spillNode != -1;

        stack.push(new StackObject(spillNode, graph.getEdges(spillNode), true));

        graph.removeNode(spillNode);

    }

    private void select() {

        for (int i = 0; i < graphArr.length; i++) {
            Interferencegraph graph = graphArr[i];
            ArrayDeque<StackObject> stack = stackArr.get(i);
            ArrayDeque<StackObject> spillStack = spillStackArr.get(i);
            RegisterArray regs = allocRegs[i];

            int[] colors = colorArr[i];
            int n = stack.size();

            for (int j = 0; j < n; j++) {
                StackObject o = stack.pop();

                while (colors.length < o.id + 1) {
                    colors = resizeArray(colors);
                }
                Interval inter = intervalFor(o.id);

                if (o.id < firstVariableNumber) { // if isRegister();

                    graph.addNode(o.id, o.edges);

                } else { // if isVarable();

                    graph.addNode(o.id, o.edges);
                    int temp = isVarColorAvailable(graph.getEdges(o.id), colors, regs);

                    if (temp != -1) {
                        inter.setLocation(regs.get(temp).asValue(inter.getKind()));

                        colors[o.id] = temp;
                        debug.log("Varableid + name: %s  ColorArr[?]: %d Color: %d Colors.size %d", lifeTimeAnalysis.toString(o.id), i, temp, colors.length);

                    } else {
                        foundColor = false;
                        graph.removeNode(o.id);

                        debug.log("Variable spill! : %s", lifeTimeAnalysis.toString(o.id));

                        if (!intervalFor(o.id).isSpilled() || Options.LIROptGcIrSpilling.getValue(getLIR().getOptions())) {
                            spillStack.push(o);
                        }

                        debug.log(1, "Variable spill! : %s neighbours: %d", lifeTimeAnalysis.toString(o.id), o.edges.size());

                    }

                }

            }
            colorArr[i] = colors;

        }

    }

    private static int isVarColorAvailable(Vector<Integer> edges, int[] colors, RegisterArray allocRegs) {

        for (int i = 0; i < allocRegs.size(); i++) {

            boolean found = false;
            for (int neighbour : edges) {

                if (colors[neighbour] != -1) {
                    if (colors[neighbour] == i) {
                        found = true;

                        break;

                    }
                }
            }
            if (!found) {

                return i;
            }

        }

        return -1;
    }

    private int regNumtoAllocRegNum(int reg, RegisterArray regs) {

        for (int i = 0; i < regs.size(); i++) {
            Register r = regs.get(i);
            if (r.equals(registers.get(reg))) {
                return i;
            }
        }

        return -1;
    }

    public int getFirstLirInstructionId(AbstractBlockBase<?> block) {
        int result = lir.getLIRforBlock(block).get(0).id();

        return result;
    }

    public int getLastLirInstructionId(AbstractBlockBase<?> block) {
        List<LIRInstruction> instructions = lir.getLIRforBlock(block);
        int result = instructions.get(instructions.size() - 1).id();

        return result;
    }

    private class StackObject {
        public Vector<Integer> edges;
        public int id;
        public boolean spillCandidate;

        StackObject(int id, Vector<Integer> edges) {
            this.id = id;
            this.edges = edges;
            spillCandidate = false;
        }

        StackObject(int id, Vector<Integer> edges, boolean spill) {
            this.id = id;
            this.edges = edges;
            spillCandidate = spill;
        }

    }

    public void resolveDataFlow() {
        GraphColoringResolveDataFlowPhase dataFlowResolver = new GraphColoringResolveDataFlowPhase(this);
        dataFlowResolver.resolveDataFlow();
    }

    public LIR getLIR() {
        return lir;
    }

    public int blockCount() {

        return blocks.length;
    }

    public MoveFactory getSpillMoveFactory() {

        return spillMoveFactory;
    }

    public RegisterArray getRegisters() {
        return registers;
    }

    public FrameMapBuilder getFrameMapBuilder() {

        return lirGenRes.getFrameMapBuilder();
    }

    public Interval[] getIntervals() {
        return intervals;
    }

    public Interval addValue(Value operand, int opId) {
        Interval v;
        while (intervals.length <= opId) {
            intervals = resizeArray(intervals);
        }
        if (intervals[opId] == null) {
            v = new Interval(operand, opId);
            intervals[opId] = v;
        } else {
            v = intervals[opId];
        }
        return v;
    }

    private void printGraph(String s) {
        for (int i = 0; i < graphArr.length; i++) {
            graphArr[i].printGraph(lifeTimeAnalysis, s);

        }
    }

    private void printColors() {

        for (int i = 0; i < colorArr.length; i++) {
            try {
                File f = new File(".." + File.separator + "Colors_" + i + "_" + lifeTimeAnalysis.getCompilationUnitName() + new Date().getTime() + ".txt");
                f.createNewFile();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
                writer.write("Graph: " + i + "\n");

                for (int j = 0; j < colorArr[i].length; j++) {
                    int temp = colorArr[i][j];
                    if (temp != -1) {

                        writer.write(lifeTimeAnalysis.toStringGraph(j) + " " + temp + "\n");
                    }

                }

                writer.flush();
                writer.close();
            } catch (FileNotFoundException e) {

                e.printStackTrace();
            } catch (IOException e) {

                e.printStackTrace();
            }
        }
    }

    void initOpIdMaps(int numInstructions) {
        opIdToInstructionMap = new LIRInstruction[numInstructions];
        opIdToBlockMap = new AbstractBlockBase<?>[numInstructions];
    }

    void putOpIdMaps(int index, LIRInstruction op, AbstractBlockBase<?> block) {
        opIdToInstructionMap[index] = op;
        opIdToBlockMap[index] = block;
    }

    int maxOpId() {
        assert opIdToInstructionMap.length > 0 : "no operations";
        return (opIdToInstructionMap.length - 1) << 1;
    }

    private static int opIdToIndex(int opId) {
        return opId >> 1;
    }

    public LIRInstruction instructionForId(int opId) {
        assert isEven(opId) : "opId not even";
        LIRInstruction instr = opIdToInstructionMap[opIdToIndex(opId)];
        assert instr.id() == opId;
        return instr;
    }

    public AbstractBlockBase<?> blockForId(int opId) {
        assert opIdToBlockMap.length > 0 && opId >= 0 && opId <= maxOpId() + 1 : "opId out of range";
        return opIdToBlockMap[opIdToIndex(opId)];
    }

    public Interval intervalFor(int operandNumber) {
        return intervals[operandNumber];

    }

    public boolean isProcessed(Value operand) {

        return !isRegister(operand) || attributes(asRegister(operand)).isAllocatable();
    }

    public RegisterAttributes attributes(Register reg) {
        return registerAllocationConfig.getRegisterConfig().getAttributesMap()[reg.number];
    }

    public boolean isAllocateable(int r) {

        return allocatable.get(r);

    }

    private static void printInterval(Interval interval, IntervalVisitor visitor) {
        Value hint = null;
        Value operand = interval.getOperand();
        String type = isRegister(operand) ? "fixed" : operand.getValueKind().getPlatformKind().toString();
        Value location = interval.getLocation();

        visitor.visitIntervalStart(operand, operand, location, hint, type);

        // print ranges

        ArrayList<LifeRange> ranges = interval.getLifeRanges();

        for (LifeRange range : ranges) {
            int from = range.getFrom();
            int to = range.getTo();
            if (to - from == 0) { // If live range is spilled, it has a length of 0. We set the
                // length to 1 in order to be shown in the c1 visualizer.
                to += 1;
            }

            visitor.visitRange(from, to);
        }

        for (UsePosition pos : interval.getUsePositions()) {
            visitor.visitUsePos(pos.getPos(), pos.getPriority().name());
        }

        visitor.visitIntervalEnd(null);
    }

    @Override
    public void visitIntervals(IntervalVisitor visitor) {

        for (int i = intervals.length - 1; i >= 0; i--) {
            Interval inter = intervals[i];
            if (inter != null) {
                printInterval(inter, visitor);
            }
        }
        if (spilledRegions != null) {
            Value v = new Value(LIRKind.Illegal) {
                @Override
                public String toString() {
                    return "spilledRanges";
                }
            };
            visitor.visitIntervalStart(v, v, v, null, null);
            for (LifeRange r : spilledRegions) {
                visitor.visitRange(r.getFrom(), r.getTo());
            }
            visitor.visitIntervalEnd(null);
        }
    }

    int operandSize() {
        return firstVariableNumber + lir.numVariables();
    }

    int numLoops() {
        return lir.getControlFlowGraph().getLoops().size();
    }

    public String getVarName(int opId) {

        return lifeTimeAnalysis.toString(opId);
    }
}
