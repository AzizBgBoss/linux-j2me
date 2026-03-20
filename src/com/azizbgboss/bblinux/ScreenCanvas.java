package com.azizbgboss.bblinux;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

public class ScreenCanvas extends Canvas implements Runnable {

    private static final int DEFAULT_COLS = 80;
    private static final int DEFAULT_ROWS = 40;
    private static final long BLINK_MS = 500;
    private static final long REFRESH_MS = 50;

    private char[][] buf = new char[DEFAULT_ROWS][DEFAULT_COLS];
    private int cols = DEFAULT_COLS;
    private int rows = DEFAULT_ROWS;
    private int curCol = 0;
    private int curRow = 0;

    private boolean cursorOn = true;
    private boolean dirty = true;

    private UARTDevice uart;

    private boolean inEscape = false;
    private StringBuffer escapeBuf = new StringBuffer();

    public ScreenCanvas() {
        clearBuffer();
        setFullScreenMode(true);
        new Thread(this).start();
    }

    public void setUART(UARTDevice uart) {
        this.uart = uart;
    }

    public synchronized void putChar(char ch) {
        ensureLayout();
        clampCursor();

        if (inEscape) {
            escapeBuf.append(ch);
            if (isEscapeComplete()) {
                inEscape = false;
                handleEscapeSequence(escapeBuf.toString());
                escapeBuf.setLength(0);
            }
            return;
        }
        if (ch == 0x1B) {
            inEscape = true;
            escapeBuf.setLength(0);
            escapeBuf.append(ch);
            return;
        }

        switch (ch) {
            case '\n':
                curCol = 0;
                curRow++;
                if (curRow >= rows) scrollUp();
                break;
            case '\r':
                curCol = 0;
                break;
            case '\b':
            case 0x7F:
                if (curCol > 0) {
                    curCol--;
                    buf[curRow][curCol] = ' ';
                }
                break;
            case '\t':
                putTab();
                break;
            default:
                if (ch >= 0x20 && ch < 0x7F) {
                    buf[curRow][curCol] = ch;
                    curCol++;
                    if (curCol >= cols) {
                        curCol = 0;
                        curRow++;
                        if (curRow >= rows) scrollUp();
                    }
                }
                break;
        }
        dirty = true;
    }

    private boolean isEscapeComplete() {
        char ch;

        if (escapeBuf.length() < 2)
            return false;

        ch = escapeBuf.charAt(1);
        if (ch == '[')
            return escapeBuf.length() > 2 &&
                   escapeBuf.charAt(escapeBuf.length() - 1) >= '@' &&
                   escapeBuf.charAt(escapeBuf.length() - 1) <= '~';

        return escapeBuf.length() == 2;
    }

    private void putTab() {
        int nextStop = ((curCol / 8) + 1) * 8;
        while (curCol < nextStop) {
            buf[curRow][curCol] = ' ';
            curCol++;
            if (curCol >= cols) {
                curCol = 0;
                curRow++;
                if (curRow >= rows) scrollUp();
            }
        }
    }

    private int parseEscapeParam(String body, int index, int defaultValue) {
        int current = 0;
        int paramIndex = 0;
        boolean haveDigits = false;
        int i;

        for (i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch >= '0' && ch <= '9') {
                current = current * 10 + (ch - '0');
                haveDigits = true;
            } else if (ch == ';') {
                if (paramIndex == index)
                    return haveDigits ? current : defaultValue;
                paramIndex++;
                current = 0;
                haveDigits = false;
            }
        }

