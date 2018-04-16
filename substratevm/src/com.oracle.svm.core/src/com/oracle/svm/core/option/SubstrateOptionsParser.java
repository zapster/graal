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
package com.oracle.svm.core.option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.graalvm.collections.EconomicMap;
import org.graalvm.compiler.core.CompilationWrapper;
import org.graalvm.compiler.options.OptionDescriptor;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.options.OptionsParser;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.util.InterruptImageBuilding;
import com.oracle.svm.core.util.VMError;

/**
 * This class contains methods for parsing options and matching them against
 * {@link OptionDescriptor}s.
 */
public class SubstrateOptionsParser {

    public static final String HOSTED_OPTION_PREFIX = "-H:";
    public static final String RUNTIME_OPTION_PREFIX = "-R:";

    /**
     * The result of {@link SubstrateOptionsParser#parseOption}.
     */
    static final class OptionParseResult {
        private final EnumSet<OptionType> printFlags;
        private final String error;

        private OptionParseResult(EnumSet<OptionType> printFlags, String error) {
            this.printFlags = printFlags;
            this.error = error;
        }

        static OptionParseResult error(String message) {
            return new OptionParseResult(EnumSet.noneOf(OptionType.class), message);
        }

        static OptionParseResult correct() {
            return new OptionParseResult(EnumSet.noneOf(OptionType.class), null);
        }

        static OptionParseResult printFlags(EnumSet<OptionType> selectedOptionTypes) {
            return new OptionParseResult(selectedOptionTypes, null);
        }

        boolean printFlags() {
            return !printFlags.isEmpty();
        }

        public boolean isValid() {
            return printFlags.isEmpty() && error == null;
        }

        public String getError() {
            return error;
        }

        private boolean matchesFlags(OptionDescriptor d, boolean svmOption) {
            boolean showAll = printFlags.equals(EnumSet.allOf(OptionType.class));
            return showAll || svmOption && printFlags.contains(d.getOptionType());
        }

        boolean matchesFlagsRuntime(OptionDescriptor d) {
            return matchesFlags(d, d.getOptionKey() instanceof RuntimeOptionKey);
        }

        boolean matchesFlagsHosted(OptionDescriptor d) {
            OptionKey<?> key = d.getOptionKey();
            return matchesFlags(d, key instanceof RuntimeOptionKey || key instanceof HostedOptionKey);
        }
    }

    /**
     * Constants denoting supported boolean option formats.
     */
    public enum BooleanOptionFormat {
        NAME_VALUE("<name>=<value>"),
        PLUS_MINUS("+/-<name>");
        BooleanOptionFormat(String help) {
            this.help = help;
        }

        private final String help;

        @Override
        public String toString() {
            return help;
        }
    }

