package com.georgeliao.engine.wire;

public enum Side {
    BUY,
    SELL;

    public byte code() {
        return (byte) ordinal();
    }

    public static Side fromCode(byte code) {
        return switch (code) {
            case 0 -> BUY;
            case 1 -> SELL;
            default -> throw new IllegalArgumentException("bad side " + code);
        };
    }
}
