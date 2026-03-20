package com.azizbgboss.bblinux;

/**
 * UART device — serial console bridge between emulator and BB screen/keyboard.
 * TX (emulator writes) -> ScreenCanvas character buffer
 * RX (emulator reads)  <- keyboard input queue
 *
 * Thread-safe: CPU thread writes TX, UI thread writes RX queue.
 */
public class UARTDevice {

    private ScreenCanvas screen;
    private int txCount = 0;

    // ── RX input queue (keyboard → emulator) ──────────────────────────────────
    private byte[] rxBuf = new byte[256];
    private int rxHead = 0;
    private int rxTail = 0;
    private int rxPushCount = 0;

    public UARTDevice(ScreenCanvas screen) {
        this.screen = screen;
    }

    /** Called by CPU/bus when emulated code writes a character to UART TX */
    public synchronized void write(char ch) {
        txCount++;
        screen.putChar(ch);
    }

    /**
     * Called by CPU/bus when emulated code reads from UART RX.
     * Returns -1 if no data available (non-blocking).
     * For blocking reads the CPU loop should spin or yield.
     */
    public synchronized int read() {
        if (rxHead == rxTail) return -1; // empty
        int ch = rxBuf[rxHead] & 0xFF;
        rxHead = (rxHead + 1) % rxBuf.length;
        return ch;
    }

    /** Called by UI thread when user presses a key */
    public synchronized void pushKey(char ch) {
        int next = (rxTail + 1) % rxBuf.length;
        if (next != rxHead) { // not full
            rxBuf[rxTail] = (byte) ch;
            rxTail = next;
            rxPushCount++;
        }
    }

    public synchronized boolean hasData() {
        return rxHead != rxTail;
    }

    public synchronized int getTxCount() {
        return txCount;
    }

    public synchronized int getRxPushCount() {
        return rxPushCount;
    }
}
