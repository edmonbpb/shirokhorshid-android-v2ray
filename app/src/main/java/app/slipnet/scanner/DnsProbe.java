// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.
package app.slipnet.scanner;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Raw DNS probe helpers, wire-compatible with SlipNet's resolver scanner.
 */
public final class DnsProbe {

    public static final int TYPE_A = 1, TYPE_NS = 2, TYPE_TXT = 16;

    public enum Transport { UDP, TCP }

    public static final class DnsResponse {
        public boolean received;
        public int rcode;
        public String a;
        public List<String> ns;
        public byte[] raw;
        public int length;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Random RANDOM = new Random();

    private DnsProbe() {
    }

    /** Builds a query; ednsSize 0 omits the OPT record. */
    public static byte[] buildQuery(String name, int type, int ednsSize) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        int txid = RANDOM.nextInt(65536);
        b.write((txid >>> 8) & 0xFF);
        b.write(txid & 0xFF);
        b.write(0x01); // RD=1
        b.write(0x00);
        b.write(0x00);
        b.write(0x01); // QDCOUNT
        b.write(0x00);
        b.write(0x00); // ANCOUNT
        b.write(0x00);
        b.write(0x00); // NSCOUNT
        b.write(0x00);
        b.write(ednsSize > 0 ? 0x01 : 0x00); // ARCOUNT
        String[] labels = name.split("\\.");
        for (String label : labels) {
            int n = label.length();
            b.write(n & 0xFF);
            for (int i = 0; i < n; i++) {
                b.write(label.charAt(i) & 0xFF);
            }
        }
        b.write(0x00);
        b.write((type >>> 8) & 0xFF);
        b.write(type & 0xFF);
        b.write(0x00);
        b.write(0x01); // QCLASS IN
        if (ednsSize > 0) {
            b.write(0x00); // root name
            b.write(0x00);
            b.write(0x29); // OPT (41)
            b.write((ednsSize >>> 8) & 0xFF); // UDP payload size
            b.write(ednsSize & 0xFF);
            b.write(0x00); // extended rcode
            b.write(0x00); // version
            b.write(0x00); // flags
            b.write(0x00);
            b.write(0x00); // rdlen
            b.write(0x00);
        }
        return b.toByteArray();
    }

    /** Sends a prebuilt query; timeouts and errors yield received=false. */
    public static DnsResponse query(String host, int port, byte[] query, int timeoutMs,
                                    Transport t, int udpBuffer) {
        DnsResponse result = new DnsResponse();
        result.ns = new ArrayList<String>();
        result.raw = new byte[0];
        int timeout = Math.max(500, Math.min(30000, timeoutMs));
        Wire wire = t == Transport.TCP
                ? queryTcp(host, port, query, timeout)
                : queryUdp(host, port, query, timeout, udpBuffer);
        if (wire == null) {
            return result;
        }
        result.received = true;
        result.raw = wire.data;
        result.length = wire.length;
        result.rcode = wire.length >= 4 ? (wire.data[3] & 0x0F) : 0;
        try {
            result.a = parseDnsResponse(wire.data, wire.length);
        } catch (Exception ignored) {
            result.a = null;
        }
        try {
            result.ns = parseDnsNsResponse(wire.data, wire.length);
        } catch (Exception ignored) {
            result.ns = new ArrayList<String>();
        }
        return result;
    }

