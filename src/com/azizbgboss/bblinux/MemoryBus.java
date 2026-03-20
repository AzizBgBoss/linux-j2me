package com.azizbgboss.bblinux;

public class MemoryBus {

    public static final int DEFAULT_RAM_SIZE = 16 * 1024 * 1024;
    public static final int RAM_BASE = 0x80000000;
    public static final int UART_BASE = 0x10000000;
    public static final int DISK_BASE = 0x20000000;
    public static final int POWER_BASE = 0x30000000;
    public static final int CLINT_BASE = 0x02000000;
    public static final int TEST_BASE = 0x00100000;
    private static final int[] RAM_CANDIDATES = {
            20 * 1024 * 1024,
            DEFAULT_RAM_SIZE
    };

    private static final int UART_RBR = 0;
    private static final int UART_THR = 0;
    private static final int UART_DLL = 0;
    private static final int UART_IER = 1;
    private static final int UART_DLM = 1;
    private static final int UART_IIR = 2;
    private static final int UART_FCR = 2;
    private static final int UART_LCR = 3;
    private static final int UART_MCR = 4;
    private static final int UART_LSR = 5;
    private static final int UART_MSR = 6;
    private static final int UART_SCR = 7;

    public byte[] ram;
    private UARTDevice uart;
    private DiskDevice disk;
    private int resetVec = RAM_BASE;

    public int cycleCount = 0;
    public boolean haltRequested = false;

    private long machineTime = 0L;
    private long mtimecmp = Long.MAX_VALUE;

    private int uartIer = 0;
    private int uartFcr = 0;
    private int uartLcr = 0x03;
    private int uartMcr = 0;
    private int uartScr = 0;
    private int uartDll = 1;
    private int uartDlm = 0;

    public MemoryBus(UARTDevice uart, DiskDevice disk) {
        ram = allocateRam();
        this.uart = uart;
        this.disk = disk;
    }

    public void setResetVector(int addr) {
        resetVec = addr;
    }

    public int resetVector() {
        return resetVec;
    }

    public byte[] getRam() {
        return ram;
    }

    public int getRamSize() {
        return ram.length;
    }

    private byte[] allocateRam() {
        int i;

        for (i = 0; i < RAM_CANDIDATES.length; i++) {
            try {
                return new byte[RAM_CANDIDATES[i]];
            } catch (OutOfMemoryError e) {
                // Try the next smaller RAM size.
            }
        }
        return new byte[DEFAULT_RAM_SIZE];
    }

    public void setMachineTime(long time) {
        machineTime = time;
        if (time > 0x7FFFFFFFL) {
            cycleCount = 0x7FFFFFFF;
        } else if (time < 0L) {
            cycleCount = 0;
        } else {
            cycleCount = (int) time;
        }
    }

    public void advanceMachineTime(long delta) {
        if (delta <= 0L)
            return;
        setMachineTime(machineTime + delta);
    }

    public long getMachineTime() {
        return machineTime;
    }

    public long timeUntilMachineTimer() {
        if (mtimecmp == Long.MAX_VALUE)
            return Long.MAX_VALUE;
        if (machineTime >= mtimecmp)
            return 0L;
        return mtimecmp - machineTime;
    }

    public boolean isMachineTimerPending() {
        return machineTime >= mtimecmp;
    }

    public boolean isRamAddress(int addr) {
        return inRam(addr);
    }

    public int ramOffsetOf(int addr) {
        return ramOffset(addr);
    }

    public int loadByte(int addr) {
        if (inRam(addr))
            return ram[ramOffset(addr)] & 0xFF;
        return mmioLoadByte(addr);
    }

    public int loadHalf(int addr) {
        if (inRam(addr)) {
            int a = ramOffset(addr);
            return (ram[a] & 0xFF) | ((ram[a + 1] & 0xFF) << 8);
        }
        return mmioLoadByte(addr) | (mmioLoadByte(addr + 1) << 8);
    }

    public int loadWord(int addr) {
        if (inRam(addr)) {
            int a = ramOffset(addr);
            return (ram[a] & 0xFF) |
                    ((ram[a + 1] & 0xFF) << 8) |
                    ((ram[a + 2] & 0xFF) << 16) |
                    ((ram[a + 3] & 0xFF) << 24);
        }
        return mmioLoadWord(addr);
    }

    public void storeByte(int addr, byte val) {
        if (inRam(addr)) {
            ram[ramOffset(addr)] = val;
            return;
        }
        mmioStoreByte(addr, val);
    }

