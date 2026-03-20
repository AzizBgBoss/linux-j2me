package com.azizbgboss.bblinux;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;
import java.io.IOException;

/**
 * BBLinux MIDlet — RISC-V RV32I emulator hosting uClinux on BB Bold 9700.
 *
 * Boot sequence:
 * 1. Show terminal canvas
 * 2. Load kernel image from microSD (file:///SDCard/bblinux/kernel.bin)
 * 3. Copy image into emulated RAM
 * 4. Set PC to reset vector
 * 5. Run CPU loop in background thread
 *
 * microSD layout expected:
 * /SDCard/bblinux/kernel.bin — flat RV32 binary (no ELF header, raw)
 * /SDCard/bblinux/disk.img — root filesystem image (optional)
 *
 * To get a kernel.bin:
 * Build uClinux or a minimal RV32 baremetal binary on your PC,
 * objcopy -O binary vmlinux kernel.bin
 * Copy to SD card.
 */
public class LinuxMIDlet extends MIDlet implements Runnable {

    private static final String KERNEL_PATH = "file:///SDCard/bblinux/kernel.bin";
    private static final String INITRAMFS_PATH = "file:///SDCard/bblinux/rootfs.cpio";
    private static final int KERNEL_BASE = MemoryBus.RAM_BASE;
    private static final int DTB_PAGE_SIZE = 0x1000;
    private static final boolean ENABLE_STALL_DEBUG = false;

    private ScreenCanvas screen;
    private UARTDevice uart;
    private DiskDevice disk;
    private MemoryBus bus;
    private RV32Core cpu;

    private Thread cpuThread;
    private volatile boolean stopped = false;

    // ── MIDlet lifecycle ──────────────────────────────────────────────────────

    public void startApp() {
        screen = new ScreenCanvas();
        Display.getDisplay(this).setCurrent(screen);

        disk = new DiskDevice();
        uart = new UARTDevice(screen);
        screen.setUART(uart);

        bus = new MemoryBus(uart, disk);
        cpu = new RV32Core(bus);
        screen.putString("Emulated RAM: " + (bus.getRamSize() / (1024 * 1024)) + " MB\r\n");

        // Boot in background so UI stays responsive
        cpuThread = new Thread(this);
        cpuThread.start();
    }

    public void pauseApp() {
        // CPU keeps running — MIDlet pause doesn't stop it
    }

    public void destroyApp(boolean unconditional) {
        stopped = true;
        disk.flush();
    }

    // ── Boot + CPU loop ───────────────────────────────────────────────────────

