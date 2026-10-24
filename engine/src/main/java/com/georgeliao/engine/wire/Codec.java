package com.georgeliao.engine.wire;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Fixed 32-byte little-endian frame, same layout for every type:
 *
 * <pre>
 *  0  type     byte   (1 = NewOrder, 2 = Cancel, 3 = Tick)
 *  1  side     byte   (NewOrder only)
 *  2  padding  2 bytes
 *  4  qty      int    (NewOrder, Tick)
 *  8  seq      long
 * 16  orderId  long   (NewOrder, Cancel)
 * 24  price    long   in ticks (NewOrder, Tick)
 * </pre>
 *
 * Reads and writes are relative: each call consumes or fills one frame at the buffer's position.
 */
public final class Codec {

    public static final int FRAME_SIZE = 32;

    public static final byte NEW_ORDER = 1;
    public static final byte CANCEL = 2;
    public static final byte TICK = 3;

    private Codec() {}

    public static void encode(Message m, ByteBuffer buf) {
        checkOrder(buf);
        int start = buf.position();
        switch (m) {
            case Message.NewOrder o -> write(buf, start, NEW_ORDER, o.side().code(), o.qty(), o.seq(), o.orderId(), o.price());
            case Message.Cancel c -> write(buf, start, CANCEL, (byte) 0, 0, c.seq(), c.orderId(), 0L);
            case Message.Tick t -> write(buf, start, TICK, (byte) 0, t.qty(), t.seq(), 0L, t.price());
        }
        buf.position(start + FRAME_SIZE);
    }

    public static Message decode(ByteBuffer buf) {
        checkOrder(buf);
        int start = buf.position();
        byte type = buf.get(start);
        long seq = buf.getLong(start + 8);
        Message m = switch (type) {
            case NEW_ORDER -> new Message.NewOrder(
                    seq, buf.getLong(start + 16), Side.fromCode(buf.get(start + 1)), buf.getLong(start + 24), buf.getInt(start + 4));
            case CANCEL -> new Message.Cancel(seq, buf.getLong(start + 16));
            case TICK -> new Message.Tick(seq, buf.getLong(start + 24), buf.getInt(start + 4));
            default -> throw new IllegalArgumentException("bad message type " + type + " at offset " + start);
        };
        buf.position(start + FRAME_SIZE);
        return m;
    }

    private static void write(ByteBuffer buf, int at, byte type, byte side, int qty, long seq, long orderId, long price) {
        buf.put(at, type);
        buf.put(at + 1, side);
        buf.putShort(at + 2, (short) 0);
        buf.putInt(at + 4, qty);
        buf.putLong(at + 8, seq);
        buf.putLong(at + 16, orderId);
        buf.putLong(at + 24, price);
    }

    private static void checkOrder(ByteBuffer buf) {
        if (buf.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("buffer must be little-endian");
        }
    }
}
