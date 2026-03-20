package com.azizbgboss.bblinux;

/**
 * UART device — serial console bridge between emulator and BB screen/keyboard.
 * TX (emulator writes) -> ScreenCanvas character buffer
 * RX (emulator reads) <- keyboard input queue
 *
 * Thread-safe: CPU thread writes TX, UI thread writes RX queue.
 */
public class UARTDevice {
    private ScreenCanvas screen;
    private int txCount = 0;
    
    private final Object rxLock = new Object(); // separate lock for RX only
    private byte[] rxBuf = new byte[256];
    private int rxHead = 0;
    private int rxTail = 0;
    private int rxPushCount = 0;

    // NO synchronized — only CPU thread calls this
    public void write(char ch) {
        txCount++;
        screen.putChar(ch);
    }

    public int read() {
        synchronized (rxLock) {
            if (rxHead == rxTail) return -1;
            int ch = rxBuf[rxHead] & 0xFF;
            rxHead = (rxHead + 1) % rxBuf.length;
            return ch;
        }
    }

    public void pushKey(char ch) {
        synchronized (rxLock) {
            int next = (rxTail + 1) % rxBuf.length;
            if (next != rxHead) {
                rxBuf[rxTail] = (byte) ch;
                rxTail = next;
                rxPushCount++;
            }
        }
    }

    public boolean hasData() {
        synchronized (rxLock) { return rxHead != rxTail; }
    }

    public void setScreen(ScreenCanvas screen) {
    this.screen = screen;
}

    public int getTxCount() { return txCount; }
    public int getRxPushCount() { synchronized (rxLock) { return rxPushCount; } }
}