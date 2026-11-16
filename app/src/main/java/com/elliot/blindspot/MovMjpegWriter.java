package com.elliot.blindspot;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class MovMjpegWriter implements Closeable {
    private static final long MAC_EPOCH_OFFSET_SEC = 2082844800L;
    private static final int DEFAULT_TIMESCALE = 1000;

    private final int width;
    private final int height;
    private final int timescale;
    private final int defaultSampleDuration;
    private final long creationTimeSec;

    private BufferedOutputStream out;
    private FileChannel channel;
    private long fileOffset;
    private long mdatHeaderOffset;

    private int sampleCount;
    private int[] sampleSizes = new int[256];
    private long[] sampleOffsets = new long[256];
    private int[] sampleDurations = new int[256];
    private long lastSampleTsMs = -1;
    private int lastSampleIndex = -1;

    MovMjpegWriter(int width, int height, int fps, long startEpochMs) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        int f = Math.max(1, fps);
        this.timescale = DEFAULT_TIMESCALE;
        this.defaultSampleDuration = (int) Math.max(1, Math.round((double) timescale / (double) f));
        this.creationTimeSec = (startEpochMs / 1000L) + MAC_EPOCH_OFFSET_SEC;
    }

    void open(File file) throws IOException {
        if (out != null) throw new IllegalStateException("already open");
        FileOutputStream fos = new FileOutputStream(file);
        this.channel = fos.getChannel();
        open(fos);
    }

    void open(OutputStream os) throws IOException {
        if (out != null) throw new IllegalStateException("already open");
        out = new BufferedOutputStream(os, 256 * 1024);
        fileOffset = 0;
        writeBytes(box("ftyp", ftypPayload()));
        mdatHeaderOffset = fileOffset;
        writeInt32(0);
        writeType("mdat");
    }

    int getSampleCount() {
        return sampleCount;
    }

    void writeJpegSample(byte[] jpegBytes, int length, long sampleEpochMs) throws IOException {
        if (out == null) throw new IllegalStateException("not open");
        if (jpegBytes == null) throw new NullPointerException("jpegBytes");
        if (length <= 0 || length > jpegBytes.length) throw new IllegalArgumentException("length");
        ensureCapacity(sampleCount + 1);
        if (lastSampleIndex >= 0 && lastSampleTsMs >= 0) {
            long deltaMs = sampleEpochMs - lastSampleTsMs;
            if (deltaMs < 1) deltaMs = 1;
            long units = (deltaMs * (long) timescale) / 1000L;
            if (units < 1) units = 1;
            if (units > Integer.MAX_VALUE) units = Integer.MAX_VALUE;
            sampleDurations[lastSampleIndex] = (int) units;
        }
        sampleOffsets[sampleCount] = fileOffset;
        sampleSizes[sampleCount] = length;
        sampleDurations[sampleCount] = defaultSampleDuration;
        lastSampleIndex = sampleCount;
        lastSampleTsMs = sampleEpochMs;
        sampleCount++;
        writeBytes(jpegBytes, 0, length);
    }

    void writeJpegSample(byte[] jpegBytes, int length) throws IOException {
        writeJpegSample(jpegBytes, length, System.currentTimeMillis());
    }

    void flush() throws IOException {
        BufferedOutputStream o = out;
        if (o != null) o.flush();
    }

    @Override
    public void close() throws IOException {
        BufferedOutputStream o = out;
        out = null;
        if (o == null) return;
        long mdatEndOffset = fileOffset;
        if (sampleCount > 0) {
            byte[] moov = buildMoov();
            o.write(moov);
            fileOffset += moov.length;
        }
        o.flush();
        FileChannel ch = channel;
        channel = null;
        if (ch != null) {
            long mdatSize = mdatEndOffset - mdatHeaderOffset;
            if (mdatSize > 0 && mdatSize <= 0xFFFFFFFFL) {
                ByteBuffer b = ByteBuffer.allocate(4);
                b.putInt((int) mdatSize);
                b.flip();
                ch.position(mdatHeaderOffset);
                while (b.hasRemaining()) ch.write(b);
                ch.force(false);
            }
        }
        o.close();
    }

    private void ensureCapacity(int needed) {
        if (needed <= sampleSizes.length) return;
        int n = sampleSizes.length;
        while (n < needed) n = n * 2;
        sampleSizes = Arrays.copyOf(sampleSizes, n);
        sampleOffsets = Arrays.copyOf(sampleOffsets, n);
        sampleDurations = Arrays.copyOf(sampleDurations, n);
    }

    private byte[] buildMoov() {
        long duration = 0;
        for (int i = 0; i < sampleCount; i++) duration += (long) sampleDurations[i];
        byte[] mvhd = box("mvhd", mvhdPayload(duration));
        byte[] trak = box("trak", trakPayload(duration));
        return box("moov", mvhd, trak);
    }

    private byte[] ftypPayload() {
        return concat(
                ascii4("qt  "),
                int32(0x00000200),
                ascii4("qt  ")
        );
    }

    private byte[] mvhdPayload(long duration) {
        return concat(
                fullBoxHeader(1, 0),
                int64(creationTimeSec),
                int64(creationTimeSec),
                int32(timescale),
                int64(duration),
                int32(0x00010000),
                int16(0x0100),
                int16(0),
                int32(0),
                int32(0),
                matrixIdentity(),
                int32(0),
                int32(0),
                int32(0),
                int32(0),
                int32(0),
                int32(0),
                int32(0),
                int32(0),
                int32(0),
                int32(2)
        );
    }

    private byte[] trakPayload(long duration) {
        byte[] tkhd = box("tkhd", tkhdPayload(duration));
        byte[] mdia = box("mdia", mdiaPayload(duration));
        return concat(tkhd, mdia);
    }

    private byte[] tkhdPayload(long duration) {
        return concat(
                fullBoxHeader(1, 0x00000007),
                int64(creationTimeSec),
                int64(creationTimeSec),
                int32(1),
                int32(0),
                int64(duration),
                int32(0),
                int32(0),
                int16(0),
                int16(0),
                int16(0),
                int16(0),
                matrixIdentity(),
                fixed16_16(width),
                fixed16_16(height)
        );
    }

    private byte[] mdiaPayload(long duration) {
        byte[] mdhd = box("mdhd", mdhdPayload(duration));
        byte[] hdlr = box("hdlr", hdlrPayload());
        byte[] minf = box("minf", minfPayload());
        return concat(mdhd, hdlr, minf);
    }

    private byte[] mdhdPayload(long duration) {
        return concat(
                fullBoxHeader(1, 0),
                int64(creationTimeSec),
                int64(creationTimeSec),
                int32(timescale),
                int64(duration),
                int16(0x55c4),
                int16(0)
        );
    }

    private byte[] hdlrPayload() {
        return concat(
                fullBoxHeader(0, 0),
                int32(0),
                ascii4("vide"),
                int32(0),
                int32(0),
                int32(0),
                new byte[]{0}
        );
    }

    private byte[] minfPayload() {
        byte[] vmhd = box("vmhd", vmhdPayload());
        byte[] dinf = box("dinf", dinfPayload());
        byte[] stbl = box("stbl", stblPayload());
        return concat(vmhd, dinf, stbl);
    }

    private byte[] vmhdPayload() {
        return concat(
                fullBoxHeader(0, 1),
                int16(0),
                int16(0),
                int16(0),
                int16(0)
        );
    }

    private byte[] dinfPayload() {
        byte[] url = box("url ", concat(fullBoxHeader(0, 1)));
        byte[] dref = box("dref", concat(fullBoxHeader(0, 0), int32(1), url));
        return concat(dref);
    }

    private byte[] stblPayload() {
        byte[] stsd = box("stsd", stsdPayload());
        byte[] stts = box("stts", sttsPayload());
        byte[] stsc = box("stsc", stscPayload());
        byte[] stsz = box("stsz", stszPayload());
        byte[] stco = box(sampleNeedsCo64() ? "co64" : "stco", stcoPayload());
        return concat(stsd, stts, stsc, stsz, stco);
    }

    private boolean sampleNeedsCo64() {
        if (sampleCount <= 0) return false;
        long last = sampleOffsets[sampleCount - 1];
        long lastEnd = last + (long) sampleSizes[sampleCount - 1];
        return lastEnd > 0xFFFFFFFFL;
    }

    private byte[] stsdPayload() {
        byte[] entry = jpegSampleEntry();
        return concat(fullBoxHeader(0, 0), int32(1), entry);
    }

    private byte[] jpegSampleEntry() {
        byte[] compressor = new byte[32];
        compressor[0] = 0;
        return box("jpeg", concat(
                new byte[6],
                int16(1),
                int16(0),
                int16(0),
                int32(0),
                int32(0),
                int32(0),
                int16(width),
                int16(height),
                int32(0x00480000),
                int32(0x00480000),
                int32(0),
                int16(1),
                compressor,
                int16(0x0018),
                int16(0xffff)
        ));
    }

    private byte[] sttsPayload() {
        int entries = 0;
        int i = 0;
        while (i < sampleCount) {
            int d = sampleDurations[i];
            int run = 1;
            while (i + run < sampleCount && sampleDurations[i + run] == d) run++;
            entries++;
            i += run;
        }
        byte[] a = new byte[4 + entries * 8];
        putInt32(a, 0, entries);
        int p = 4;
        i = 0;
        while (i < sampleCount) {
            int d = sampleDurations[i];
            int run = 1;
            while (i + run < sampleCount && sampleDurations[i + run] == d) run++;
            putInt32(a, p, run);
            putInt32(a, p + 4, d);
            p += 8;
            i += run;
        }
        return concat(fullBoxHeader(0, 0), a);
    }

    private byte[] stscPayload() {
        return concat(
                fullBoxHeader(0, 0),
                int32(1),
                int32(1),
                int32(1),
                int32(1)
        );
    }

    private byte[] stszPayload() {
        byte[] a = new byte[8 + sampleCount * 4];
        putInt32(a, 0, 0);
        putInt32(a, 4, sampleCount);
        int p = 8;
        for (int i = 0; i < sampleCount; i++) {
            putInt32(a, p, sampleSizes[i]);
            p += 4;
        }
        return concat(fullBoxHeader(0, 0), a);
    }

    private byte[] stcoPayload() {
        boolean co64 = sampleNeedsCo64();
        int entrySize = co64 ? 8 : 4;
        byte[] a = new byte[4 + sampleCount * entrySize];
        putInt32(a, 0, sampleCount);
        int p = 4;
        for (int i = 0; i < sampleCount; i++) {
            long off = sampleOffsets[i];
            if (co64) {
                putInt64(a, p, off);
                p += 8;
            } else {
                putInt32(a, p, (int) off);
                p += 4;
            }
        }
        return concat(fullBoxHeader(0, 0), a);
    }

    private void writeBytes(byte[] b) throws IOException {
        out.write(b);
        fileOffset += b.length;
    }

    private void writeBytes(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        fileOffset += len;
    }

    private void writeType(String type) throws IOException {
        out.write(ascii4(type));
        fileOffset += 4;
    }

    private void writeInt32(int v) throws IOException {
        out.write((v >>> 24) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
        fileOffset += 4;
    }

    private static byte[] matrixIdentity() {
        return concat(
                int32(0x00010000), int32(0), int32(0),
                int32(0), int32(0x00010000), int32(0),
                int32(0), int32(0), int32(0x40000000)
        );
    }

    private static byte[] fixed16_16(int v) {
        return int32(v << 16);
    }

    private static byte[] fullBoxHeader(int version, int flags) {
        return new byte[]{
                (byte) (version & 0xff),
                (byte) ((flags >>> 16) & 0xff),
                (byte) ((flags >>> 8) & 0xff),
                (byte) (flags & 0xff)
        };
    }

    private static byte[] box(String type, byte[]... payloads) {
        int payloadSize = 0;
        for (byte[] p : payloads) payloadSize += p.length;
        int size = 8 + payloadSize;
        byte[] out = new byte[size];
        putInt32(out, 0, size);
        byte[] t = ascii4(type);
        System.arraycopy(t, 0, out, 4, 4);
        int pos = 8;
        for (byte[] p : payloads) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static byte[] ascii4(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        if (b.length != 4) throw new IllegalArgumentException("type");
        return b;
    }

    private static byte[] int16(int v) {
        return new byte[]{
                (byte) ((v >>> 8) & 0xff),
                (byte) (v & 0xff)
        };
    }

    private static byte[] int32(int v) {
        return new byte[]{
                (byte) ((v >>> 24) & 0xff),
                (byte) ((v >>> 16) & 0xff),
                (byte) ((v >>> 8) & 0xff),
                (byte) (v & 0xff)
        };
    }

    private static byte[] int64(long v) {
        return new byte[]{
                (byte) ((v >>> 56) & 0xff),
                (byte) ((v >>> 48) & 0xff),
                (byte) ((v >>> 40) & 0xff),
                (byte) ((v >>> 32) & 0xff),
                (byte) ((v >>> 24) & 0xff),
                (byte) ((v >>> 16) & 0xff),
                (byte) ((v >>> 8) & 0xff),
                (byte) (v & 0xff)
        };
    }

    private static void putInt32(byte[] a, int off, int v) {
        a[off] = (byte) ((v >>> 24) & 0xff);
        a[off + 1] = (byte) ((v >>> 16) & 0xff);
        a[off + 2] = (byte) ((v >>> 8) & 0xff);
        a[off + 3] = (byte) (v & 0xff);
    }

    private static void putInt64(byte[] a, int off, long v) {
        a[off] = (byte) ((v >>> 56) & 0xff);
        a[off + 1] = (byte) ((v >>> 48) & 0xff);
        a[off + 2] = (byte) ((v >>> 40) & 0xff);
        a[off + 3] = (byte) ((v >>> 32) & 0xff);
        a[off + 4] = (byte) ((v >>> 24) & 0xff);
        a[off + 5] = (byte) ((v >>> 16) & 0xff);
        a[off + 6] = (byte) ((v >>> 8) & 0xff);
        a[off + 7] = (byte) (v & 0xff);
    }
}
