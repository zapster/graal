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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;
import java.util.Vector;

import org.graalvm.compiler.core.common.alloc.RegisterAllocationConfig;
import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.Debug;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInsertionBuffer;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.VirtualStackSlot;
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
    private RegisterAllocationConfig registerAllocationConfig;
    private RegisterArray registers;
    private Liveness LifeTimeAnalysis;
    private Interferencegraph[] graphArr;
    private Stack<StackObject>[] stackArr;
    private RegisterArray[] allocRegs;
    private Stack<StackObject>[] spillStackArr;
    private Stack<StackObject>[] spilledStackArr;
    private HashMap<RegisterCategory, Number> regCatToRegCatNum;
    private int[][] colorArr;
    private int firstVariableNumber;
    private LIR lir;
// private ArrayList<Interval> ValueList;
    private Interval[] intervals;
    private LIRInstruction[] opIdToInstructionMap;
    private AbstractBlockBase<?>[] opIdToBlockMap;
    private LIRInsertionBuffer insertionBuffer;
    private RegisterArray allocatableRegs;
    private BitSet allocatable;
    private boolean foundColor;

    public Chaitin(TargetDescription target, LIRGenerationResult lirGenRes, MoveFactory spillMoveFactory, RegisterAllocationConfig registerAllocationConfig) {

        this.lirGenRes = lirGenRes;
        this.lir = lirGenRes.getLIR();

        this.spillMoveFactory = spillMoveFactory;
        this.registerAllocationConfig = registerAllocationConfig;
        this.registers = target.arch.getRegisters();
        this.firstVariableNumber = registers.size();
        this.allocatableRegs = registerAllocationConfig.getAllocatableRegisters();
        this.allocatable = new BitSet(registers.size());
// ValueList = new ArrayList<>();
        intervals = new Interval[10];
        initAllocRegs();
        this.LifeTimeAnalysis = new Liveness(registers, lirGenRes, registerAllocationConfig, this);
        this.graphArr = LifeTimeAnalysis.getInterferencegraph();
        this.stackArr = new Stack[graphArr.length];
        this.spillStackArr = new Stack[graphArr.length];
        this.spilledStackArr = new Stack[graphArr.length];
        this.colorArr = new int[graphArr.length][];
        this.insertionBuffer = new LIRInsertionBuffer();

        initStack();
        fillAllocRegs();
        this.regCatToRegCatNum = LifeTimeAnalysis.getRegCatToRegCatNum();
        Debug.log(LifeTimeAnalysis.getCompilationUnitName());
        Debug.log("Graph size: %d", graphArr[0].size());

        if (Debug.isLogEnabled()) {

            printGraph("Before Coloring");
            Debug.dump(1, lir, "Before Coloring");
            Debug.dump(1, this, "Before Coloring");
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
        while (!foundColor && !isSpillStackEmpty()) {

            tries++;
            Debug.log("CompilationUnitName: %s", LifeTimeAnalysis.getCompilationUnitName());
            Debug.dump(1, lir, "before spillntries: %d", tries);
            Debug.dump(1, this, "before spillntries: %d", tries);
            Debug.log("Spill beginn: ");

            spill();
            Debug.log("Spill end: ");

            colorGraph();

            Debug.dump(1, lir, "After spillntries: %d", tries);
            Debug.dump(1, this, "After spillntries: %d", tries);
            if (Debug.isLogEnabled()) {

                printGraph("After spillntries: " + tries);

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
            if (Debug.isLogEnabled(1)) {
                // LifeTimeAnalysis.rebuildGraph();
                printGraph("After Coloring");
                printColors();
            }
            throw new BailoutException("Every SpillCandidate spilled, no color found!");
        }

        // dumpLIR();

// if (foundColoring()) {
// Debug.log("CompilationUnitName: %s", LifeTimeAnalysis.getCompilationUnitName());
// new AssignLocations(registerAllocationConfig, colorArr, allocRegs, lirGenRes, LifeTimeAnalysis,
// this);
// Debug.log("end Assignment: ");
// Debug.log("Resolve DataFlow beginn: ");
// resolveDataFlow();
// Debug.log("Resolve DataFlow end: ");
//
// } else {
// Debug.log("Needs spilling, not yet handled");
// Debug.log("CompilationUnitName: %s", LifeTimeAnalysis.getCompilationUnitName());
//// printGraph();
// Debug.log("Spill beginn: ");
// spill();
// Debug.log("Spill end: ");
// new AssignLocations(registerAllocationConfig, colorArr, allocRegs, lirGenRes, LifeTimeAnalysis,
// this);
// Debug.log("end Assignment: ");
//
// Debug.log("Resolve DataFlow beginn: ");
// resolveDataFlow();
// Debug.log("Resolve DataFlow end: ");
// // throw new BailoutException("No coloring found!");
//
// }
        Debug.log("\nbeginn Assignment: ");
        AssignLocations assignLocations = new AssignLocations(registerAllocationConfig, colorArr, allocRegs, lirGenRes, LifeTimeAnalysis, this);
        assignLocations.run();
        Debug.log("end Assignment: ");
        Debug.dump(1, lir, "After AssignLocations");
        Debug.log("Resolve DataFlow beginn: ");
        resolveDataFlow();
        Debug.log("Resolve DataFlow end: ");
        // testGraph();

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
        Debug.log("begin simplify: ");
        simplify();
        Debug.log("end simplify: ");
// if (Debug.isLogEnabled()) {
//
// printGraph("After simplify");
//// }
// if
// (lirGenRes.getCompilationUnitName().equals("com.oracle.graal.jtt.loop.SpillLoopPhiVariableAtDefinition.test(int)"))
// {
// printGraph("After simplify");
// }

// System.out.println("start");
        Debug.log("\nbeginn select: ");
        select();
        Debug.log("end select: ");
// System.out.println("end");

        Debug.log("Graph size: %d", graphArr[0].size());
// if (Debug.isLogEnabled()) {
//
// printGraph("After select:");
// printColors();
// }

    }

    private void spill() {

        Interval spilledInterval = null;

        for (Stack<StackObject> s : spillStackArr) {
            while (!s.isEmpty()) {
                StackObject o = s.pop();
                Interval inter = intervalFor(o.id);
                assert o.spillCandidate;
                assert inter != null;

                // if (spilledInterval == null || (inter.getUsePositions().size() <
                // spilledInterval.getUsePositions().size() && !inter.isSpilled()) ||
                // spilledInterval.isSpilled()) {
                spilledInterval = inter;
                // }

                assert !spilledInterval.isSpilled() : "Interval already spilled";
                Debug.dump(1, lir, "Before spilling: %s", LifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                Debug.log("Choose %s for spilling", LifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                FrameMapBuilder frameMapBuilder = lirGenRes.getFrameMapBuilder();
                VirtualStackSlot slot = frameMapBuilder.allocateSpillSlot(spilledInterval.getKind());
                spilledInterval.setSlot(slot);
                int def = spilledInterval.getDef();
                AbstractBlockBase<?> spillBlock;
                int spillPos;
                ArrayList<UsePosition> usePositions = spilledInterval.getUsePositions();
                spilledInterval.delRanges();
// if (lirGenRes.getCompilationUnitName().equals("org.h2.index.TreeIndex.findFirstNode(SearchRow,
// boolean)")) {
// System.out.println("Spilling " + getVarName(spilledInterval.getOpId()) + " def: " + def + "
// priority: " + usePositions.get(usePositions.size() - 1).getPriority());
// }
                Debug.log(1, "Spilling %s def: %d priority: %s", getVarName(spilledInterval.getOpId()), def, usePositions.get(usePositions.size() - 1).getPriority());
                if (usePositions.get(usePositions.size() - 1).getPriority() == RegisterPriority.MustHaveRegister) {
                    spillBlock = getSpillBlock(def);
                    insertionBuffer.init(lir.getLIRforBlock(spillBlock));
                    spillPos = getSpillPos(spillBlock, spilledInterval.getDef());
                    spillPos++;
                    Debug.log("Spilled def instId: %d at index: %d", spilledInterval.getDef(), spillPos);
// if (lirGenRes.getCompilationUnitName().equals("org.h2.index.TreeIndex.findFirstNode(SearchRow,
// boolean)")) {
// System.out.println("Spilled def instId: " + spilledInterval.getDef() + " at index: " + spillPos);
// }

                    assert spillPos != -1 : "Spill Position not found!";

                    LifeTimeAnalysis.addTemp(spilledInterval, def);

                    insertionBuffer.append(spillPos, spillMoveFactory.createMove(slot, spilledInterval.getOperand()));
                    insertionBuffer.finish();

                }
// if (spilledInterval.getPriority().lessThan(RegisterPriority.MustHaveRegister)) {
//
// Debug.log("ID: %d Def pos: %d should have Register", spilledInterval.getOpId(),
// spilledInterval.getDef());
// spilledInterval.setLocation(slot);
//
// } else {

                for (int i = usePositions.size() - 1; i >= 0; i--) {
                    UsePosition pos = usePositions.get(i);

                    if (pos.getPriority() == RegisterPriority.MustHaveRegister) {
                        int current = pos.getPos();

                        if (current != def) {
// if (lirGenRes.getCompilationUnitName().equals("org.h2.index.TreeIndex.findFirstNode(SearchRow,
// boolean)")) {
// System.out.println("ID: " + LifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()) + " Def
// pos: " + spilledInterval.getDef() + " spillPos: " + current);
// }
                            if ((current & 1) != 0) {
                                current--;
                                LifeTimeAnalysis.addTemp(spilledInterval, current);
                            }

                            Debug.log("ID: %s Def pos: %d spillPos: %d Must have register", LifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()), spilledInterval.getDef(), current);

                            spillBlock = getSpillBlock(current);
                            spillPos = getSpillPos(spillBlock, current);

                            assert spillPos != -1 : "Spill Position not found!";
                            insertionBuffer.init(lir.getLIRforBlock(spillBlock));
                            insertionBuffer.append(spillPos, spillMoveFactory.createMove((AllocatableValue) spilledInterval.getOperand(), slot));
                            insertionBuffer.finish();
                            LifeTimeAnalysis.addTemp(spilledInterval, pos.getPos());

                        }
                    }
                }
// }
                if (Debug.isLogEnabled(1)) {

                    ArrayList<LifeRange> lifeRanges = spilledInterval.getLifeRanges();
                    Debug.log(1, "%s was spilled. New LifeRanges:", LifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()));
                    for (LifeRange range : lifeRanges) {
                        Debug.log(1, "Range from: %d to: %d", range.getFrom(), range.getTo());
                    }

                }

// if (lirGenRes.getCompilationUnitName().equals("org.h2.index.TreeIndex.findFirstNode(SearchRow,
// boolean)")) {
// ArrayList<LifeRange> lifeRanges = spilledInterval.getLifeRanges();
//
// System.out.println(LifeTimeAnalysis.toStringGraph(spilledInterval.getOpId()) + " was spilled. New
// LifeRanges:");
// for (LifeRange range : lifeRanges) {
//
// System.out.println("Range from: " + range.getFrom() + " to: " + range.getTo());
// }
// }

                // spilledInterval.delRanges();
            }
        }
        LifeTimeAnalysis.rebuildGraph();
        graphArr = LifeTimeAnalysis.getInterferencegraph();

    }

    private int getSpillPos(AbstractBlockBase<?> block, int instId) {

        List<LIRInstruction> instructions = lir.getLIRforBlock(block);

        for (int i = 0; i < instructions.size(); i++) {
            Debug.log("get Spill position index: %d current instId: %d looking for instId: %d ", i, instructions.get(i).id(), instId);
            if (instructions.get(i).id() == instId || instructions.get(i).id() == instId + 1) {
                Debug.log("Spill Pos found: %d", i);
                return i;
            }
// if (instructions.get(i).id() == instId + 1) {
// Debug.log("Spill Pos found: %d", i);
// return i;
// }
        }
// for (int i = 0; i < instructions.size(); i++) {
// Debug.log("get Spill position current instId: %d looking for instId: %d ",
// instructions.get(i).id(), instId);
// if (instructions.get(i).id() == instId + 1) {
// Debug.log("Spill Pos found: %d", i);
// return i;
// }
// }
        Debug.log("Spill Pos not found: %d", instId);
        return -1;
    }

    private AbstractBlockBase<?> getSpillBlock(int instId) {
        int first = -1;
        int last = -1;

        for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
            first = getFirstLirInstructionId(block);
            last = getLastLirInstructionId(block);

            if (instId >= first && instId <= last) {
                return block;
            }
        }

        return null;
    }

    private boolean isSpillStackEmpty() {

        for (int i = 0; i < spillStackArr.length; i++) {
            if (spillStackArr[i] != null) {
                if (!spillStackArr[i].empty()) {
                    Debug.log("foundColoring: SpillStack size: %d", spillStackArr[i].size());
                    return false;
                }
            }
        }

        return true;

    }

    private void initStack() {
        for (int i = 0; i < graphArr.length; i++) {
            stackArr[i] = new Stack<>();
            spillStackArr[i] = new Stack<>();
            spilledStackArr[i] = new Stack<>();
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
        regCatToRegCatNum = LifeTimeAnalysis.getRegCatToRegCatNum();

        RegisterArray temp = registerAllocationConfig.getAllocatableRegisters();

        allocRegs = new RegisterArray[regCatToRegCatNum.size()];

        ArrayList<Register>[] tempList = new ArrayList[allocRegs.length];
        for (int i = 0; i < regCatToRegCatNum.size(); i++) {
            tempList[i] = new ArrayList<>();
        }

        for (int i = 0; i < temp.size(); i++) {
            Register r = temp.get(i);
            RegisterCategory cat = r.getRegisterCategory();

            if (regCatToRegCatNum.containsKey(cat)) {
                int catNum = (int) regCatToRegCatNum.get(cat);
                tempList[catNum].add(r);
            }
        }
        for (int i = 0; i < regCatToRegCatNum.size(); i++) {
            allocRegs[i] = new RegisterArray(tempList[i]);
        }

    }

    private void simplify() {

        for (int i = 0; i < graphArr.length; i++) {

            int nRegs = 0;
            Interferencegraph graph = graphArr[i];
            Stack<StackObject> stack = stackArr[i];
            Stack<StackObject> spilledstack = spilledStackArr[i];
            // graph.printGraph(LifeTimeAnalysis);
            Debug.log("Before simplify: graph.size():%d nRegs: %d", graph.size(), nRegs);

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
                    Debug.log(" isRegister: %s regnum: %d", LifeTimeAnalysis.toString(j), reg);

                    if (reg == -1) {
                        Debug.log("Fixed register not in allocatable Registers!");
                        Debug.log(LifeTimeAnalysis.toString(j));
                        // System.out.println("Registers: " + regs);
                    }
                    // System.out.println("Colors length: " + colors.length + " j: " +
                    // j);
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
                        s += LifeTimeAnalysis.toStringGraph(v) + " ";
                        number++;
                    }
                }
                Debug.log("%s size: %d ", s, number);
                Debug.log("to many nodes still in Graph");
            }
            Debug.log("After simplify: graph.size(): %d nRegs: %d", graph.size(), nRegs);

        }

    }

    private void removeNodes(Interferencegraph graph, Stack<Chaitin.StackObject> stack, int k) {
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

    private void chooseSpillCandidate(Interferencegraph graph, Stack<Chaitin.StackObject> stack, int k) {

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

                if (min < 0 || ratio < min) {
                    min = ratio;
                    spillNode = j;
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
            Stack<StackObject> stack = stackArr[i];
            Stack<StackObject> spillStack = spillStackArr[i];
            RegisterArray regs = allocRegs[i];

            int[] colors = colorArr[i];
            int n = stack.size();

            for (int j = 0; j < n; j++) {
                StackObject o = stack.pop();

                while (colors.length < o.id + 1) {
                    colors = resizeArray(colors);
                }
                Interval inter = intervalFor(o.id);

                if (o.id < firstVariableNumber) {// if isRegister();
                    // probably unused
                    // int reg = regNumtoAllocRegNum(o.id, regs);

                    // System.out.println(" isRegister" + " op id: " + o.id + " regnum: " + reg);

                    graph.addNode(o.id, o.edges);

                } else {// if isVarable();

                    graph.addNode(o.id, o.edges);
                    int temp = isVarColorAvailable(graph.getEdges(o.id), colors, regs);

                    if (temp != -1) {
                        inter.setLocation(regs.get(temp).asValue(inter.getKind()));

                        colors[o.id] = temp;
                        Debug.log("Varableid + name: %s  ColorArr[?]: %d Color: %d Colors.size %d", LifeTimeAnalysis.toString(o.id), i, temp, colors.length);

                    } else {
                        foundColor = false;
                        graph.removeNode(o.id);

                        Debug.log("Variable spill! : %s", LifeTimeAnalysis.toString(o.id));
                        if (!intervalFor(o.id).isSpilled()) {
                            spillStack.push(o);
                        }

                        Debug.log(1, "Variable spill! : %s neighbours: %d", LifeTimeAnalysis.toString(o.id), o.edges.size());

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
// while (colors.length < neighbour + 1) {
// colors = resizeArray(colors);
// }
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

// private static boolean isRegColorAvailable(int reg, Vector<Integer> neighbours, int[] colors) {
//
// for (int n : neighbours) {
//
// if (colors[n] == reg) {
// return false;
// }
// }
// return true;
// }

    private int regNumtoAllocRegNum(int reg, RegisterArray regs) {

        for (int i = 0; i < regs.size(); i++) {
            Register r = regs.get(i);
            if (r.equals(registers.get(reg))) {
                return i;
            }
        }

        return -1;
    }

    // private void testGraph() {

// graph.addNode(0);
// graph.addNode(1);
// graph.addNode(2);
// graph.addNode(3);
// graph.addNode(4);
// graph.addNode(6);
// graph.removeNode(4);
//
// graph.setEdge(0, 1, true);
// graph.setEdge(0, 3, true);
// graph.setEdge(1, 2, true);
// graph.setEdge(3, 2, true);
// graph.setEdge(3, 4, true);
// graph.setEdge(1, 3, true);
//
// graph.printGraph();

    // graph = graphArr[0];

// for (int i = 0; i < graphArr.length; i++) {
// graphArr[i].printGraph(LifeTimeAnalysis);
//
// }

    // }

    public int getFirstLirInstructionId(AbstractBlockBase<?> block) {
        int result = lir.getLIRforBlock(block).get(0).id();
        // assert result >= 0;
        return result;
    }

    public int getLastLirInstructionId(AbstractBlockBase<?> block) {
        List<LIRInstruction> instructions = lir.getLIRforBlock(block);
        int result = instructions.get(instructions.size() - 1).id();
        // assert result >= 0;
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

        return lir.getControlFlowGraph().getBlocks().length;
    }

    public MoveFactory getSpillMoveFactory() {

        return spillMoveFactory;
    }

// public int operandSize() {
//
// return 0;
// }
//
// public Object getBlockData(AbstractBlockBase<?> toBlock) {
//
// return null;
// }

    public RegisterArray getRegisters() {
        return registers;
    }

    public FrameMapBuilder getFrameMapBuilder() {

        return lirGenRes.getFrameMapBuilder();
    }

// public ArrayList<Interval> getValueList() {
//
// return ValueList;
// }

    public Interval[] getIntervals() {
        return intervals;
    }

    public Interval AddValue(Value operand, int opId) {
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

    // ToDo: Umbau auf array mit op Id
// public Interval containsValue(int opId) {
// if (ValueList != null && !ValueList.isEmpty()) {
// for (Interval v : ValueList) {
// if (v.getOpId() == opId) {
// return v;
// }
// }
// }
// return null;
// }

// private void printGraph() {
// for (int i = 0; i < graphArr.length; i++) {
// graphArr[i].printGraph(LifeTimeAnalysis);
//
// }
// }

    private void printGraph(String s) {
        for (int i = 0; i < graphArr.length; i++) {
            graphArr[i].printGraph(LifeTimeAnalysis, s);

        }
    }

    private void printColors() {

        for (int i = 0; i < colorArr.length; i++) {
            try {
                File f = new File(".." + File.separator + "Colors_" + i + "_" + LifeTimeAnalysis.getCompilationUnitName() + new Date().getTime() + ".txt");
                f.createNewFile();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
                writer.write("Graph: " + i + "\n");
                for (int j = 0; j < colorArr[i].length; j++) {
                    int temp = colorArr[i][j];
                    if (temp != -1) {

                        writer.write(LifeTimeAnalysis.toStringGraph(j) + " " + temp + "\n");
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
        // char typeChar = operand.getPlatformKind().getTypeChar();
        // TODO: look again at visitMethod old: visitIntervalStart(operand, operand, null, hint,
        // type, typeChar);
        visitor.visitIntervalStart(operand, operand, null, hint, type);

        // print ranges

        ArrayList<LifeRange> ranges = interval.getLifeRanges();

        for (LifeRange range : ranges) {
            int from = range.getFrom();
            int to = range.getTo();
            if (to - from == 0) {// If live range is spilled, it has a length of 0. We set the
                                 // length to 1 in order to be shown in the c1 visualizer.
                to += 1;
            }

            visitor.visitRange(from, to);
        }
// LifeRange cur = interval.first();
//
// while (cur != LifeRange.EndMarker) {
//
// visitor.visitRange(cur.getFrom(), cur.getTo());
//
// cur = cur.getNext();
//
// }

        // print use positions
// int prev = -1;
// ArrayList<UsePosition> usePosList = interval.getUsePositions();

// for (int i = usePosList.size() - 1; i >= 0; i--) {
// UsePosition pos = usePosList.get(i);
// visitor.visitUsePos(pos.getPos(), pos.getPriority().name());
//
// }

// for (UsePosition pos : usePosList) {
// visitor.visitUsePos(pos.getPos(), pos.getPriority().name());
// }
// for (int i = usePosList.size() - 1; i >= 0; --i) {
// assert prev < usePosList.usePos(i) : "use positions not sorted";
// visitor.visitUsePos(usePosList.usePos(i), usePosList.registerPriority(i));
// prev = usePosList.usePos(i);
// }

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
// for (Interval interval : ValueList) {
// if (interval != null) {
// printInterval(interval, visitor);
// }
// }

    }

    int operandSize() {
        return firstVariableNumber + lir.numVariables();
    }

    int numLoops() {
        return lir.getControlFlowGraph().getLoops().size();
    }

// private void dumpLIR() {
//// TODO Keep for debugging
// for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
// Debug.log("/nBlock: %d", block.getId());
//
// InstructionValueConsumer consumer = (inst, operand, mode, flags) -> {
// Debug.log(" InstId: %d %s operand: %s ", inst.id(), inst.getLIRInstructionClass(), operand);
//
// };
//
// for (LIRInstruction inst : lir.getLIRforBlock(block)) {
//
// inst.visitEachInput(consumer);
// inst.visitEachAlive(consumer);
// inst.visitEachOutput(consumer);
// inst.visitEachTemp(consumer);
// inst.visitEachState(consumer);
//
// }
// }
// }

    public String getVarName(int opId) {

        return LifeTimeAnalysis.toString(opId);
    }
}
