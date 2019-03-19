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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import static org.graalvm.component.installer.Commands.DO_NOT_PROCESS_OPTIONS;

/**
 *
 * @author sdedic
 */
public class SimpleGetopt {
    private LinkedList<String> parameters;
    private final Map<String, String> globalOptions;
    private final Map<String, Map<String, String>> commandOptions = new HashMap<>();

    private final Map<String, String> optValues = new HashMap<>();
    private final LinkedList<String> positionalParameters = new LinkedList<>();

    private String command;
    private boolean ignoreUnknownCommands;
    private boolean unknownCommand;

    private Map<String, String> abbreviations = new HashMap<>();
    private final Map<String, Map<String, String>> commandAbbreviations = new HashMap<>();

    public SimpleGetopt(Map<String, String> globalOptions) {
        this.globalOptions = globalOptions;
    }

    public void setParameters(LinkedList<String> parameters) {
        this.parameters = parameters;
    }

    public SimpleGetopt ignoreUnknownCommands(boolean ignore) {
        this.ignoreUnknownCommands = ignore;
        return this;
    }

    // overridable by tests
    RuntimeException err(String messageKey, Object... args) {
        throw ComponentInstaller.err(messageKey, args);
    }

    private String findCommand(String cmdString) {
        String cmd = cmdString;
        if (cmd.isEmpty()) {
            if (ignoreUnknownCommands) {
                return null;
            }
            throw err("ERROR_MissingCommand"); // NOI18N
        }
        String selCommand = null;
        for (String s : commandOptions.keySet()) {
            if (s.startsWith(cmdString)) {
                if (selCommand != null) {
                    throw err("ERROR_AmbiguousCommand", cmdString, selCommand, s);
                }
                selCommand = s;
                if (s.length() == cmdString.length()) {
                    break;
                }
            }
        }
        if (selCommand == null) {
            if (ignoreUnknownCommands) {
                unknownCommand = true;
                command = cmdString;
                return null;
            }
            throw err("ERROR_UnknownCommand", cmdString); // NOI18N
        }
        command = selCommand;
        return command;
    }

    private static final String NO_ABBREV = "**no-abbrev"; // NOI18N

    private boolean hasCommand() {
        return command != null && !unknownCommand;
    }

    @SuppressWarnings("StringEquality")
    Map<String, String> computeAbbreviations(Collection<String> optNames) {
        Map<String, String> result = new HashMap<>();

        for (String o : optNames) {
            if (o.length() < 2) {
                continue;
            }
            result.put(o, NO_ABBREV);
            for (int i = 2; i < o.length(); i++) {
                String s = o.substring(0, i);

                String fullName = result.get(s);
                if (fullName == null) {
                    result.put(s, o);
                } else if (fullName.length() == 2) {
                    continue;
                } else if (o.length() == 2) {
                    result.put(o, o);
                } else {
                    result.put(s, NO_ABBREV);
                }
            }
        }
        // final Object noAbbrevMark = NO_ABBREV;
        for (Iterator<Entry<String, String>> ens = result.entrySet().iterator(); ens.hasNext();) {
            Entry<String, String> en = ens.next();
            // cannot use comparison to NO_ABBREV directly because of FindBugs + mx gate combo.
            if (NO_ABBREV.equals(en.getValue())) {
                ens.remove();
            }
        }
        return result;
    }

    void computeAbbreviations() {
        abbreviations = computeAbbreviations(globalOptions.keySet());

        for (String c : commandOptions.keySet()) {
            Set<String> names = new HashSet<>(commandOptions.get(c).keySet());
            names.addAll(globalOptions.keySet());

            Map<String, String> commandAbbrevs = computeAbbreviations(names);
            commandAbbreviations.put(c, commandAbbrevs);
        }
    }

