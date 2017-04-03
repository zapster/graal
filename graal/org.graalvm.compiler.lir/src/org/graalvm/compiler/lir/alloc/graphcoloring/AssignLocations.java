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

import static org.graalvm.compiler.lir.LIRValueUtil.isVariable;

import java.util.ArrayList;
import java.util.HashMap;

import org.graalvm.compiler.core.common.alloc.RegisterAllocationConfig;
import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph;
import org.graalvm.compiler.debug.Debug;
import org.graalvm.compiler.lir.InstructionValueProcedure;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.alloc.graphcoloring.GraphColoringPhase.Options;
import org.graalvm.compiler.lir.alloc.graphcoloring.Interval.RegisterPriority;
import org.graalvm.compiler.lir.alloc.graphcoloring.Interval.UsePosition;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.Register.RegisterCategory;
import jdk.vm.ci.meta.Value;

public class AssignLocations {

    private RegisterAllocationConfig registerAllocationConfig;
    private int[][] colorArr;
    private RegisterArray[] allocRegs;
    private LIR lir;
    private AbstractControlFlowGraph<?> cfg;
    private Liveness life;
    private HashMap<RegisterCategory, Number> regCatToRegCatNum; // Integer statt number
    private Chaitin allocator;

    public AssignLocations(RegisterAllocationConfig registerAllocationConfig, int[][] colorArr, RegisterArray[] allocRegs, LIRGenerationResult lirGenRes, Liveness life, Chaitin allocator) {
        this.registerAllocationConfig = registerAllocationConfig;
        this.colorArr = colorArr;
        this.allocRegs = allocRegs;

        this.lir = lirGenRes.getLIR();
        this.life = life;
        this.allocator = allocator;

        cfg = lir.getControlFlowGraph();
        cfg.getBlocks();
        this.regCatToRegCatNum = life.getRegCatToRegCatNum();

    }

    public void run() {
        Debug.log(1, "\nBegin Assignment");
        assignRegs();
        Debug.log(1, "End Assignment");
    }

    private void assignRegs() {

        for (AbstractBlockBase<?> block : cfg.getBlocks()) {

            InstructionValueProcedure assignProc = (inst, operand, mode, flags) -> {
                if (isVariable(operand)) {
                    Interval inter = allocator.intervalFor(life.operandNumber(operand));
                    Value ret = null;
                    if (!inter.isSpilled()) {
                        ret = inter.getLocation();
                        if (inter.getLocation() == null) {
                            ret = getRegisterForValue(inst, operand).asValue(operand.getValueKind());
                            assert false;
                        }

                        // Register r = getRegisterForValue(inst, operand);
                        return ret; // r.asValue(operand.getLIRKind());
                    } else {
                        if (Options.LIROptGcIrSpilling.getValue()) {

                            if (inter.isSpilledRegion(inst.id())) {
                                RegisterPriority priority = findUsePosPriority(inst.id(), inter);
                                if (inst.id() == -1) { // move from or to stackslot, replace with
                                    // location!
                                    ret = inter.getLocation();

                                } else if (priority == RegisterPriority.MustHaveRegister) {

                                    ret = inter.getLocation();
                                } else {
                                    ret = inter.getSlot();
                                }
                            } else {
                                ret = inter.getLocation();
                                if (inter.getLocation() == null) {
                                    ret = getRegisterForValue(inst, operand).asValue(operand.getValueKind());
                                    assert false;
                                }
                            }

                        } else {

                            RegisterPriority priority = findUsePosPriority(inst.id(), inter);
                            if (inst.id() == -1) { // move from or to stackslot, replace with
                                                   // location!
                                ret = inter.getLocation();

                            } else if (priority == RegisterPriority.MustHaveRegister) {

                                ret = inter.getLocation();
                            } else {
                                ret = inter.getSlot();
                            }

                        }
                        return ret;
                    }
                }

                return operand;
            };
            InstructionValueProcedure stateProc = (inst, operand, mode, flags) -> {
                if (isVariable(operand)) {
                    Interval inter = allocator.intervalFor(life.operandNumber(operand));
                    Value ret;
                    if (inter.isSpilled()) {
                        ret = inter.getSlot();
                    } else {
                        ret = inter.getLocation();
                    }
                    if (inter.getLocation() == null) {
                        ret = getRegisterForValue(inst, operand).asValue(operand.getValueKind());
                        assert false;
                    }

                    // Register r = getRegisterForValue(inst, operand);
                    return ret; // r.asValue(operand.getLIRKind());
                }

                return operand;
            };

            for (LIRInstruction inst : lir.getLIRforBlock(block)) {
                inst.forEachInput(assignProc);
                inst.forEachAlive(assignProc);
                inst.forEachOutput(assignProc);
                inst.forEachTemp(assignProc);
                inst.forEachState(stateProc);
            }
        }

    }

    private RegisterPriority findUsePosPriority(int id, Interval inter) {
        ArrayList<UsePosition> usePositions = inter.getUsePositions();
        Debug.log(1, "Instruction id: %d %s", id, allocator.getVarName(inter.getOpId()));
        for (UsePosition pos : usePositions) {
            if (pos.getPos() == id || pos.getPos() == id + 1) {

                Debug.log(1, "Priority: %s", pos.getPriority());
                return pos.getPriority();

            }
        }
        Debug.log(1, "Priority: None");

        return RegisterPriority.None;

    }

    private Register getRegisterForValue(@SuppressWarnings("unused") LIRInstruction inst, Value operand) {
        int opId = life.operandNumber(operand);
        RegisterCategory cat = registerAllocationConfig.getAllocatableRegisters(operand.getPlatformKind()).allocatableRegisters[0].getRegisterCategory();
        int regCatNum = (int) regCatToRegCatNum.get(cat);
        int[] colors = colorArr[regCatNum];
        RegisterArray allocregs = allocRegs[regCatNum];
        int n = colors[opId];
        System.out.println("asssign location: " + life.toString(opId) + " ColorArr[?] " + regCatNum + " Color: " + n);
        Register r = allocregs.get(n);
        Debug.log("AssignLoc: %s RegCatNum: %d colors size: %d Color: %s", life.toStringGraph(opId), regCatNum, colors.length, r);
        return r;
    }

}
