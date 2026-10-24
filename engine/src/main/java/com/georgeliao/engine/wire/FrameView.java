package com.georgeliao.engine.wire;

import java.nio.ByteBuffer;

/**
 * Flyweight over one frame of an in-memory feed: fields are read straight from the buffer on
 * demand, nothing is copied or allocated. Same layout as {@link Codec}. Re-point it with
 * {@link #wrap} for each message.
 */
public final class FrameView {

    private ByteBuffer buf;
    private int at;

    /** {@code buf} must be little-endian; it is only read, never moved. */
    public FrameView wrap(ByteBuffer buf, int offset) {
        this.buf = buf;
        this.at = offset;
        return this;
    }

    public byte type() {
        return buf.get(at);
    }

    public Side side() {
        return Side.fromCode(buf.get(at + 1));
    }

    public int qty() {
        return buf.getInt(at + 4);
    }

    public long seq() {
        return buf.getLong(at + 8);
    }

    public long orderId() {
        return buf.getLong(at + 16);
    }

    public long price() {
        return buf.getLong(at + 24);
    }
}
