package app.napster;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Lazy thread-safe loader for Napster Legacy and Gen2 lookup tables.
 * Loads assets either from InputStream (Android Context.getAssets()) or File (JVM tests).
 * Pure Java 8, no android.* imports.
 */
public final class NapsterTables {

    public static final int LEGACY_TABLES_SIZE = 61440; // 16384 + 16384 + 4096 + 24576
    public static final int GEN2_UNCOMPRESSED_SIZE = 749568; // 13 * 57344 + 4096

    private static volatile byte[] sLegacyTables;
    private static volatile byte[] sGen2Tables;
    private static final Object LOCK = new Object();

    private NapsterTables() {
    }

    public static byte[] getLegacyTables(InputStream is) throws IOException {
        if (sLegacyTables != null) {
            return sLegacyTables;
        }
        synchronized (LOCK) {
            if (sLegacyTables == null) {
                if (is == null) {
                    throw new IOException("legacy_tables.bin InputStream is null");
                }
                byte[] data = readFully(is);
                if (data.length != LEGACY_TABLES_SIZE) {
                    throw new IOException("Invalid legacy_tables.bin size: " + data.length + ", expected " + LEGACY_TABLES_SIZE);
                }
                sLegacyTables = data;
            }
            return sLegacyTables;
        }
    }

    public static byte[] getGen2Tables(InputStream is) throws IOException {
        if (sGen2Tables != null) {
            return sGen2Tables;
        }
        synchronized (LOCK) {
            if (sGen2Tables == null) {
                if (is == null) {
                    throw new IOException("gen2_tables.bin.z InputStream is null");
                }
                byte[] compressed = readFully(is);
                byte[] decompressed = inflateZlib(compressed, GEN2_UNCOMPRESSED_SIZE);
                if (decompressed.length != GEN2_UNCOMPRESSED_SIZE) {
                    throw new IOException("Invalid gen2 decompressed size: " + decompressed.length + ", expected " + GEN2_UNCOMPRESSED_SIZE);
                }
                sGen2Tables = decompressed;
            }
            return sGen2Tables;
        }
    }

    /** For testing when running under standard JVM without Context. */
    public static void setLegacyTablesForTest(byte[] data) {
        synchronized (LOCK) {
            sLegacyTables = data;
        }
    }

    public static void setGen2TablesForTest(byte[] data) {
        synchronized (LOCK) {
            sGen2Tables = data;
        }
    }

    private static byte[] inflateZlib(byte[] compressed, int expectedLen) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[expectedLen];
            int count = 0;
            byte[] buf = new byte[8192];
            ByteArrayOutputStream baos = new ByteArrayOutputStream(expectedLen);
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("Inflater needs input/dictionary prematurely");
                    }
                    break;
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("Failed to decompress zlib data: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] readFully(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
            return baos.toByteArray();
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
            }
        }
    }
}
