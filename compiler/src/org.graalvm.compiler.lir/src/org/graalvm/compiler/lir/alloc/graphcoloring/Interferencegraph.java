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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.BitSet;
import java.util.Date;
import java.util.Vector;

public class Interferencegraph {

    private int id;
    private BitSet[] adList;
    private Vector<Integer>[] edgeList;
    private int size;

    @SuppressWarnings({"cast", "unchecked"})
    public Interferencegraph() {

        adList = new BitSet[10];
        edgeList = (Vector<Integer>[]) new Vector[10];
        size = 0;

    }

    private static BitSet[] resizeArray(BitSet[] small) {

        BitSet[] temp = new BitSet[small.length * 2];

        System.arraycopy(small, 0, temp, 0, small.length);

        return temp;
    }

    @SuppressWarnings({"cast", "unchecked"})
    private static Vector<Integer>[] resizeArray(Vector<Integer>[] small) {

        Vector<Integer>[] temp = (Vector<Integer>[]) new Vector[small.length * 2];

        System.arraycopy(small, 0, temp, 0, small.length);

        return temp;
    }

    public Interferencegraph(int id) {
        this();
        this.id = id;

    }

    public int getId() {
        return id;
    }

    public void addNode(int id1) {

        while (adList.length <= id1) {
            adList = resizeArray(adList);
            edgeList = resizeArray(edgeList);

        }

        if (adList[id1] == null) {

            edgeList[id1] = new Vector<>();
            adList[id1] = new BitSet();
            size++;

        }

    }

    public Vector<Integer> removeNode(int id1) {

        Vector<Integer> v = edgeList[id1];
        for (int i = 0; i < v.size(); i++) {
            setEdge(id1, v.get(i), false);
        }

        adList[id1] = null;
        edgeList[id1] = null;
        size--;

        return v;

    }

    public int size() {
        size = 0;

        for (int i = 0; i < adList.length; i++) {
            if (adList[i] != null) {
                size++;
            }
        }

        return size;

    }

    public void setEdge(int a, int b, boolean set) {

        addNode(a);

        addNode(b);

        if (a > b) {
            adList[a].set(b, set);
        } else if (a < b) {
            adList[b].set(a, set);
        }

        if (set && a != b) {

            if (!edgeList[a].contains(b)) {
                edgeList[a].add(b);
            }

            if (!edgeList[b].contains(a)) {
                edgeList[b].add(a);
            }

        } else if (a != b) {

            if (edgeList[b].contains(a)) {
                edgeList[b].remove(new Integer(a));
            }

        }

    }

    public Vector<Integer>[] getEdgeList() {

        return edgeList;

    }

    public BitSet[] getNodeList() {
        return adList;
    }

    public Vector<Integer> getEdges(int id1) {
        return edgeList[id1];

    }

// private boolean containsNode(int id) {
//
// for (Node n : graph) {
// if (n.getId() == id) {
// return true;
// }
// }
//
// return false;
//
// }
//
    public void printGraph(Liveness life) {

        try {
            File f = new File(".." + File.separator + "IG_" + id + "_" + life.getCompilationUnitName() + new Date().getTime() + ".dot");
            f.createNewFile();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
            writer.write("graph Interferencegraph {" + "\n");

            for (int i = 0; i < adList.length; i++) {
                if (adList[i] != null) {
                    BitSet b = adList[i];
                    if (!b.isEmpty()) {
                        for (int j = b.nextSetBit(0); j >= 0; j = b.nextSetBit(j + 1)) {
                            writer.write(life.toStringGraph(i));
                            writer.write(" -- " + life.toStringGraph(j) + ";\n");

                            if (i == Integer.MAX_VALUE || i == b.size() + 1) {
                                break; // or (i+1) would overflow
                            }
                        }
                    } else {
                        writer.write(life.toStringGraph(i));
                        writer.write(";\n");
                    }

                }

            }

            writer.write("label=\"" + life.getCompilationUnitName() + "\";\n");
            writer.write("}");
            writer.flush();
            writer.close();

        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    public void printGraph(Liveness life, String s) {

        try {
            File f = new File(".." + File.separator + "IG_" + id + "_" + life.getCompilationUnitName() + new Date().getTime() + ".dot");
            f.createNewFile();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
            writer.write("graph Interferencegraph {" + "\n");

            for (int i = 0; i < adList.length; i++) {
                if (adList[i] != null) {
                    BitSet b = adList[i];
                    if (!b.isEmpty()) {
                        for (int j = b.nextSetBit(0); j >= 0; j = b.nextSetBit(j + 1)) {
                            writer.write(life.toStringGraph(i));
                            writer.write(" -- " + life.toStringGraph(j) + ";\n");

                            if (i == Integer.MAX_VALUE || i == b.size() + 1) {
                                break; // or (i+1) would overflow
                            }
                        }
                    } else {
                        writer.write(life.toStringGraph(i));
                        writer.write(";\n");
                    }

                }

            }

            writer.write("label=\"" + life.getCompilationUnitName() + s + "\";\n");
            writer.write("}");
            writer.flush();
            writer.close();

        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    public void addNode(int id2, Vector<Integer> edges) {
        addNode(id2);
        for (int n : edges) {
            setEdge(id2, n, true);
        }

    }

}