    public void process() {
        computeAbbreviations();
        while (true) {
            String p = parameters.peek();
            if (p == null) {
                break;
            }
            if (!p.startsWith("-")) { // NOI18N
                if (command == null) {
                    findCommand(parameters.poll());
                    Map<String, String> cOpts = commandOptions.get(command);
                    if (cOpts != null) {
                        for (String s : optValues.keySet()) {
                            if (s.length() > 1) {
                                continue;
                            }
                            if ("X".equals(cOpts.get(s))) {
                                throw err("ERROR_UnsupportedOption", s, command); // NOI18N
                            }
                        }
                        if (cOpts.containsKey(DO_NOT_PROCESS_OPTIONS)) { // NOI18N
                            // terminate all processing, the rest are positional params
                            positionalParameters.addAll(parameters);
                            break;
                        }
                    } else {
                        positionalParameters.add(p);
                    }
                } else {
                    positionalParameters.add(parameters.poll());
                }
                continue;
            } else if (p.length() == 1 || "--".equals(p)) {
                // dash alone, or double-dash terminates option search.
                parameters.poll();
                positionalParameters.addAll(parameters);
                break;
            }
            String param = parameters.poll();
            boolean nextParam = p.startsWith("--"); // NOI18N
            String optName;
            int optCharIndex = 1;
            while (optCharIndex < param.length()) {
                if (nextParam) {
                    optName = param.substring(2);
                    param = processOptSpec(optName, optCharIndex, param, nextParam);
                } else {
                    optName = param.substring(optCharIndex, optCharIndex + 1);
                    optCharIndex += optName.length();
                    param = processOptSpec(optName, optCharIndex, param, nextParam);
                }
                // hack: if "help" option (hardcoded) is present, terminate
                if (optValues.get("h") != null) {
                    return;
                }
                if (nextParam) {
                    break;
                }
            }
        }
    }

    private String processOptSpec(String o, int optCharIndex, String optParam, boolean nextParam) {
        String param = optParam;
        String optSpec = null;
        String optName = o;
        if (hasCommand()) {
            Map<String, String> cmdAbbrevs = commandAbbreviations.get(command);
            String fullO = cmdAbbrevs.get(optName);
            if (fullO != null) {
                optName = fullO;
            }
            Map<String, String> cmdSpec = commandOptions.get(command);
            String c = cmdSpec.get(optName);
            if (c != null && optName.length() > 1) {
                optSpec = cmdSpec.get(c);
                optName = c;
            } else {
                optSpec = c;
            }
        }
        if (optSpec == null) {
            String fullO = abbreviations.get(optName);
            if (fullO != null) {
                optName = fullO;
            }
            String c = globalOptions.get(optName);
            if (c != null && optName.length() > 1) {
                optSpec = globalOptions.get(c);
                optName = c;
            } else {
                optSpec = c;
            }
        }
        if (optSpec == null) {
            if (unknownCommand) {
                return param;
            }
            if (command == null) {
                throw err("ERROR_UnsupportedGlobalOption", o); // NOI18N
            }
            Map<String, String> cmdSpec = commandOptions.get(command);
            if (cmdSpec.isEmpty()) {
                throw err("ERROR_CommandWithNoOptions", command); // NOI18N
            }
            throw err("ERROR_UnsupportedOption", o, command); // NOI18N
        }
        // no support for parametrized options now
        String optVal = "";
        switch (optSpec) {
            case "s":
                if (nextParam) {
                    optVal = parameters.poll();
                    if (optVal == null) {
                        throw err("ERROR_OptionNeedsParameter", o, command); // NOI18N
                    }
                } else {
                    if (optCharIndex < param.length()) {
                        optVal = param.substring(optCharIndex);
                        param = "";
                    } else if (parameters.isEmpty()) {
                        throw err("ERROR_OptionNeedsParameter", o, command); // NOI18N
                    } else {
                        optVal = parameters.poll();
                    }
                }
                break;
            case "X":
                throw err("ERROR_UnsupportedOption", o, command); // NOI18N
            case "":
                break;
        }
        optValues.put(optName, optVal); // NOI18N
        return param;
    }

    public String getCommand() {
        return command;
    }

    public void addCommandOptions(String commandName, Map<String, String> optSpec) {
        commandOptions.put(commandName, optSpec);
    }

    public Map<String, String> getOptValues() {
        return optValues;
    }

    public LinkedList<String> getPositionalParameters() {
        return positionalParameters;
    }
}