    static OptionParseResult parseOption(SortedMap<String, OptionDescriptor> options, String option, EconomicMap<OptionKey<?>, Object> valuesMap, String optionPrefix,
                    BooleanOptionFormat booleanOptionFormat) {
        if (option.length() == 0) {
            return OptionParseResult.error("Option name must be specified");
        }

        String optionName;
        Object value = null;
        String valueString = null;

        char first = option.charAt(0);
        int eqIndex = option.indexOf('=');
        if (first == '+' || first == '-') {
            if (eqIndex != -1) {
                return OptionParseResult.error("Cannot mix +/- with <name>=<value> format: '" + optionPrefix + option + "'");
            }
            optionName = option.substring(1, eqIndex == -1 ? option.length() : eqIndex);
            if (booleanOptionFormat == BooleanOptionFormat.NAME_VALUE) {
                return OptionParseResult.error("Option '" + optionName + "' must use <name>=<value> format, not +/- prefix");
            }
            value = (first == '+');
        } else {
            if (eqIndex == -1) {
                optionName = option;
                valueString = null;
            } else {
                optionName = option.substring(0, eqIndex);
                valueString = option.substring(eqIndex + 1);
            }
        }

        OptionDescriptor desc = options.get(optionName);
        if (desc == null && value != null) {
            if (eqIndex != -1) {
                optionName = option.substring(1, eqIndex);
                desc = options.get(optionName);
            }
        }

        if (desc == null) {
            List<OptionDescriptor> matches = new ArrayList<>();
            OptionsParser.collectFuzzyMatches(options.values(), optionName, matches);
            StringBuilder msg = new StringBuilder("Could not find option '").append(optionName).append('\'');
            if (!matches.isEmpty()) {
                msg.append(". Did you mean one of these:");
                for (OptionDescriptor match : matches) {
                    msg.append(' ').append(match.getName());
                }
            }
            msg.append(". Use " + optionPrefix + SubstrateOptions.PrintFlags.getName() + "= to list all available options.");
            return OptionParseResult.error(msg.toString());
        }

        Class<?> optionType = desc.getOptionValueType();

        if (value == null) {
            if (optionType == Boolean.class && booleanOptionFormat == BooleanOptionFormat.PLUS_MINUS) {
                return OptionParseResult.error("Boolean option '" + optionName + "' must have +/- prefix");
            }
            if (valueString == null) {
                return OptionParseResult.error("Missing value for option '" + optionName + "'");
            }

            try {
                if (optionType == Integer.class) {
                    long longValue = parseLong(valueString);
                    if ((int) longValue != longValue) {
                        return OptionParseResult.error("Wrong value for option '" + optionName + "': '" + valueString + "' is not a valid number");
                    }
                    value = (int) longValue;
                } else if (optionType == Long.class) {
                    value = parseLong(valueString);
                } else if (optionType == String.class) {
                    value = valueString;
                } else if (optionType == Double.class) {
                    value = Double.parseDouble(valueString);
                } else if (optionType == Boolean.class) {
                    if (valueString.equals("true")) {
                        value = true;
                    } else if (valueString.equals("false")) {
                        value = false;
                    } else {
                        return OptionParseResult.error("Boolean option '" + optionName + "' must have value 'true' or 'false'");
                    }
                } else if (optionType == CompilationWrapper.ExceptionAction.class) {
                    value = CompilationWrapper.ExceptionAction.valueOf(valueString);
                } else {
                    throw VMError.shouldNotReachHere("Unsupported option value class: " + optionType.getSimpleName());
                }
            } catch (NumberFormatException ex) {
                return OptionParseResult.error("Invalid value for option '" + optionName + "': '" + valueString + "' is not a valid number");
            }
        } else {
            if (optionType != Boolean.class) {
                return OptionParseResult.error("Non-boolean option '" + optionName + "' can not use +/- prefix. Use '" + optionName + "=<value>' format");
            }
        }

        desc.getOptionKey().update(valuesMap, value);

        if (SubstrateOptions.PrintFlags.getName().equals(optionName)) {
            String optionValue = (String) value;
            EnumSet<OptionType> selectedOptionTypes;
            if (optionValue.isEmpty()) {
                selectedOptionTypes = EnumSet.allOf(OptionType.class);
            } else {
                selectedOptionTypes = EnumSet.noneOf(OptionType.class);
                String enumString = null;
                try {
                    String[] enumStrings = optionValue.split(",");
                    for (int i = 0; i < enumStrings.length; i++) {
                        enumString = enumStrings[i];
                        selectedOptionTypes.add(OptionType.valueOf(enumString));
                    }
                } catch (IllegalArgumentException e) {
                    String possibleValues = Arrays.stream(OptionType.values()).map(OptionType::name).collect(Collectors.joining(", ", "", ""));
                    return OptionParseResult.error("Invalid value for option '" + optionName + ". " + enumString + "' is not one of: " + possibleValues);
                }
            }
            return OptionParseResult.printFlags(selectedOptionTypes);
        }

        return OptionParseResult.correct();
    }

    /**
     * Parses a option at image build time. When the PrintFlags option is found prints all options
     * and interrupts compilation.
     *
     * @param optionPrefix prefix used before option name
     * @param options all possible options
     * @param valuesMap all current option values
     * @param booleanOptionFormat help expected for boolean options
     * @param errors a set that contains all error messages
     * @param arg the argument currently processed
     * @return true if {@code arg.startsWith(optionPrefix)}
     */
    public static boolean parseHostedOption(String optionPrefix, SortedMap<String, OptionDescriptor> options, EconomicMap<OptionKey<?>, Object> valuesMap, BooleanOptionFormat booleanOptionFormat,
                    Set<String> errors, String arg, PrintStream out) {
        if (!arg.startsWith(optionPrefix)) {
            return false;
        }

        OptionParseResult optionParseResult = SubstrateOptionsParser.parseOption(options, arg.substring(optionPrefix.length()), valuesMap, optionPrefix, booleanOptionFormat);
        if (optionParseResult.printFlags()) {
            SubstrateOptionsParser.printFlags(optionParseResult::matchesFlagsHosted, options, optionPrefix, out);
            throw new InterruptImageBuilding();
        }
        if (!optionParseResult.isValid()) {
            errors.add(optionParseResult.getError());
        }
        return true;
    }