    public void storeHalf(int addr, short val) {
        if (inRam(addr)) {
            int a = ramOffset(addr);
            ram[a] = (byte) val;
            ram[a + 1] = (byte) (val >> 8);
            return;
        }
        mmioStoreByte(addr, (byte) val);
        mmioStoreByte(addr + 1, (byte) (val >> 8));
    }

    public void storeWord(int addr, int val) {
        if (inRam(addr)) {
            int a = ramOffset(addr);
            ram[a] = (byte) val;
            ram[a + 1] = (byte) (val >> 8);
            ram[a + 2] = (byte) (val >> 16);
            ram[a + 3] = (byte) (val >> 24);
            return;
        }
        mmioStoreWord(addr, val);
    }

    private int lastSyscall = -1;
    private int lastSyscallCount = 0;

    public void ecall(int[] regs) {
        int syscall = regs[17];

        if (syscall != lastSyscall) {
            if (lastSyscallCount > 1) {
                System.out.println("  [syscall " + lastSyscall + " x" + lastSyscallCount + "]");
            }
            System.out.println("[SYSCALL " + syscall + "] a0=" + regs[10] + " a1=" + regs[11] + " a2=" + regs[12]);
            lastSyscall = syscall;
            lastSyscallCount = 1;
        } else {
            lastSyscallCount++;
        }

        switch (syscall) {
            case 64: {
                int fd = regs[10];
                int buf = regs[11];
                int count = regs[12];
                int i;
                if (fd == 1 || fd == 2) {
                    for (i = 0; i < count; i++)
                        uart.write((char) (loadByte(buf + i) & 0xFF));
                }
                uart.write('w');
                regs[10] = count;
                break;
            }
            case 63: {
                int fd = regs[10];
                int buf = regs[11];
                int count = regs[12];
                int n = 0;
                if (fd == 0) {
                    int ch = uart.read();
                    if (ch >= 0 && count > 0) {
                        storeByte(buf, (byte) ch);
                        n = 1;
                    }
                }
                if (n == 0)
                    uart.write('r');
                regs[10] = n;
                break;
            }
            case 17:
                regs[10] = regs[10];
                break;
            case 19:
                regs[10] = 0;
                break;
            case 214:
                regs[10] = RAM_BASE + 0x01000000;
                break;
            case 220:
                if (regs[11] != 0) {
                    storeWord(regs[11], (int) (machineTime / 1000L));
                    storeWord(regs[11] + 4, (int) ((machineTime % 1000L) * 1000000L));
                }
                regs[10] = 0;
                break;
            case 93:
            case 94:
                haltRequested = true;
                break;
            default: {
                String msg = "?SYS" + syscall + " ";
                for (int si = 0; si < msg.length(); si++)
                    uart.write(msg.charAt(si));
                regs[10] = -38;
                break;
            }
        }
    }

    public void flushSyscallLog() {
        if (lastSyscallCount > 1) {
            System.out.println("  [syscall " + lastSyscall + " x" + lastSyscallCount + "]");
        }
    }

    private int mmioLoadByte(int addr) {
        if ((addr & 0xFFFFFFF8) == UART_BASE) {
            int off = addr - UART_BASE;
            switch (off) {
                case UART_RBR:
                    if ((uartLcr & 0x80) != 0)
                        return uartDll;
                    if (!uart.hasData())
                        return 0;
                    return uart.read() & 0xFF;
                case UART_IER:
                    if ((uartLcr & 0x80) != 0)
                        return uartDlm;
                    return uartIer;
                case UART_IIR:
                    return uart.hasData() ? 0x04 : 0x01;
                case UART_LCR:
                    return uartLcr;
                case UART_MCR:
                    return uartMcr;
                case UART_LSR:
                    return 0x60 | (uart.hasData() ? 0x01 : 0x00);
                case UART_MSR:
                    return 0xB0;
                case UART_SCR:
                    return uartScr;
            }
        }

        if ((addr & 0xFFFFF000) == DISK_BASE)
            return disk.readByte(addr - DISK_BASE);

        return 0;
    }

