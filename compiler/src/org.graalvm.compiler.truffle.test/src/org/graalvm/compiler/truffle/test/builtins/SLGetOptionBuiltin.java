/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.test.builtins;

import org.graalvm.compiler.options.OptionDescriptor;
import org.graalvm.compiler.truffle.common.TruffleCompilerOptions;
import org.graalvm.compiler.truffle.common.TruffleCompilerOptions_OptionDescriptors;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Looks up the value of an option in {@link TruffleCompilerOptions}. In the future this builtin
 * might be extended to lookup other options as well.
 */
@NodeInfo(shortName = "getOption")
public abstract class SLGetOptionBuiltin extends SLGraalRuntimeBuiltin {

    @Specialization
    @TruffleBoundary
    public Object getOption(String name) {
        TruffleCompilerOptions_OptionDescriptors options = new TruffleCompilerOptions_OptionDescriptors();
        for (OptionDescriptor option : options) {
            if (option.getName().equals(name)) {
                return convertValue(TruffleCompilerOptions.getValue(option.getOptionKey()));
            }
        }
        throw new SLAssertionError("No such option named \"" + name + "\" found in " + TruffleCompilerOptions.class.getName(), this);
    }

    private static Object convertValue(Object value) {
        // Improve this method as you need it.
        if (value instanceof Integer) {
            return (long) (int) value;
        }
        return value;
    }

}
