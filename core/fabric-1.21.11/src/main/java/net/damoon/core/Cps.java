package net.damoon.core;

import java.util.ArrayDeque;

/** 최근 1초 동안의 좌/우 클릭 수 */
public final class Cps {
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
    private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();

    private Cps() {}

    public static void click(int button) {
        long now = System.currentTimeMillis();
        if (button == 0) LEFT.addLast(now);
        else if (button == 1) RIGHT.addLast(now);
    }

    private static int count(ArrayDeque<Long> q) {
        long limit = System.currentTimeMillis() - 1000;
        while (!q.isEmpty() && q.peekFirst() < limit) q.pollFirst();
        return q.size();
    }

    public static int left() { return count(LEFT); }
    public static int right() { return count(RIGHT); }
}
