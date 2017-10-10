/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.test.nodes;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.Iterator;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.NodeVisitor;
import com.oracle.truffle.api.nodes.RootNode;

public class NodeUtilTest {

    @Test
    public void testRecursiveIterator1() {
        TestRootNode root = new TestRootNode();
        TestNode testNode = new TestNode();
        root.child0 = testNode;
        root.adoptChildren();

        int count = iterate(NodeUtil.makeRecursiveIterator(root));

        assertThat(count, is(2));
        assertThat(root.visited, is(0));
        assertThat(testNode.visited, is(1));
    }

    @Test
    public void testReplaceReplaced() {
        TestRootNode rootNode = new TestRootNode();
        TestNode replacedNode = new TestNode();
        rootNode.child0 = replacedNode;
        rootNode.adoptChildren();
        rootNode.child0 = null;

        TestNode test1 = new TestNode();
        TestNode test11 = new TestNode();
        TestNode test111 = new TestNode();

        test11.child1 = test111;
        test1.child1 = test11;
        replacedNode.replace(test1);

        Assert.assertSame(rootNode, test1.getParent());
        Assert.assertSame(test1, test11.getParent());
        Assert.assertSame(test11, test111.getParent());
    }

    @Test
    public void testForEachChild() {
        TestRootNode root = new TestRootNode();
        TestForEachNode testForEachNode = new TestForEachNode(1);
        root.child0 = testForEachNode;
        TestNode testNode1 = new TestNode();
        testForEachNode.firstChild = testNode1;
        TestNode testNode2 = new TestNode();
        testForEachNode.children[0] = testNode2;
        TestNode testNode3 = new TestNode();
        testForEachNode.lastChild = testNode3;
        root.adoptChildren();

        int[] count = new int[1];
        NodeUtil.forEachChild(root, new NodeVisitor() {
            public boolean visit(Node node) {
                Assert.assertSame(testForEachNode, node);
                count[0]++;
                return true;
            }
        });
        Assert.assertEquals(1, count[0]);

        count[0] = 0;
        NodeUtil.forEachChild(testForEachNode, new NodeVisitor() {
            public boolean visit(Node node) {
                ((VisitableNode) node).visited++;
                count[0]++;
                return true;
            }
        });
        Assert.assertEquals(3, count[0]);
        Assert.assertEquals(1, testNode1.visited);
        Assert.assertEquals(1, testNode2.visited);
        Assert.assertEquals(1, testNode3.visited);
    }

    @Test
    public void testAccept() {
        TestRootNode root = new TestRootNode();
        TestForEachNode testForEachNode = new TestForEachNode(1);
        root.child0 = testForEachNode;
        TestNode testNode1 = new TestNode();
        testForEachNode.firstChild = testNode1;
        TestNode testNode2 = new TestNode();
        testForEachNode.children[0] = testNode2;
        TestNode testNode3 = new TestNode();
        testForEachNode.lastChild = testNode3;
        root.adoptChildren();

        int[] count = new int[1];
        testForEachNode.accept(new NodeVisitor() {
            public boolean visit(Node node) {
                ((VisitableNode) node).visited++;
                count[0]++;
                return true;
            }
        });

        Assert.assertEquals(4, count[0]);
        Assert.assertEquals(1, testForEachNode.visited);
        Assert.assertEquals(1, testNode1.visited);
        Assert.assertEquals(1, testNode2.visited);
        Assert.assertEquals(1, testNode3.visited);
    }

    @Test
    public void testRecursiveIterator() {
        TestRootNode root = new TestRootNode();
        TestForEachNode testForEachNode = new TestForEachNode(1);
        root.child0 = testForEachNode;
        TestNode testNode1 = new TestNode();
        testForEachNode.firstChild = testNode1;
        TestNode testNode2 = new TestNode();
        testForEachNode.children[0] = testNode2;
        TestNode testNode3 = new TestNode();
        testForEachNode.lastChild = testNode3;
        root.adoptChildren();

        int count = 0;
        Iterable<Node> iterable = () -> NodeUtil.makeRecursiveIterator(testForEachNode);
        for (Node node : iterable) {
            ((VisitableNode) node).visited++;
            count++;
        }

        Assert.assertEquals(4, count);
        Assert.assertEquals(1, testForEachNode.visited);
        Assert.assertEquals(1, testNode1.visited);
        Assert.assertEquals(1, testNode2.visited);
        Assert.assertEquals(1, testNode3.visited);
    }

