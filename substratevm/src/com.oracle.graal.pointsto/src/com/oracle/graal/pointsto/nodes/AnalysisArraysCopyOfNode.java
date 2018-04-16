/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.pointsto.nodes;

import static org.graalvm.compiler.nodeinfo.NodeCycles.CYCLES_IGNORED;
import static org.graalvm.compiler.nodeinfo.NodeSize.SIZE_IGNORED;

import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.spi.ArrayLengthProvider;

@NodeInfo(size = SIZE_IGNORED, cycles = CYCLES_IGNORED)
public class AnalysisArraysCopyOfNode extends FixedWithNextNode implements ArrayLengthProvider {
    public static final NodeClass<AnalysisArraysCopyOfNode> TYPE = NodeClass.create(AnalysisArraysCopyOfNode.class);

    @Input ValueNode original;
    @Input ValueNode newLength;
    @OptionalInput ValueNode newArrayType;

    public AnalysisArraysCopyOfNode(@InjectedNodeParameter Stamp stamp, ValueNode original, ValueNode newLength) {
        this(stamp, original, newLength, null);
    }

    public AnalysisArraysCopyOfNode(@InjectedNodeParameter Stamp stamp, ValueNode original, ValueNode newLength, ValueNode newArrayType) {
        super(TYPE, computeStamp(stamp));
        this.original = original;
        this.newLength = newLength;
        this.newArrayType = newArrayType;
    }

    public ValueNode getOriginal() {
        return original;
    }

    public ValueNode getNewArrayType() {
        return newArrayType;
    }

    @Override
    public ValueNode length() {
        return newLength;
    }

    private static Stamp computeStamp(Stamp result) {
        if (result instanceof ObjectStamp) {
            return result.join(StampFactory.objectNonNull());
        }
        return result;
    }

}