    private int mmioLoadWord(int addr) {
        long off;

        if (addr >= CLINT_BASE && addr < CLINT_BASE + 0x10000) {
            off = (addr - CLINT_BASE) & 0xFFFFFFFFL;
            if (off == 0x4000L)
                return (int) (mtimecmp & 0xFFFFFFFFL);
            if (off == 0x4004L)
                return (int) ((mtimecmp >>> 32) & 0xFFFFFFFFL);
            if (off == 0xBFF8L)
                return (int) (machineTime & 0xFFFFFFFFL);
            if (off == 0xBFFCL)
                return (int) ((machineTime >>> 32) & 0xFFFFFFFFL);
            return 0;
        }

        if ((addr & 0xFFFFFFF8) == UART_BASE) {
            int b0 = mmioLoadByte(addr) & 0xFF;
            int b1 = mmioLoadByte(addr + 1) & 0xFF;
            int b2 = mmioLoadByte(addr + 2) & 0xFF;
            int b3 = mmioLoadByte(addr + 3) & 0xFF;
            return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }

        return (mmioLoadByte(addr) & 0xFF) |
                ((mmioLoadByte(addr + 1) & 0xFF) << 8) |
                ((mmioLoadByte(addr + 2) & 0xFF) << 16) |
                ((mmioLoadByte(addr + 3) & 0xFF) << 24);
    }

    private void mmioStoreByte(int addr, byte val) {
        if ((addr & 0xFFFFFFF8) == UART_BASE) {
            int off = addr - UART_BASE;
            switch (off) {
                case UART_THR:
                    if ((uartLcr & 0x80) != 0) {
                        uartDll = val & 0xFF;
                    } else {
                        uart.write((char) (val & 0xFF));
                    }
                    return;
                case UART_IER:
                    if ((uartLcr & 0x80) != 0) {
                        uartDlm = val & 0xFF;
                    } else {
                        uartIer = val & 0x0F;
                    }
                    return;
                case UART_FCR:
                    uartFcr = val & 0xFF;
                    return;
                case UART_LCR:
                    uartLcr = val & 0xFF;
                    return;
                case UART_MCR:
                    uartMcr = val & 0xFF;
                    return;
                case UART_SCR:
                    uartScr = val & 0xFF;
                    return;
            }
            return;
        }

        if ((addr & 0xFFFFF000) == DISK_BASE) {
            disk.writeByte(addr - DISK_BASE, val);
            return;
        }

        if (addr == POWER_BASE) {
            haltRequested = true;
            return;
        }

        if (addr == TEST_BASE) {
            int code = val & 0xFF;
            if (code == 0x55 || code == 0x77)
                haltRequested = true;
        }
    }

    private void mmioStoreWord(int addr, int val) {
        long off;

        if (addr >= CLINT_BASE && addr < CLINT_BASE + 0x10000) {
            off = (addr - CLINT_BASE) & 0xFFFFFFFFL;
            if (off == 0x4000L) {
                mtimecmp = (mtimecmp & 0xFFFFFFFF00000000L) | (val & 0xFFFFFFFFL);
                return;
            }
            if (off == 0x4004L) {
                mtimecmp = (mtimecmp & 0x00000000FFFFFFFFL) | (((long) val & 0xFFFFFFFFL) << 32);
                return;
            }
            return;
        }

        if (addr == TEST_BASE) {
            int code = val & 0xFFFF;
            if (code == 0x5555 || code == 0x7777)
                haltRequested = true;
            return;
        }

        mmioStoreByte(addr, (byte) val);
        mmioStoreByte(addr + 1, (byte) (val >> 8));
        mmioStoreByte(addr + 2, (byte) (val >> 16));
        mmioStoreByte(addr + 3, (byte) (val >> 24));
    }

    private boolean inRam(int addr) {
        long uaddr = addr & 0xFFFFFFFFL;
        long base = RAM_BASE & 0xFFFFFFFFL;
        if (uaddr >= base && uaddr < base + ram.length)
            return true;
        if (uaddr < ram.length) {
            if (uaddr >= TEST_BASE && uaddr < TEST_BASE + 4)
                return false;
            return true;
        }
        return false;
    }

    private int ramOffset(int addr) {
        long uaddr = addr & 0xFFFFFFFFL;
        long base = RAM_BASE & 0xFFFFFFFFL;
        if (uaddr >= base && uaddr < base + ram.length)
            return (int) (uaddr - base);
        return (int) uaddr;
    }

    public void loadImage(byte[] image, int baseAddr) {
        int off = ramOffset(baseAddr);
        int len = image.length < ram.length - off ? image.length : ram.length - off;
        System.arraycopy(image, 0, ram, off, len);
    }
}
