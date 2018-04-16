/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.phases;

import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.NodePlugin;
import org.graalvm.compiler.nodes.util.ConstantFoldUtil;

import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.svm.core.meta.SubstrateObjectConstant;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;

public final class ConstantFoldLoadFieldPlugin implements NodePlugin {

    @Override
    public boolean handleLoadField(GraphBuilderContext b, ValueNode receiver, ResolvedJavaField field) {
        if (receiver.isConstant()) {
            JavaConstant asJavaConstant = receiver.asJavaConstant();
            return tryConstantFold(b, field, asJavaConstant);
        }
        return false;
    }

    @Override
    public boolean handleLoadStaticField(GraphBuilderContext b, ResolvedJavaField staticField) {
        return tryConstantFold(b, staticField, null);
    }

    private static boolean tryConstantFold(GraphBuilderContext b, ResolvedJavaField field, JavaConstant receiver) {
        ConstantNode result = ConstantFoldUtil.tryConstantFold(b.getConstantFieldProvider(), b.getConstantReflection(), b.getMetaAccess(), field, receiver, b.getOptions());

        if (result != null) {
            JavaConstant value = result.asJavaConstant();
            if (b.getMetaAccess() instanceof AnalysisMetaAccess && value.getJavaKind() == JavaKind.Object && value.isNonNull()) {

                SubstrateObjectConstant sValue = (SubstrateObjectConstant) value;
                SubstrateObjectConstant sReceiver = (SubstrateObjectConstant) receiver;

                Object root;
                if (receiver == null) {
                    /* Found a root, map the constant value to the root field. */
                    root = field;
                } else {
                    /* Map the constant value to the root field of it's receiver. */
                    root = sReceiver.getRoot();
                    assert root != null : receiver.toValueString() + " : " + field + " : " + b.getGraph();
                }
                sValue.setRoot(root);
            }

            result = b.getGraph().unique(result);
            b.push(field.getJavaKind(), result);
            return true;
        }
        return false;
    }
}
