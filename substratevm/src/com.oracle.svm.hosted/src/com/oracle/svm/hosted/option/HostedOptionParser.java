/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.option;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.graalvm.compiler.options.OptionDescriptor;
import org.graalvm.compiler.options.OptionDescriptors;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.util.EconomicMap;
import org.graalvm.util.EconomicSet;

import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.option.RuntimeOptionKey;
import com.oracle.svm.core.option.SubstrateOptionsParser;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.ImageClassLoader;

public class HostedOptionParser implements HostedOptionProvider {

    public static final String HOSTED_OPTION_PREFIX = "-H:";
    public static final String RUNTIME_OPTION_PREFIX = "-R:";

    private EconomicMap<OptionKey<?>, Object> hostedValues = OptionValues.newOptionMap();
    private EconomicMap<OptionKey<?>, Object> runtimeValues = OptionValues.newOptionMap();
    private SortedMap<String, OptionDescriptor> allHostedOptions = new TreeMap<>();
    private SortedMap<String, OptionDescriptor> allRuntimeOptions = new TreeMap<>();

    public HostedOptionParser(ImageClassLoader imageClassLoader) {
        List<Class<? extends OptionDescriptors>> optionsClasses = imageClassLoader.findSubclasses(OptionDescriptors.class);
        for (Class<? extends OptionDescriptors> optionsClass : optionsClasses) {
            if (Modifier.isAbstract(optionsClass.getModifiers())) {
                continue;
            }

            OptionDescriptors descriptors;
            try {
                descriptors = optionsClass.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw shouldNotReachHere(ex);
            }
            for (OptionDescriptor descriptor : descriptors) {
                String name = descriptor.getName();

                if (descriptor.getDeclaringClass().getAnnotation(Platforms.class) != null) {
                    throw UserError.abort("Options must not be declared in a class that has a @" + Platforms.class.getSimpleName() + " annotation: option " + name + " declared in " +
                                    descriptor.getDeclaringClass().getTypeName());
                }

                if (!(descriptor.getOptionKey() instanceof RuntimeOptionKey)) {
                    OptionDescriptor existing = allHostedOptions.put(name, descriptor);
                    if (existing != null) {
                        throw shouldNotReachHere("Option name \"" + name + "\" has multiple definitions: " + existing.getLocation() + " and " + descriptor.getLocation());
                    }
                }
                if (!(descriptor.getOptionKey() instanceof HostedOptionKey)) {
                    OptionDescriptor existing = allRuntimeOptions.put(name, descriptor);
                    if (existing != null) {
                        throw shouldNotReachHere("Option name \"" + name + "\" has multiple definitions: " + existing.getLocation() + " and " + descriptor.getLocation());
                    }
                }
            }

        }
    }

    public String[] parse(String[] args) {

        List<String> remainingArgs = new ArrayList<>();
        Set<String> errors = new HashSet<>();
        for (String arg : args) {
            boolean isImageBuildOption = SubstrateOptionsParser.parseHostedOption(HOSTED_OPTION_PREFIX, "SVM hosted", allHostedOptions, hostedValues, errors, arg, System.out) ||
                            SubstrateOptionsParser.parseHostedOption(RUNTIME_OPTION_PREFIX, "SVM runtime", allRuntimeOptions, runtimeValues, errors, arg, System.out);
            if (!isImageBuildOption) {
                remainingArgs.add(arg);
            }
        }
        if (!errors.isEmpty()) {
            throw UserError.abort(errors);
        }

        /*
         * We cannot prevent that runtime-only options are accessed during native image generation.
         * However, we set these options to null here, so that at least they do not have a sensible
         * value.
         */
        for (OptionDescriptor descriptor : allRuntimeOptions.values()) {
            if (!allHostedOptions.containsValue(descriptor)) {
                hostedValues.put(descriptor.getOptionKey(), null);
            }
        }

        return remainingArgs.toArray(new String[remainingArgs.size()]);
    }

    @Override
    public EconomicMap<OptionKey<?>, Object> getHostedValues() {
        return hostedValues;
    }

    @Override
    public EconomicMap<OptionKey<?>, Object> getRuntimeValues() {
        return runtimeValues;
    }

    public EconomicSet<String> getRuntimeOptionNames() {
        EconomicSet<String> res = EconomicSet.create(allRuntimeOptions.size());
        allRuntimeOptions.keySet().forEach(res::add);
        return res;
    }

    /**
     * Returns a string to be used on command line to set the option to a desirable value.
     *
     * @param option for which the command line argument is created
     * @return recommendation for setting a option value (e.g., for option 'Name' and value 'file'
     *         it returns "-H:Name=file")
     */
    public static String commandArgument(OptionKey<?> option, String value) {
        if (option.getDescriptor().getType() == Boolean.class) {
            assert value.equals("+") || value.equals("-") || value.equals("[+|-]") : "Boolean option can be only + or - or [+|-].";
            return HOSTED_OPTION_PREFIX + value + option;
        } else {
            return HOSTED_OPTION_PREFIX + option.getName() + "=" + value;
        }
    }
}
