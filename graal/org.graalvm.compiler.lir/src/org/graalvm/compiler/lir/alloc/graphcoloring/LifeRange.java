package org.graalvm.compiler.lir.alloc.graphcoloring;

public class LifeRange {
    private final int id;
    private int from;
    private int to;
    private LifeRange next;

    public static final LifeRange EndMarker = new LifeRange(-1, Integer.MAX_VALUE, Integer.MAX_VALUE, null);

    public LifeRange(int id, int from, int to, LifeRange lifeRange) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.next = lifeRange;

    }

    public int getTo() {
        return to;
    }

    public void setTo(int to) {
        this.to = to;
    }

    public int getFrom() {
        return from;
    }

    public void setFrom(int from) {
        this.from = from;
    }

    public int getId() {
        return id;
    }

    public LifeRange getNext() {
        return next;
    }

    public void setNext(LifeRange next) {
        this.next = next;
    }

}
