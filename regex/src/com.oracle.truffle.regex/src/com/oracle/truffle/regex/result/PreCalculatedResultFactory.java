/*
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.truffle.regex.result;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.regex.RegexObject;
import com.oracle.truffle.regex.tregex.util.DebugUtil;

import java.util.Arrays;

/**
 * Predefined lists of capture group start and end indices. Used for regular expressions like
 * /(\w)(\d)/
 */
public final class PreCalculatedResultFactory {

    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] indices;
    @CompilerDirectives.CompilationFinal private int length;

    public PreCalculatedResultFactory(int nGroups) {
        this.indices = new int[nGroups * 2];
        Arrays.fill(this.indices, -1);
    }

    private PreCalculatedResultFactory(int[] indices, int length) {
        this.indices = indices;
        this.length = length;
    }

    public PreCalculatedResultFactory copy() {
        return new PreCalculatedResultFactory(Arrays.copyOf(indices, indices.length), length);
    }

    public int getStart(int groupNr) {
        return indices[groupNr * 2];
    }

    public void setStart(int groupNr, int value) {
        indices[groupNr * 2] = value;
    }

    public int getEnd(int groupNr) {
        return indices[(groupNr * 2) + 1];
    }

    public void setEnd(int groupNr, int value) {
        indices[(groupNr * 2) + 1] = value;
    }

    /**
     * Outermost bounds of the result, necessary for expressions where lookaround matches may exceed
     * the bounds of capture group 0.
     */
    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public void updateIndices(byte[] updateIndices, int index) {
        for (byte b : updateIndices) {
            int i = Byte.toUnsignedInt(b);
            indices[i] = index;
        }
    }

    public RegexResult createFromStart(RegexObject regex, Object input, int start) {
        return createFromOffset(regex, input, start - indices[0]);
    }

    public RegexResult createFromEnd(RegexObject regex, Object input, int end) {
        return createFromOffset(regex, input, end - length);
    }

    public int getNumberOfGroups() {
        return indices.length / 2;
    }

    private RegexResult createFromOffset(RegexObject regex, Object input, int offset) {
        final int[] realIndices = new int[indices.length];
        applyOffset(realIndices, offset);
        return new SingleIndexArrayResult(regex, input, realIndices);
    }

    public void applyRelativeToEnd(int[] target, int end) {
        applyOffset(target, end - length);
    }

    private void applyOffset(int[] target, int offset) {
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] == -1) {
                target[i] = -1;
            } else {
                target[i] = indices[i] + offset;
            }
        }
    }

    @Override
    public int hashCode() {
        return length * 31 + Arrays.hashCode(indices);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PreCalculatedResultFactory)) {
            return false;
        }
        PreCalculatedResultFactory o = (PreCalculatedResultFactory) obj;
        return length == o.length && Arrays.equals(indices, o.indices);
    }

    public DebugUtil.Table toTable() {
        return new DebugUtil.Table("IndicesResultFactory",
                        new DebugUtil.Value("indices", Arrays.toString(indices)),
                        new DebugUtil.Value("length", length));
    }
}
