package com.azizbgboss.bblinux;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Disk device — flat binary image on microSD card.
 *
 * MMIO layout (relative to DISK_BASE = 0x20000000):
 *   offset 0x000 (4 bytes) : sector index register (write to seek)
 *   offset 0x004 (512 bytes): sector data window (read/write)
 *
 * Sector size: 512 bytes (standard).
 * Image file: file:///SDCard/bblinux/disk.img
 */
public class DiskDevice {

    public static final String IMAGE_PATH = "file:///SDCard/bblinux/disk.img";
    public static final int SECTOR_SIZE = 512;

    private byte[] sectorBuf = new byte[SECTOR_SIZE];
    private int currentSector = -1; // cached sector index
    private int sectorReg = 0;      // sector index register
    private boolean dirty = false;

    // ── MMIO interface ────────────────────────────────────────────────────────

    public int readByte(int offset) {
        if (offset < 4) {
            // Sector register read
            return (sectorReg >> (offset * 8)) & 0xFF;
        }
        int dataOffset = offset - 4;
        if (dataOffset >= 0 && dataOffset < SECTOR_SIZE) {
            ensureSector(sectorReg);
            return sectorBuf[dataOffset] & 0xFF;
        }
        return 0;
    }

    public void writeByte(int offset, byte val) {
        if (offset < 4) {
            // Write sector index register byte by byte (little-endian)
            int shift = offset * 8;
            sectorReg = (sectorReg & ~(0xFF << shift)) | ((val & 0xFF) << shift);
            // Flush previous sector if dirty before switching
            if (sectorReg != currentSector && dirty) {
                flushSector();
            }
            return;
        }
        int dataOffset = offset - 4;
        if (dataOffset >= 0 && dataOffset < SECTOR_SIZE) {
            ensureSector(sectorReg);
            sectorBuf[dataOffset] = val;
            dirty = true;
        }
    }

    // ── Sector I/O ────────────────────────────────────────────────────────────

    private void ensureSector(int sector) {
        if (sector == currentSector) return;
        if (dirty) flushSector();
        readSector(sector);
        currentSector = sector;
        dirty = false;
    }

    private void readSector(int sector) {
        FileConnection fc = null;
        InputStream is = null;
        try {
            fc = (FileConnection) Connector.open(IMAGE_PATH, Connector.READ);
            is = fc.openInputStream();
            long skip = (long) sector * SECTOR_SIZE;
            // Skip to sector — InputStream.skip() may not skip all at once
            while (skip > 0) {
                long skipped = is.skip(skip);
                if (skipped <= 0) break;
                skip -= skipped;
            }
            int off = 0;
            while (off < SECTOR_SIZE) {
                int n = is.read(sectorBuf, off, SECTOR_SIZE - off);
                if (n < 0) break;
                off += n;
            }
            // Zero-fill if short read (end of image)
            while (off < SECTOR_SIZE) sectorBuf[off++] = 0;
        } catch (IOException e) {
            // Zero sector on error
            for (int i = 0; i < SECTOR_SIZE; i++) sectorBuf[i] = 0;
        } finally {
            try { if (is != null) is.close(); } catch (IOException e) {}
            try { if (fc != null) fc.close(); } catch (IOException e) {}
        }
    }

    private void flushSector() {
        if (currentSector < 0) return;
        FileConnection fc = null;
        OutputStream os = null;
        try {
            fc = (FileConnection) Connector.open(IMAGE_PATH, Connector.READ_WRITE);
            os = fc.openOutputStream((long) currentSector * SECTOR_SIZE);
            os.write(sectorBuf);
            os.flush();
        } catch (IOException e) {
            // Swallow — best effort
        } finally {
            try { if (os != null) os.close(); } catch (IOException e) {}
            try { if (fc != null) fc.close(); } catch (IOException e) {}
        }
        dirty = false;
    }

    public void flush() {
        if (dirty) flushSector();
    }

    /** Load an entire file into RAM directly (used at boot). */
    public byte[] loadImage(String path) throws IOException {
        javax.microedition.io.file.FileConnection fc = null;
        java.io.InputStream is = null;
        try {
            fc = (javax.microedition.io.file.FileConnection)
                javax.microedition.io.Connector.open(path, javax.microedition.io.Connector.READ);
            if (!fc.exists()) throw new IOException("File not found: " + path);
            int size = (int) fc.fileSize();
            byte[] buf = new byte[size];
            is = fc.openInputStream();
            int off = 0, n;
            while (off < size && (n = is.read(buf, off, size - off)) > 0) off += n;
            return buf;
        } finally {
            try { if (is != null) is.close(); } catch (IOException e) {}
            try { if (fc != null) fc.close(); } catch (IOException e) {}
        }
    }

    /** Backward-compatible wrapper for older boot code. */
    public byte[] loadKernelImage(String path) throws IOException {
        return loadImage(path);
    }
}
