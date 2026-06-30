package com.autotapper.app;

public class TapTarget {
    public int x;
    public int y;
    public long delayMs;
    public int index;

    public TapTarget(int x, int y, long delayMs, int index) {
        this.x = x;
        this.y = y;
        this.delayMs = delayMs;
        this.index = index;
    }
}
