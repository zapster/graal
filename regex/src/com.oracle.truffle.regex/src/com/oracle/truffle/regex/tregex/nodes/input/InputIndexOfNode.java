/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.regex.tregex.nodes.input;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;

public abstract class InputIndexOfNode extends Node {

    public static InputIndexOfNode create() {
        return InputIndexOfNodeGen.create();
    }

    public abstract int execute(Object input, int fromIndex, int maxIndex, char[] chars);

    @Specialization
    public int indexOf(String input, int fromIndex, int maxIndex, char[] chars) {
        assert chars.length == 1;
        int index = input.indexOf(chars[0], fromIndex);
        if (index >= maxIndex) {
            return -1;
        }
        return index;
    }

    @Specialization
    public int indexOf(TruffleObject input, int fromIndex, int maxIndex, char[] chars,
                    @Cached("create()") InputCharAtNode charAtNode) {
        for (int i = fromIndex; i < maxIndex; i++) {
            char c = charAtNode.execute(input, i);
            for (char v : chars) {
                if (c == v) {
                    return i;
                }
            }
        }
        return -1;
    }
}