    @Test
    public void testChildren() {
        TestRootNode root = new TestRootNode();
        TestForEachNode testForEachNode = new TestForEachNode(1);
        root.child0 = testForEachNode;
        TestNode testNode1 = new TestNode();
        testForEachNode.firstChild = testNode1;
        TestNode testNode2 = new TestNode();
        testForEachNode.children[0] = testNode2;
        TestNode testNode3 = new TestNode();
        testForEachNode.lastChild = testNode3;

        int count = 0;
        for (Node node : testForEachNode.getChildren()) {
            ((VisitableNode) node).visited++;
            count++;
        }
        Assert.assertEquals(3, count);
        Assert.assertEquals(1, testNode1.visited);
        Assert.assertEquals(1, testNode2.visited);
        Assert.assertEquals(1, testNode3.visited);
    }

    @Test
    public void testChildrenArray() {
        // 2 children in the array
        TestForEachNode test2children = new TestForEachNode(2);
        TestNode both1 = new TestNode();
        TestNode both2 = new TestNode();
        test2children.children[0] = both1;
        test2children.children[1] = both2;

        int count = 0;
        for (Node node : test2children.getChildren()) {
            ((VisitableNode) node).visited++;
            count++;
        }
        Assert.assertEquals(2, count);
        Assert.assertEquals(1, both1.visited);
        Assert.assertEquals(1, both2.visited);

        // First null
        TestNode testChild1 = new TestNode();
        test2children.children[0] = null;
        test2children.children[1] = testChild1;

        count = 0;
        for (Node node : test2children.getChildren()) {
            ((VisitableNode) node).visited++;
            count++;
        }
        Assert.assertEquals(1, count);
        Assert.assertEquals(1, testChild1.visited);

        // Second null
        TestNode testChild2 = new TestNode();
        test2children.children[0] = testChild2;
        test2children.children[1] = null;

        count = 0;
        for (Node node : test2children.getChildren()) {
            ((VisitableNode) node).visited++;
            count++;
        }
        Assert.assertEquals(1, count);
        Assert.assertEquals(1, testChild2.visited);

        // Both null, go to next child
        TestNode otherChild = new TestNode();
        test2children.children[0] = null;
        test2children.children[1] = null;
        test2children.lastChild = otherChild;

        count = 0;
        for (Node node : test2children.getChildren()) {
            ((VisitableNode) node).visited++;
            count++;
        }
        Assert.assertEquals(1, count);
        Assert.assertEquals(1, otherChild.visited);
    }

    private static int iterate(Iterator<Node> iterator) {
        int iterationCount = 0;
        while (iterator.hasNext()) {
            Node node = iterator.next();
            if (node == null) {
                continue;
            }
            if (node instanceof TestNode) {
                ((TestNode) node).visited = iterationCount;
            } else if (node instanceof TestRootNode) {
                ((TestRootNode) node).visited = iterationCount;
            } else {
                throw new AssertionError();
            }
            iterationCount++;
        }
        return iterationCount;
    }

    private static class VisitableNode extends Node {
        int visited = 0;
    }

    private static class TestNode extends VisitableNode {

        @Child TestNode child0;
        @Child TestNode child1;

        TestNode() {
        }

    }

    private static class TestRootNode extends RootNode {

        @Child Node child0;

        protected int visited;

        TestRootNode() {
            super(null);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return null;
        }

    }

    private static class TestForEachNode extends VisitableNode {

        @Child private Node nullChild;
        @SuppressWarnings("unused") private String data1;
        @Child private Node firstChild;
        @Children private final Node[] children;
        @SuppressWarnings("unused") private boolean data2;
        @Child private Node lastChild;

        TestForEachNode(int childrenSize) {
            this.children = new Node[childrenSize];
        }

    }

}