    public void run() {
        byte[] initramfs = null;
        byte[] ram;
        int copyLen;
        int dtbAddr = 0;
        int initrdAddr = 0;
        int lastTxCount = 0;
        int lastRxPushCount = 0;
        int quietStartCycle = 0;
        int quietSnapshotCount = 0;
        int nextQuietSnapshotCycle = 5000000;
        boolean interactiveMode = false;
        boolean stallDebugEnabled = ENABLE_STALL_DEBUG;
        final int QUIET_SNAPSHOT_FIRST = 5000000;
        final int QUIET_SNAPSHOT_REPEAT = 50000000;
        final int QUIET_SNAPSHOT_MAX = 3;

        screen.putString("BBLinux v0.1 - RISC-V RV32I emulator\r\n");
        screen.putString("(c) AzizBgBoss\r\n\r\n");
        screen.putString("Loading kernel from SD card...\r\n");

        // Load kernel
        byte[] kernel;
        try {
            kernel = disk.loadKernelImage(KERNEL_PATH);
            screen.putString("Kernel loaded: " + kernel.length + " bytes\r\n");
        } catch (IOException e) {
            screen.putString("ERROR: kernel.bin: " + e.getMessage() + "\r\n");
            return;
        }

        ram = bus.getRam();
        {
            byte[] dtb;
            int dtbOffset;
            int topOffset;
            int initrdOffset = -1;
            long initrdEnd = 0L;

            try {
                initramfs = disk.loadImage(INITRAMFS_PATH);
                screen.putString("Initramfs: " + initramfs.length + " bytes\r\n");
            } catch (IOException e) {
                screen.putString("Initramfs: none\r\n");
            }

            topOffset = (ram.length - DTB_PAGE_SIZE) & ~0x0FFF;
            if (topOffset < 0) {
                screen.putString("ERROR: no room for DTB\r\n");
                return;
            }

            if (initramfs != null) {
                initrdOffset = (topOffset - initramfs.length) & ~0x0FFF;
                if (initrdOffset <= 0 || initrdOffset < kernel.length) {
                    screen.putString("WARNING: initramfs does not fit, skipping\r\n");
                    initramfs = null;
                } else {
                    System.arraycopy(initramfs, 0, ram, initrdOffset, initramfs.length);
                    initrdAddr = KERNEL_BASE + initrdOffset;
                    initrdEnd = initrdAddr + initramfs.length;
                }
            }

            dtb = DtbGenerator.createMinimalTree(ram.length,
                                                 initrdAddr & 0xFFFFFFFFL,
                                                 initrdEnd & 0xFFFFFFFFL);
            screen.putString("Built-in DTB: " + dtb.length + " bytes\r\n");

            dtbOffset = topOffset;
            if (dtb.length > DTB_PAGE_SIZE) {
                screen.putString("WARNING: DTB larger than reserved page\r\n");
            }
            if (dtbOffset >= 0 && dtbOffset + dtb.length <= ram.length) {
                System.arraycopy(dtb, 0, ram, dtbOffset, dtb.length);
                dtbAddr = KERNEL_BASE + dtbOffset;
            } else {
                screen.putString("WARNING: DTB too large for RAM\r\n");
            }
        }

        copyLen = Math.min(kernel.length, ram.length);
        System.arraycopy(kernel, 0, ram, 0, copyLen);

        bus.setResetVector(KERNEL_BASE);
        cpu.reset();
        cpu.regs[10] = 0; // a0 = hartid
        cpu.regs[11] = dtbAddr; // a1 = DTB address
        screen.putString("Boot @ 0x" + hex(KERNEL_BASE) + "\r\n");
        if (dtbAddr != 0)
            screen.putString("DTB  @ 0x" + hex(dtbAddr) + "\r\n");
        if (initrdAddr != 0)
            screen.putString("Initrd @ 0x" + hex(initrdAddr) + "\r\n");

        screen.putString("Starting CPU...\r\n\r\n");

        // ── CPU execution loop ─────────────────────────────────────────────
        // Run in bursts with yields to keep UI alive
        final int BURST_BOOT = 200000;
        final int BURST_INTERACTIVE = 5000;

        while (!stopped && cpu.running && !bus.haltRequested) {
            int burst = interactiveMode ? BURST_INTERACTIVE : BURST_BOOT;
            for (int i = 0; i < burst; i++) {
                if (!cpu.step())
                    break;
                if (bus.haltRequested)
                    break;
            }

            {
                int txCount = uart.getTxCount();
                int rxPushCount = uart.getRxPushCount();
                if (stallDebugEnabled && cpu.hasEnteredUserMode()) {
                    stallDebugEnabled = false;
                    quietSnapshotCount = QUIET_SNAPSHOT_MAX;
                }
                if (rxPushCount != lastRxPushCount) {
                    lastRxPushCount = rxPushCount;
                    interactiveMode = true;
                    stallDebugEnabled = false;
                    quietSnapshotCount = QUIET_SNAPSHOT_MAX;
                }
                if (txCount != lastTxCount) {
                    lastTxCount = txCount;
                    quietStartCycle = cpu.cycleCount;
                    if (stallDebugEnabled && !interactiveMode) {
                        quietSnapshotCount = 0;
                        nextQuietSnapshotCycle = cpu.cycleCount + QUIET_SNAPSHOT_FIRST;
                    }
                } else if (stallDebugEnabled &&
                           !interactiveMode &&
                           quietSnapshotCount < QUIET_SNAPSHOT_MAX &&
                           cpu.cycleCount >= nextQuietSnapshotCycle) {
                    screen.putString("\r\n[stall " + (quietSnapshotCount + 1) +
                                     "] silent=" + (cpu.cycleCount - quietStartCycle) +
                                     " cyc\r\n");
                    screen.putString("[state] " + cpu.getQuietStateSummary() + "\r\n");
                    screen.putString("[hot] " + cpu.getQuietHotSummary() + "\r\n");
                    screen.putString("[ni] " + cpu.getNiSyscallSummary() + "\r\n");
                    screen.putString("[trace] " + cpu.getRecentPcTraceSummary() + "\r\n");
                    quietSnapshotCount++;
                    nextQuietSnapshotCycle = cpu.cycleCount + QUIET_SNAPSHOT_REPEAT;
                }
            }

            try { Thread.sleep(interactiveMode ? 5 : 1); } catch (InterruptedException e) {}
        }

        if (bus.haltRequested) {
            screen.putString("\r\n\r\n[System halted]\r\n");
        } else if (!cpu.running) {
            screen.putString("\r\n\r\n[CPU halted - illegal instruction or EBREAK]\r\n");
        }

        bus.flushSyscallLog();
        screen.putString(cpu.getDebugSummary() + "\r\n");
        screen.putString("Cycles executed: " + cpu.cycleCount + "\r\n");
        disk.flush();
    }

    private String hex(int val) {
        String s = Integer.toHexString(val);
        while (s.length() < 8)
            s = "0" + s;
        return s;
    }
}
