package com.georgeliao.engine.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodecTest {

    private static ByteBuffer buffer(int frames) {
        return ByteBuffer.allocate(frames * Codec.FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void roundTripsEveryType() {
        List<Message> in = List.of(
                new Message.NewOrder(1, 77, Side.SELL, 10_050, 300),
                new Message.Cancel(2, 77),
                new Message.Tick(3, 10_049, 12),
                new Message.NewOrder(4, Long.MAX_VALUE, Side.BUY, Long.MIN_VALUE, Integer.MAX_VALUE));
        ByteBuffer buf = buffer(in.size());
        in.forEach(m -> Codec.encode(m, buf));
        assertEquals(buf.capacity(), buf.position());

        buf.flip();
        for (Message expected : in) {
            assertEquals(expected, Codec.decode(buf));
        }
        assertEquals(0, buf.remaining());
    }

    @Test
    void layoutIsLittleEndian() {
        ByteBuffer buf = buffer(1);
        Codec.encode(new Message.Tick(0x0102, 5, 1), buf);
        assertEquals(Codec.TICK, buf.get(0));
        assertEquals(0x02, buf.get(8));
        assertEquals(0x01, buf.get(9));
        assertEquals(5, buf.get(24));
    }

    @Test
    void rejectsBigEndianAndUnknownType() {
        ByteBuffer big = ByteBuffer.allocate(Codec.FRAME_SIZE);
        assertThrows(IllegalArgumentException.class, () -> Codec.encode(new Message.Cancel(1, 1), big));
        ByteBuffer junk = buffer(1);
        junk.put(0, (byte) 9);
        assertThrows(IllegalArgumentException.class, () -> Codec.decode(junk));
    }
}
