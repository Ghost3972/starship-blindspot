package com.elliot.blindspot;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class MovMjpegReader implements Closeable {
    private final RandomAccessFile raf;
    private final int width;
    private final int height;
    private final int timescale;
    private final int sampleDuration;
    private final int sampleCount;
    private final int[] sampleSizes;
    private final long[] sampleOffsets;
    private final int[] sampleDurations;
    private final long[] cumulativeDurations;

    static MovMjpegReader open(File file) throws IOException {
        return new MovMjpegReader(file);
    }

    private MovMjpegReader(File file) throws IOException {
        this.raf = new RandomAccessFile(file, "r");
        Box moov = findTopLevelBox(raf, "moov");
        if (moov == null) throw new IOException("moov not found");
        byte[] moovData = new byte[(int) moov.size];
        raf.seek(moov.offset);
        raf.readFully(moovData);

        ParseResult r = parseMoov(moovData);
        this.width = r.width;
        this.height = r.height;
        this.timescale = r.timescale;
        this.sampleDuration = r.sampleDuration;
        this.sampleCount = r.sampleCount;
        this.sampleSizes = r.sampleSizes;
        this.sampleOffsets = r.sampleOffsets;
        this.sampleDurations = r.sampleDurations != null ? r.sampleDurations : new int[sampleCount];
        this.cumulativeDurations = buildCumulative(this.sampleDurations);
        if (sampleCount <= 0) throw new IOException("empty");
        if (sampleSizes.length != sampleCount) throw new IOException("stsz mismatch");
        if (sampleOffsets.length != sampleCount) throw new IOException("stco mismatch");
        if (sampleDurations.length != sampleCount) throw new IOException("stts mismatch");
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    int getTimescale() {
        return timescale;
    }

    int getSampleDuration() {
        return sampleDuration;
    }

    int getSampleCount() {
        return sampleCount;
    }

    long getTotalDurationMs() {
        long units = cumulativeDurations != null && cumulativeDurations.length > 0
                ? cumulativeDurations[cumulativeDurations.length - 1]
                : 0;
        if (timescale <= 0) return 0;
        return (units * 1000L) / (long) timescale;
    }

    long getPositionMsForSampleIndex(int index) {
        if (index <= 0) return 0;
        if (index >= sampleCount) return getTotalDurationMs();
        if (timescale <= 0) return 0;
        long units = cumulativeDurations[index];
        return (units * 1000L) / (long) timescale;
    }

    long getSampleDurationMs(int index) {
        if (index < 0 || index >= sampleCount) return 0;
        if (timescale <= 0) return 0;
        long units = sampleDurations[index];
        if (units < 0) units = 0;
        return (units * 1000L) / (long) timescale;
    }

    int findSampleIndexByPositionMs(long posMs) {
        if (sampleCount <= 0) return 0;
        if (posMs <= 0) return 0;
        if (timescale <= 0) return 0;
        long targetUnits = (posMs * (long) timescale) / 1000L;
        long[] cum = cumulativeDurations;
        int lo = 0;
        int hi = sampleCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            long v = cum[mid];
            if (v <= targetUnits) lo = mid + 1;
            else hi = mid;
        }
        int idx = lo - 1;
        if (idx < 0) idx = 0;
        if (idx >= sampleCount) idx = sampleCount - 1;
        return idx;
    }

    byte[] readJpegSample(int index) throws IOException {
        if (index < 0 || index >= sampleCount) throw new IndexOutOfBoundsException();
        int size = sampleSizes[index];
        long off = sampleOffsets[index];
        byte[] out = new byte[size];
        raf.seek(off);
        raf.readFully(out);
        return out;
    }

    int readJpegSample(int index, byte[] dst, int dstOff) throws IOException {
        if (index < 0 || index >= sampleCount) throw new IndexOutOfBoundsException();
        int size = sampleSizes[index];
        if (dstOff < 0 || dstOff + size > dst.length) throw new IndexOutOfBoundsException();
        long off = sampleOffsets[index];
        raf.seek(off);
        raf.readFully(dst, dstOff, size);
        return size;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    private static final class Box {
        final long offset;
        final long size;
        final String type;
        final long headerSize;

        Box(long offset, long size, String type, long headerSize) {
            this.offset = offset;
            this.size = size;
            this.type = type;
            this.headerSize = headerSize;
        }
    }

    private static Box findTopLevelBox(RandomAccessFile raf, String wantType) throws IOException {
        raf.seek(0);
        long fileLen = raf.length();
        long pos = 0;
        while (pos + 8 <= fileLen) {
            raf.seek(pos);
            long size = readUInt32(raf);
            String type = readType(raf);
            long header = 8;
            if (size == 1) {
                size = raf.readLong();
                header = 16;
            } else if (size == 0) {
                size = fileLen - pos;
            }
            if (size < header) throw new IOException("bad box size");
            if (wantType.equals(type)) {
                return new Box(pos, size, type, header);
            }
            pos += size;
        }
        return null;
    }

    private static final class ParseResult {
        int width;
        int height;
        int timescale;
        int sampleDuration;
        int sampleCount;
        int[] sampleSizes = new int[0];
        long[] sampleOffsets = new long[0];
        int[] sampleDurations = new int[0];
    }

    private static ParseResult parseMoov(byte[] moov) throws IOException {
        ParseResult r = new ParseResult();
        int end = moov.length;
        int pos = 8;
        while (pos < end) {
            BoxView b = readBoxView(moov, pos, end);
            if ("trak".equals(b.type)) {
                ParseResult tr = parseTrak(moov, b.contentStart, b.contentEnd);
                if (tr.sampleCount > 0) return tr;
            }
            pos = b.next;
        }
        throw new IOException("video trak not found");
    }

    private static ParseResult parseTrak(byte[] data, int start, int end) throws IOException {
        ParseResult r = new ParseResult();
        int pos = start;
        while (pos < end) {
            BoxView b = readBoxView(data, pos, end);
            if ("tkhd".equals(b.type)) {
                parseTkhd(data, b.contentStart, b.contentEnd, r);
            } else if ("mdia".equals(b.type)) {
                parseMdia(data, b.contentStart, b.contentEnd, r);
            }
            pos = b.next;
        }
        return r;
    }

    private static void parseTkhd(byte[] data, int start, int end, ParseResult r) throws IOException {
        if (end - start < 4) return;
        int version = data[start] & 0xff;
        int p = start + 4;
        if (version == 1) {
            p += 8 + 8 + 4 + 4 + 8 + 8 + 4 + 4 + 2 + 2 + 2 + 2 + 36;
        } else {
            p += 4 + 4 + 4 + 4 + 4 + 8 + 4 + 4 + 2 + 2 + 2 + 2 + 36;
        }
        if (p + 8 <= end) {
            int w = readInt32(data, p);
            int h = readInt32(data, p + 4);
            r.width = w >>> 16;
            r.height = h >>> 16;
        }
    }

    private static void parseMdia(byte[] data, int start, int end, ParseResult r) throws IOException {
        int pos = start;
        while (pos < end) {
            BoxView b = readBoxView(data, pos, end);
            if ("mdhd".equals(b.type)) {
                parseMdhd(data, b.contentStart, b.contentEnd, r);
            } else if ("minf".equals(b.type)) {
                parseMinf(data, b.contentStart, b.contentEnd, r);
            }
            pos = b.next;
        }
    }

    private static void parseMdhd(byte[] data, int start, int end, ParseResult r) throws IOException {
        if (end - start < 4) return;
        int version = data[start] & 0xff;
        int p = start + 4;
        if (version == 1) {
            p += 8 + 8;
            if (p + 4 <= end) r.timescale = readInt32(data, p);
        } else {
            p += 4 + 4;
            if (p + 4 <= end) r.timescale = readInt32(data, p);
        }
    }

    private static void parseMinf(byte[] data, int start, int end, ParseResult r) throws IOException {
        int pos = start;
        while (pos < end) {
            BoxView b = readBoxView(data, pos, end);
            if ("stbl".equals(b.type)) {
                parseStbl(data, b.contentStart, b.contentEnd, r);
            }
            pos = b.next;
        }
    }

    private static void parseStbl(byte[] data, int start, int end, ParseResult r) throws IOException {
        int pos = start;
        while (pos < end) {
            BoxView b = readBoxView(data, pos, end);
            if ("stts".equals(b.type)) {
                parseStts(data, b.contentStart, b.contentEnd, r);
            } else if ("stsz".equals(b.type)) {
                parseStsz(data, b.contentStart, b.contentEnd, r);
            } else if ("stco".equals(b.type) || "co64".equals(b.type)) {
                parseStco(data, b.type, b.contentStart, b.contentEnd, r);
            }
            pos = b.next;
        }
    }

    private static void parseStts(byte[] data, int start, int end, ParseResult r) throws IOException {
        int p = start + 4;
        if (p + 4 > end) return;
        int entryCount = readInt32(data, p);
        p += 4;
        if (entryCount <= 0) return;
        int totalSamples = 0;
        int firstDelta = 0;
        int[] counts = new int[Math.max(1, entryCount)];
        int[] deltas = new int[Math.max(1, entryCount)];
        int used = 0;
        for (int i = 0; i < entryCount; i++) {
            if (p + 8 > end) break;
            int count = readInt32(data, p);
            int delta = readInt32(data, p + 4);
            p += 8;
            if (count <= 0) continue;
            if (used == 0) firstDelta = delta;
            counts[used] = count;
            deltas[used] = delta;
            used++;
            totalSamples += count;
        }
        if (totalSamples <= 0) return;
        int[] durations = new int[totalSamples];
        int pos = 0;
        for (int i = 0; i < used; i++) {
            int c = counts[i];
            int d = deltas[i];
            for (int j = 0; j < c && pos < totalSamples; j++) {
                durations[pos++] = d;
            }
        }
        r.sampleCount = totalSamples;
        r.sampleDuration = firstDelta;
        r.sampleDurations = durations;
    }

    private static long[] buildCumulative(int[] durations) {
        int n = durations != null ? durations.length : 0;
        long[] cum = new long[n + 1];
        long s = 0;
        for (int i = 0; i < n; i++) {
            int d = durations[i];
            if (d < 0) d = 0;
            s += (long) d;
            cum[i + 1] = s;
        }
        return cum;
    }

    private static void parseStsz(byte[] data, int start, int end, ParseResult r) throws IOException {
        int p = start + 4;
        if (p + 8 > end) return;
        int sampleSize = readInt32(data, p);
        int sampleCount = readInt32(data, p + 4);
        p += 8;
        r.sampleCount = Math.max(r.sampleCount, sampleCount);
        if (sampleCount <= 0) {
            r.sampleSizes = new int[0];
            return;
        }
        int[] sizes = new int[sampleCount];
        if (sampleSize != 0) {
            Arrays.fill(sizes, sampleSize);
        } else {
            int need = sampleCount * 4;
            if (p + need > end) throw new EOFException();
            for (int i = 0; i < sampleCount; i++) {
                sizes[i] = readInt32(data, p);
                p += 4;
            }
        }
        r.sampleSizes = sizes;
    }

    private static void parseStco(byte[] data, String type, int start, int end, ParseResult r) throws IOException {
        int p = start + 4;
        if (p + 4 > end) return;
        int entryCount = readInt32(data, p);
        p += 4;
        if (entryCount <= 0) {
            r.sampleOffsets = new long[0];
            return;
        }
        long[] offs = new long[entryCount];
        if ("co64".equals(type)) {
            int need = entryCount * 8;
            if (p + need > end) throw new EOFException();
            for (int i = 0; i < entryCount; i++) {
                offs[i] = readInt64(data, p);
                p += 8;
            }
        } else {
            int need = entryCount * 4;
            if (p + need > end) throw new EOFException();
            for (int i = 0; i < entryCount; i++) {
                offs[i] = readUInt32FromBytes(data, p);
                p += 4;
            }
        }
        r.sampleOffsets = offs;
    }

    private static final class BoxView {
        final String type;
        final int contentStart;
        final int contentEnd;
        final int next;

        BoxView(String type, int contentStart, int contentEnd, int next) {
            this.type = type;
            this.contentStart = contentStart;
            this.contentEnd = contentEnd;
            this.next = next;
        }
    }

    private static BoxView readBoxView(byte[] data, int pos, int end) throws IOException {
        if (pos + 8 > end) throw new EOFException();
        long size = readUInt32FromBytes(data, pos);
        String type = new String(data, pos + 4, 4, StandardCharsets.US_ASCII);
        int header = 8;
        if (size == 1) {
            if (pos + 16 > end) throw new EOFException();
            size = readInt64(data, pos + 8);
            header = 16;
        } else if (size == 0) {
            size = end - pos;
        }
        if (size < header) throw new IOException("bad box");
        long next = pos + size;
        if (next > end) throw new EOFException();
        int cs = pos + header;
        int ce = (int) next;
        return new BoxView(type, cs, ce, (int) next);
    }

    private static long readUInt32(RandomAccessFile raf) throws IOException {
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        int b4 = raf.read();
        if ((b1 | b2 | b3 | b4) < 0) throw new EOFException();
        return ((long) b1 << 24) | ((long) b2 << 16) | ((long) b3 << 8) | (long) b4;
    }

    private static String readType(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static int readInt32(byte[] a, int off) {
        return ((a[off] & 0xff) << 24) | ((a[off + 1] & 0xff) << 16) | ((a[off + 2] & 0xff) << 8) | (a[off + 3] & 0xff);
    }

    private static long readUInt32FromBytes(byte[] a, int off) {
        return ((long) (a[off] & 0xff) << 24) | ((long) (a[off + 1] & 0xff) << 16) | ((long) (a[off + 2] & 0xff) << 8) | (long) (a[off + 3] & 0xff);
    }

    private static long readInt64(byte[] a, int off) {
        return ((long) (a[off] & 0xff) << 56)
                | ((long) (a[off + 1] & 0xff) << 48)
                | ((long) (a[off + 2] & 0xff) << 40)
                | ((long) (a[off + 3] & 0xff) << 32)
                | ((long) (a[off + 4] & 0xff) << 24)
                | ((long) (a[off + 5] & 0xff) << 16)
                | ((long) (a[off + 6] & 0xff) << 8)
                | (long) (a[off + 7] & 0xff);
    }
}
