/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.component.installer;

import org.graalvm.component.installer.model.ComponentRegistry;
import java.nio.file.Path;

/**
 * Provides access to command line parameters and useful variables.
 */
public interface CommandInput {
    /**
     * Iterates existingFiles on command line.
     * 
     * @return next file from commandline
     * @throws FailedOperationException if the named file does not exist.
     */
    Iterable<ComponentParam> existingFiles() throws FailedOperationException;

    /**
     * Retrieves the next required parameter.
     * 
     * @return parameter text
     * @throws FailedOperationException if the parameter is missing.
     */
    String requiredParameter() throws FailedOperationException;

    /**
     * Returns the next parameter or {@code null} if all parameters were read.
     * 
     * @return parametr text or {@code null}
     */
    String nextParameter();

    /**
     * Has some parameters ?
     */
    boolean hasParameter();

    /**
     * Path to the GraalVM installation. The value is already sanity-checked and represents a
     * directory.
     * 
     * @return Path to GraalVM installation.
     */
    Path getGraalHomePath();

    /**
     * @return Registry of available components.
     */
    ComponentRegistry getRegistry();

    /**
     * @return Registry of local components.
     */
    ComponentRegistry getLocalRegistry();

    /**
     * Access to option parameters. Empty (non-{@code null} String} is returned for parameter-less
     * options.
     * 
     * @param option value of the option.
     * @return option value; {@code null}, if the option is not present
     */
    String optValue(String option);
}
