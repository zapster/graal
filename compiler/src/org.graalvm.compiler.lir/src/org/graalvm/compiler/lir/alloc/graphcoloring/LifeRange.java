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

public class LifeRange {
    private final int id;
    private int from;
    private int to;
    private LifeRange next;

    public static final LifeRange EndMarker = new LifeRange(-1, Integer.MAX_VALUE, Integer.MAX_VALUE, null);

    public LifeRange(int id, int from, int to, LifeRange lifeRange) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.next = lifeRange;

    }

    public int getTo() {
        return to;
    }

    public void setTo(int to) {
        this.to = to;
    }

    public int getFrom() {
        return from;
    }

    public void setFrom(int from) {
        this.from = from;
    }

    public int getId() {
        return id;
    }

    public LifeRange getNext() {
        return next;
    }

    public void setNext(LifeRange next) {
        this.next = next;
    }

    @Override
    public String toString() {
        return "from: " + from + " to: " + to;
    }
}