        if (paramIndex == index)
            return haveDigits ? current : defaultValue;
        return defaultValue;
    }

    private void clearLineFromCursor() {
        int c;
        for (c = curCol; c < cols; c++)
            buf[curRow][c] = ' ';
    }

    private void clearLineToCursor() {
        int c;
        for (c = 0; c <= curCol && c < cols; c++)
            buf[curRow][c] = ' ';
    }

    private void clearWholeLine() {
        int c;
        for (c = 0; c < cols; c++)
            buf[curRow][c] = ' ';
    }

    private void clearScreen() {
        clearBuffer();
        curCol = 0;
        curRow = 0;
    }

    private void clampCursor() {
        if (curCol < 0) curCol = 0;
        if (curCol >= cols) curCol = cols - 1;
        if (curRow < 0) curRow = 0;
        if (curRow >= rows) curRow = rows - 1;
    }

    private void handleEscapeSequence(String seq) {
        String body;
        char cmd;
        int n;
        int row;
        int col;

        if (seq.length() < 2 || seq.charAt(0) != 0x1B)
            return;

        if (seq.charAt(1) != '[') {
            if (seq.length() == 2 && seq.charAt(1) == 'c') {
                clearScreen();
                dirty = true;
            }
            return;
        }
        if (seq.length() < 3)
            return;

        cmd = seq.charAt(seq.length() - 1);
        body = seq.substring(2, seq.length() - 1);
        while (body.length() > 0 && (body.charAt(0) == '?' || body.charAt(0) == '>'))
            body = body.substring(1);

        switch (cmd) {
            case 'A':
                n = parseEscapeParam(body, 0, 1);
                curRow -= n;
                clampCursor();
                break;
            case 'B':
                n = parseEscapeParam(body, 0, 1);
                curRow += n;
                clampCursor();
                break;
            case 'C':
                n = parseEscapeParam(body, 0, 1);
                curCol += n;
                clampCursor();
                break;
            case 'D':
                n = parseEscapeParam(body, 0, 1);
                curCol -= n;
                clampCursor();
                break;
            case 'H':
            case 'f':
                row = parseEscapeParam(body, 0, 1) - 1;
                col = parseEscapeParam(body, 1, 1) - 1;
                curRow = row;
                curCol = col;
                clampCursor();
                break;
            case 'J':
                n = parseEscapeParam(body, 0, 0);
                if (n == 2)
                    clearScreen();
                break;
            case 'K':
                n = parseEscapeParam(body, 0, 0);
                if (n == 1)
                    clearLineToCursor();
                else if (n == 2)
                    clearWholeLine();
                else
                    clearLineFromCursor();
                break;
            case 'm':
                break;
            default:
                break;
        }
        dirty = true;
    }

    public synchronized void putString(String s) {
        int i;
        for (i = 0; i < s.length(); i++) {
            putChar(s.charAt(i));
        }
    }

    private void ensureLayout() {
        int newCols = getWidth() / TinyFont.CELL_W;
        int newRows = getHeight() / TinyFont.CELL_H;

        if (newCols < 1) newCols = DEFAULT_COLS;
        if (newRows < 1) newRows = DEFAULT_ROWS;

        if (newCols != cols || newRows != rows) {
            resizeBuffer(newCols, newRows);
        }
    }

    private void resizeBuffer(int newCols, int newRows) {
        char[][] newBuf = new char[newRows][newCols];
        int copyRows = rows < newRows ? rows : newRows;
        int srcStartRow = rows - copyRows;
        int dstStartRow = newRows - copyRows;
        int r;
        int c;

        for (r = 0; r < newRows; r++)
            for (c = 0; c < newCols; c++)
                newBuf[r][c] = ' ';

        for (r = 0; r < copyRows; r++) {
            int copyCols = cols < newCols ? cols : newCols;
            System.arraycopy(buf[srcStartRow + r], 0, newBuf[dstStartRow + r], 0, copyCols);
        }

        buf = newBuf;
        cols = newCols;
        rows = newRows;
        if (curCol >= cols) curCol = cols - 1;
        if (curCol < 0) curCol = 0;
        if (curRow >= rows) curRow = rows - 1;
        if (curRow < 0) curRow = 0;
    }

    private void scrollUp() {
        int r;
        int c;
        for (r = 0; r < rows - 1; r++) {
            System.arraycopy(buf[r + 1], 0, buf[r], 0, cols);
        }
        for (c = 0; c < cols; c++)
            buf[rows - 1][c] = ' ';
        curRow = rows - 1;
    }

    private void clearBuffer() {
        int r;
        int c;
        for (r = 0; r < rows; r++)
            for (c = 0; c < cols; c++)
                buf[r][c] = ' ';
    }

    protected void sizeChanged(int w, int h) {
        synchronized (this) {
            ensureLayout();
        }
        dirty = true;
        repaint();
    }

    protected void paint(Graphics g) {
        int r;
        int c;

        g.setColor(0x000000);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(0x00FF00);

        synchronized (this) {
            ensureLayout();
            for (r = 0; r < rows; r++) {
                int y = r * TinyFont.CELL_H;
                for (c = 0; c < cols; c++) {
                    char ch = buf[r][c];
                    if (ch != ' ') {
                        TinyFont.drawChar(g, ch, c * TinyFont.CELL_W, y);
                    }
                }
            }

            if (cursorOn) {
                int cx = curCol * TinyFont.CELL_W;
                int cy = curRow * TinyFont.CELL_H + TinyFont.GLYPH_H;
                g.fillRect(cx, cy, TinyFont.GLYPH_W, 1);
            }
        }
    }

    public void run() {
        long lastBlink = System.currentTimeMillis();

        while (true) {
            boolean doRepaint = false;
            long now;

            try { Thread.sleep(REFRESH_MS); } catch (InterruptedException e) {}
            now = System.currentTimeMillis();

            synchronized (this) {
                if (now - lastBlink >= BLINK_MS) {
                    cursorOn = !cursorOn;
                    lastBlink = now;
                    doRepaint = true;
                }
                if (dirty) {
                    dirty = false;
                    doRepaint = true;
                }
            }

            if (doRepaint)
                repaint();
        }
    }

    protected void keyPressed(int keyCode) {
        if (uart == null) return;

        try {
            char ch = 0;
            String name;
            String upperName;

            switch (keyCode) {
                case Canvas.KEY_NUM0: ch = '0'; break;
                case Canvas.KEY_NUM1: ch = '1'; break;
                case Canvas.KEY_NUM2: ch = '2'; break;
                case Canvas.KEY_NUM3: ch = '3'; break;
                case Canvas.KEY_NUM4: ch = '4'; break;
                case Canvas.KEY_NUM5: ch = '5'; break;
                case Canvas.KEY_NUM6: ch = '6'; break;
                case Canvas.KEY_NUM7: ch = '7'; break;
                case Canvas.KEY_NUM8: ch = '8'; break;
                case Canvas.KEY_NUM9: ch = '9'; break;
                default:
                    name = getKeyName(keyCode);
                    upperName = (name == null) ? null : name.toUpperCase();
                    if (keyCode == 32 ||
                        "SPACE".equals(upperName) ||
                        "SPACEBAR".equals(upperName) ||
                        "SPACE BAR".equals(upperName)) {
                        ch = ' ';
                    } else if (name != null && name.length() == 1) {
                        ch = name.charAt(0);
                    } else if (keyCode == -8 || keyCode == 8 ||
                               "BACKSPACE".equals(upperName) ||
                               "DELETE".equals(upperName)) {
                        ch = 0x7F;
                    } else if (keyCode == -5 || keyCode == 10 || keyCode == 13 ||
                               "ENTER".equals(upperName) ||
                               "RETURN".equals(upperName) ||
                               "SELECT".equals(upperName)) {
                        ch = '\n';
                    } 
                    break;
            }

            if (ch != 0) {
                uart.pushKey(ch);
            }
        } catch (Throwable t) {
            inEscape = false;
            escapeBuf.setLength(0);
            dirty = true;
            repaint();
        }
    }
}
