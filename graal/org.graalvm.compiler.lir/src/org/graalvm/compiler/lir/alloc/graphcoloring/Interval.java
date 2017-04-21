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

import java.util.ArrayList;
import java.util.BitSet;

import org.graalvm.compiler.core.common.util.IntList;
import org.graalvm.compiler.lir.VirtualStackSlot;
import org.graalvm.compiler.lir.alloc.graphcoloring.GraphColoringPhase.Options;

import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class Interval {

    private Value operand;
    private int opId;
    private ArrayList<LifeRange> lifeRanges;
    private ArrayList<UsePosition> usePositions;

    private IntList usePositions1;
    private int defPos;
    private int catNum;
    private LifeRange first;
    private VirtualStackSlot slot;
    private Value location;
    private ValueKind<?> kind;
    private RegisterPriority priority;
    private ArrayList<ArrayList<LifeRange>> interferenceRegions;
    private BitSet spilledNeighbours;

    public Interval(Value operand, int opId) {
        this.operand = operand;
        this.opId = opId;
        lifeRanges = new ArrayList<>();

        usePositions = new ArrayList<>();
        usePositions1 = new IntList(0);
        spilledNeighbours = new BitSet();

        defPos = -1;
        catNum = -1;
        this.first = LifeRange.EndMarker;
        this.setSlot(null);
        this.kind = null;
        this.setLocation(null);
        this.setPriority(RegisterPriority.None);

    }

    public void setCatNum(int catNum) {
        this.catNum = catNum;
    }

    public int getCatNum() {
        return catNum;
    }

    public ArrayList<LifeRange> getLifeRanges() {
        return lifeRanges;
    }

    public Value getOperand() {

        return operand;
    }

    public void setNeighbourSpilled(int n, boolean set) {
        spilledNeighbours.set(n, set);
    }

    public boolean isNeighbourSpilled(int n) {
        return spilledNeighbours.get(n);
    }

    public void addUse(int pos, RegisterPriority prio) {

        usePositions.add(new UsePosition(pos, prio));
        usePositions1.add(pos);

    }

    public ArrayList<UsePosition> getUsePositions() {
        return usePositions;
    }

    public IntList getUsePositions1() {
        return usePositions1;
    }
// public void addTemp(int pos) {
// usePositions.add(pos);
//
// }

    public void delRanges() {
        lifeRanges = new ArrayList<>();
        this.first = LifeRange.EndMarker;
    }

    public void addDef(int id) {
        // assert defPos == -1;
        defPos = id;

    }

    public int getDef() {
        return defPos;
    }

    public LifeRange first() {

        return first;
    }

    public void addTempRange(int from, int to) {
        LifeRange last = first();
        first = new LifeRange(last.getId() + 1, from, to, last);
        lifeRanges.add(first);
// Debug.log(1, "Temp Range of: %d added from:%d to:%d", opId, from, to);
    }

    public void addLiveRange(int from, int to) {
        if (Options.LIROptGcIrSpilling.getValue()) {
            assert from <= to : "invalid range";
        } else {
            assert from < to : "invalid range";
        }
        if (first.getFrom() <= to) {

            first.setFrom((from < first.getFrom()) ? from : first.getFrom());

            first.setTo((to > first.getTo()) ? to : first.getTo());
// Debug.log(1, "Life Range of: %d added from:%d to:%d", opId, first.getFrom(), first.getTo());

        } else {
            LifeRange last = first();
            first = new LifeRange(last.getId() + 1, from, to, last);
            lifeRanges.add(first);
// Debug.log(1, "Life Range of: %d added from:%d to:%d", opId, first.getFrom(), first.getTo());
        }

    }

    public int getOpId() {
        return opId;
    }

    public boolean hasInterference(LifeRange currentRange, boolean spilledRange) {
        for (LifeRange temp : lifeRanges) {

            int tempFrom = temp.getFrom();
            int tempTo = temp.getTo();
            int curFrom = currentRange.getFrom();
            int curTo = currentRange.getTo();

            if (isSpilled() && spilledRange) { // both spilled

                if (tempFrom == curFrom) {
                    return true;
                }
            }
            if (isSpilled()) {

                if (curFrom < tempFrom && tempFrom <= curTo) { // a between x and y
                    return true;
                }
            }
            if (spilledRange) {
                if (tempFrom < curFrom && curFrom <= tempTo) { // x between a and b
                    return true;
                }
            } else {

// if (!isSpilled() && !spilledRange) {
                if (tempFrom <= curFrom && curFrom <= tempTo) { // x between a and b
// Debug.log("true");
                    return true;
                } else if (tempFrom <= curTo && curTo <= tempTo) { // y between a and b
// Debug.log("true");
                    return true;
                } else if (curFrom <= tempFrom && tempFrom <= curTo) { // a between x and y
// Debug.log("true");
                    return true;
                } else if (curFrom <= tempTo && tempTo <= curTo) { // b between x and y
// Debug.log("true");
                    return true;

                }
            }
        }

        return false;
    }

    public VirtualStackSlot getSlot() {
        return slot;
    }

    public void setSlot(VirtualStackSlot slot) {
        this.slot = slot;
    }

    public void setKind(ValueKind<?> valueKind) {
        this.kind = valueKind;

    }

    public ValueKind<?> getKind() {

        return kind;
    }

    public Value getLocation() {
        return location;
    }

    public void setLocation(Value location) {
        this.location = location;
    }

    public RegisterPriority getPriority() {
        return priority;
    }

    public void setPriority(RegisterPriority priority) {

        // if (this.priority.lessThan(priority)) {
        this.priority = priority;
        // }
    }

    public boolean isSpilled() {
        return slot != null;
    }

    public ArrayList<ArrayList<LifeRange>> getInterferenceRegions() {
        return interferenceRegions;
    }

    public void setInterferenceRegions(ArrayList<ArrayList<LifeRange>> interferenceRegions) {
        this.interferenceRegions = interferenceRegions;
    }

    @Override
    public String toString() {
        return operand.toString();
    }

    public enum RegisterPriority {
        /**
         * No special reason for an interval to be allocated a register.
         */
        None,

        /**
         * Priority level for intervals live at the end of a loop.
         */
        LiveAtLoopEnd,

        /**
         * Priority level for intervals that should be allocated to a register.
         */
        ShouldHaveRegister,

        /**
         * Priority level for intervals that must be allocated to a register.
         */
        MustHaveRegister;

        public static final RegisterPriority[] VALUES = values();

        /**
         * Determines if this priority is higher than or equal to a given priority.
         */
        public boolean greaterEqual(RegisterPriority other) {
            return ordinal() >= other.ordinal();
        }

        /**
         * Determines if this priority is lower than a given priority.
         */
        public boolean lessThan(RegisterPriority other) {
            return ordinal() < other.ordinal();
        }
    }

    public class UsePosition {
        private boolean restored;
        private int pos;
        private RegisterPriority priority;

        public UsePosition(int pos, RegisterPriority priority) {
            this.pos = pos;
            this.priority = priority;
            this.restored = false;
        }

        public int getPos() {
            return pos;
        }

        public RegisterPriority getPriority() {
            return priority;
        }

        public boolean isRestored() {
            return restored;
        }

        public void setRestored(boolean set) {
            restored = set;
        }

        @Override
        public String toString() {
            return pos + " " + priority.toString();
        }
    }

    public boolean isInLiveRange(int id) {
        for (LifeRange range : lifeRanges) {
            if (id >= range.getFrom() && id <= range.getTo()) {
                return true;
            }
        }
        return false;
    }

}