    /** True when the additional section contains an OPT record (type 41). */
    public static boolean hasOpt(byte[] raw, int length) {
        if (raw == null || length < 12 || length > raw.length) {
            return false;
        }
        int qdCount = ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
        int anCount = ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF);
        int nsCount = ((raw[8] & 0xFF) << 8) | (raw[9] & 0xFF);
        int arCount = ((raw[10] & 0xFF) << 8) | (raw[11] & 0xFF);
        if (arCount == 0) {
            return false;
        }
        int offset = 12;
        for (int i = 0; i < qdCount; i++) {
            offset = skipDnsName(raw, offset, length);
            if (offset < 0) {
                return false;
            }
            offset += 4;
            if (offset > length) {
                return false;
            }
        }
        for (int i = 0; i < anCount + nsCount; i++) {
            offset = skipDnsName(raw, offset, length);
            if (offset < 0) {
                return false;
            }
            if (offset + 10 > length) {
                return false;
            }
            int rdLength = ((raw[offset + 8] & 0xFF) << 8) | (raw[offset + 9] & 0xFF);
            offset += 10 + rdLength;
            if (offset > length) {
                return false;
            }
        }
        for (int i = 0; i < arCount; i++) {
            offset = skipDnsName(raw, offset, length);
            if (offset < 0) {
                return false;
            }
            if (offset + 10 > length) {
                return false;
            }
            int rrType = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
            if (rrType == 41) {
                return true;
            }
            int rdLength = ((raw[offset + 8] & 0xFF) << 8) | (raw[offset + 9] & 0xFF);
            offset += 10 + rdLength;
            if (offset > length) {
                return false;
            }
        }
        return false;
    }

    /** Concatenated raw bytes of the first TXT answer, or null. */
    public static byte[] firstTxtRaw(byte[] raw, int length) {
        if (raw == null || length < 12 || length > raw.length) {
            return null;
        }
        int anCount = ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF);
        if (anCount == 0) {
            return null;
        }
        int qdCount = ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
        int offset = 12;
        for (int i = 0; i < qdCount; i++) {
            offset = skipDnsName(raw, offset, length);
            if (offset < 0) {
                return null;
            }
            offset += 4;
            if (offset > length) {
                return null;
            }
        }
        for (int i = 0; i < anCount; i++) {
            offset = skipDnsName(raw, offset, length);
            if (offset < 0) {
                return null;
            }
            if (offset + 10 > length) {
                return null;
            }
            int rrType = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
            int rdLength = ((raw[offset + 8] & 0xFF) << 8) | (raw[offset + 9] & 0xFF);
            offset += 10;
            if (rrType == TYPE_TXT && rdLength > 0 && offset + rdLength <= length) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(rdLength);
                int pos = offset;
                int end = offset + rdLength;
                while (pos < end) {
                    int strLen = raw[pos] & 0xFF;
                    pos++;
                    if (pos + strLen > end) {
                        break;
                    }
                    out.write(raw, pos, strLen);
                    pos += strLen;
                }
                return out.toByteArray();
            }
            offset += rdLength;
        }
        return null;
    }

    private static Wire queryUdp(String host, int port, byte[] query, int timeout, int bufferSize) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeout);
            InetAddress address = InetAddress.getByName(host);
            socket.send(new DatagramPacket(query, query.length, address, port));
            byte[] buf = new byte[bufferSize];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            int len = packet.getLength();
            return new Wire(Arrays.copyOf(buf, len), len);
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static Wire queryTcp(String host, int port, byte[] query, int timeout) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            OutputStream out = socket.getOutputStream();
            out.write(new byte[]{
                    (byte) ((query.length >>> 8) & 0xFF),
                    (byte) (query.length & 0xFF)
            });
            out.write(query);
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] lenBuf = new byte[2];
            if (!readExact(in, lenBuf, 2)) {
                return null;
            }
            int respLen = ((lenBuf[0] & 0xFF) << 8) | (lenBuf[1] & 0xFF);
            if (respLen <= 0 || respLen > 65535) {
                return null;
            }
            byte[] resp = new byte[respLen];
            if (!readExact(in, resp, respLen)) {
                return null;
            }
            return new Wire(resp, respLen);
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean readExact(InputStream in, byte[] buf, int len) throws java.io.IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n <= 0) {
                return false;
            }
            read += n;
        }
        return true;
    }

    private static String parseDnsResponse(byte[] response, int length) {
        if (length < 12) {
            return null;
        }
        int responseCode = response[3] & 0x0F;
        if (responseCode != 0) {
            return null;
        }
        int answerCount = ((response[6] & 0xFF) << 8) | (response[7] & 0xFF);
        if (answerCount == 0) {
            return null;
        }
        int offset = 12;
        while (offset < length && response[offset] != 0) {
            int labelLength = response[offset] & 0xFF;
            if (labelLength >= 0xC0) {
                offset += 2;
                break;
            }
            offset += labelLength + 1;
        }
        if (offset < length && response[offset] == 0) {
            offset++;
        }
        offset += 4;
        for (int i = 0; i < answerCount; i++) {
            if (offset >= length) {
                break;
            }
            if ((response[offset] & 0xC0) == 0xC0) {
                offset += 2;
            } else {
                while (offset < length && response[offset] != 0) {
                    int labelLength = response[offset] & 0xFF;
                    if (labelLength >= 0xC0) {
                        offset += 2;
                        break;
                    }
                    offset += labelLength + 1;
                }
                if (offset < length && response[offset] == 0) {
                    offset++;
                }
            }
            if (offset + 10 > length) {
                break;
            }
            int recordType = ((response[offset] & 0xFF) << 8) | (response[offset + 1] & 0xFF);
            offset += 2;
            offset += 2;
            offset += 4;
            int dataLength = ((response[offset] & 0xFF) << 8) | (response[offset + 1] & 0xFF);
            offset += 2;
            if (recordType == TYPE_A && dataLength == 4 && offset + 4 <= length) {
                return (response[offset] & 0xFF) + "."
                        + (response[offset + 1] & 0xFF) + "."
                        + (response[offset + 2] & 0xFF) + "."
                        + (response[offset + 3] & 0xFF);
            }
            offset += dataLength;
        }
        return null;
    }

    private static List<String> parseDnsNsResponse(byte[] response, int length) {
        List<String> results = new ArrayList<String>();
        if (length < 12) {
            return results;
        }
        int anCount = ((response[6] & 0xFF) << 8) | (response[7] & 0xFF);
        int nsCount = ((response[8] & 0xFF) << 8) | (response[9] & 0xFF);
        int offset = skipQuestionSection(response, length);
        int afterAnswers = parseNsFromSection(response, length, offset, anCount, results);
        if (results.isEmpty() && nsCount > 0) {
            parseNsFromSection(response, length, afterAnswers, nsCount, results);
        }
        return results;
    }

    private static int skipQuestionSection(byte[] response, int length) {
        if (length < 12) {
            return length;
        }
        int qdCount = ((response[4] & 0xFF) << 8) | (response[5] & 0xFF);
        int offset = 12;
        for (int i = 0; i < qdCount; i++) {
            if (offset >= length) {
                break;
            }
            while (offset < length) {
                int labelLen = response[offset] & 0xFF;
                if (labelLen == 0) {
                    offset++;
                    break;
                }
                if ((labelLen & 0xC0) == 0xC0) {
                    offset += 2;
                    break;
                }
                offset += labelLen + 1;
            }
            offset += 4;
        }
        return offset;
    }

    private static int parseNsFromSection(byte[] response, int length, int startOffset,
                                          int count, List<String> results) {
        int offset = startOffset;
        for (int i = 0; i < count; i++) {
            if (offset >= length) {
                break;
            }
            NameRead rr = readDnsName(response, length, offset);
            offset = rr.after;
            if (offset + 10 > length) {
                break;
            }
            int rrType = ((response[offset] & 0xFF) << 8) | (response[offset + 1] & 0xFF);
            offset += 2;
            offset += 2;
            offset += 4;
            int rdLength = ((response[offset] & 0xFF) << 8) | (response[offset + 1] & 0xFF);
            offset += 2;
            if (rrType == TYPE_NS && offset + rdLength <= length) {
                NameRead ns = readDnsName(response, length, offset);
                if (ns.name.length() > 0) {
                    results.add(ns.name);
                }
            }
            offset += rdLength;
        }
        return offset;
    }

    private static NameRead readDnsName(byte[] response, int length, int startOffset) {
        List<String> parts = new ArrayList<String>();
        int offset = startOffset;
        boolean jumped = false;
        int returnOffset = -1;
        int safety = 0;
        while (offset < length && safety++ < 128) {
            int labelLen = response[offset] & 0xFF;
            if (labelLen == 0) {
                offset++;
                break;
            }
            if ((labelLen & 0xC0) == 0xC0) {
                if (offset + 1 >= length) {
                    break;
                }
                if (!jumped) {
                    returnOffset = offset + 2;
                }
                offset = ((labelLen & 0x3F) << 8) | (response[offset + 1] & 0xFF);
                jumped = true;
                continue;
            }
            offset++;
            if (offset + labelLen > length) {
                break;
            }
            parts.add(new String(response, offset, labelLen, UTF8));
            offset += labelLen;
        }
        int afterName = jumped ? returnOffset : offset;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(parts.get(i));
        }
        return new NameRead(sb.toString(), afterName);
    }

    private static int skipDnsName(byte[] data, int startOffset, int length) {
        int offset = startOffset;
        while (offset < length) {
            int labelLen = data[offset] & 0xFF;
            if (labelLen == 0) {
                return offset + 1;
            }
            if ((labelLen & 0xC0) == 0xC0) {
                return offset + 2;
            }
            offset += 1 + labelLen;
        }
        return -1;
    }

    private static final class Wire {
        final byte[] data;
        final int length;

        Wire(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }
    }

    private static final class NameRead {
        final String name;
        final int after;

        NameRead(String name, int after) {
            this.name = name;
            this.after = after;
        }
    }
}