    private static String spaces(int length) {
        return new String(new char[length]).replace('\0', ' ');
    }

    private static String wrap(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        int cursor = 0;
        while (cursor + width < sb.length()) {
            int i = sb.lastIndexOf(" ", cursor + width);
            if (i == -1 || i < cursor) {
                i = sb.indexOf(" ", cursor + width);
            }
            if (i != -1) {
                sb.replace(i, i + 1, System.lineSeparator());
                cursor = i;
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static void printOption(PrintStream out, String option, String description) {
        printOption(out::println, option, description, 2, 45, 120);
    }

    public static void printOption(Consumer<String> println, String option, String description, int indentation, int optionWidth, int wrapWidth) {
        String indent = spaces(indentation);
        String desc = wrap(description != null ? description : "", wrapWidth);
        String nl = System.lineSeparator();
        String[] descLines = desc.split(nl);
        if (option.length() >= optionWidth && description != null) {
            println.accept(indent + option + nl + indent + spaces(optionWidth) + descLines[0]);
        } else {
            println.accept(indent + option + spaces(optionWidth - option.length()) + descLines[0]);
        }
        for (int i = 1; i < descLines.length; i++) {
            println.accept(indent + spaces(optionWidth) + descLines[i]);
        }
    }

    static void printFlags(Predicate<OptionDescriptor> filter, SortedMap<String, OptionDescriptor> sortedOptions, String prefix, PrintStream out) {
        for (Entry<String, OptionDescriptor> entry : sortedOptions.entrySet()) {
            OptionDescriptor descriptor = entry.getValue();
            if (!filter.test(descriptor)) {
                continue;
            }
            String helpMsg = descriptor.getHelp();
            int helpLen = helpMsg.length();
            if (helpLen > 0 && helpMsg.charAt(helpLen - 1) != '.') {
                helpMsg += '.';
            }
            if (descriptor.getOptionValueType() == Boolean.class) {
                Boolean val = (Boolean) descriptor.getOptionKey().getDefaultValue();
                if (helpLen != 0) {
                    helpMsg += ' ';
                }
                if (val == null || !((boolean) val)) {
                    helpMsg += "Default: - (disabled).";
                } else {
                    helpMsg += "Default: + (enabled).";
                }
                printOption(out, prefix + "\u00b1" + entry.getKey(), helpMsg);
            } else {
                Object def = descriptor.getOptionKey().getDefaultValue();
                if (def instanceof String) {
                    def = '"' + String.valueOf(def) + '"';
                }
                printOption(out, prefix + entry.getKey() + "=" + def, helpMsg);
            }
        }
    }

    public static long parseLong(String v) {
        String valueString = v.toLowerCase();
        long scale = 1;
        if (valueString.endsWith("k")) {
            scale = 1024L;
        } else if (valueString.endsWith("m")) {
            scale = 1024L * 1024L;
        } else if (valueString.endsWith("g")) {
            scale = 1024L * 1024L * 1024L;
        } else if (valueString.endsWith("t")) {
            scale = 1024L * 1024L * 1024L * 1024L;
        }

        if (scale != 1) {
            /* Remove trailing scale character. */
            valueString = valueString.substring(0, valueString.length() - 1);
        }

        return Long.parseLong(valueString) * scale;
    }

    /**
     * Returns a string to be used on command line to set the option to a desirable value.
     *
     * @param option for which the command line argument is created
     * @return recommendation for setting a option value (e.g., for option 'Name' and value 'file'
     *         it returns "-H:Name=file")
     */
    public static String commandArgument(OptionKey<?> option, String value) {
        if (option.getDescriptor().getOptionValueType() == Boolean.class) {
            assert value.equals("+") || value.equals("-") || value.equals("[+|-]") : "Boolean option can be only + or - or [+|-].";
            return HOSTED_OPTION_PREFIX + value + option;
        } else {
            return HOSTED_OPTION_PREFIX + option.getName() + "=" + value;
        }
    }
}
