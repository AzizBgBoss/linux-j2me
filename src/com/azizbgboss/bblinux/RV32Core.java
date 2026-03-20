package com.azizbgboss.bblinux;

public class RV32Core {
    public int[] regs = new int[32];
    public int pc;

    private static final int CSR_MSTATUS = 0x300;
    private static final int CSR_MISA = 0x301;
    private static final int CSR_MIE = 0x304;
    private static final int CSR_MTVEC = 0x305;
    private static final int CSR_MSCRATCH = 0x340;
    private static final int CSR_MEPC = 0x341;
    private static final int CSR_MCAUSE = 0x342;
    private static final int CSR_MTVAL = 0x343;
    private static final int CSR_MIP = 0x344;
    private static final int CSR_MHARTID = 0xF14;
    private static final int CSR_CYCLE = 0xC00;
    private static final int CSR_TIME = 0xC01;
    private static final int CSR_INSTRET = 0xC02;
    private static final int CSR_CYCLEH = 0xC80;
    private static final int CSR_TIMEH = 0xC81;
    private static final int CSR_INSTRETH = 0xC82;
    private static final int CSR_MVENDORID = 0xF11;
    private static final int CSR_MARCHID = 0xF12;
    private static final int CSR_MIMPID = 0xF13;

    private static final int MSTATUS_MIE = 0x00000008;
    private static final int MSTATUS_MPIE = 0x00000080;
    private static final int MSTATUS_MPP_SHIFT = 11;
    private static final int MSTATUS_MPP_MASK = 0x00001800;
    private static final int MIP_MTIP = 0x00000080;
    private static final int WATCH_MUTEX_LR_PC = 0x800638C4;
    private static final int WATCH_MUTEX_SC_PC = 0x800638CC;
    private static final int MUTEX_REGION_START = 0x800635E0;
    private static final int MUTEX_REGION_END = 0x80063920;
    private static final int MUTEX_REGION_SLOTS = (MUTEX_REGION_END - MUTEX_REGION_START) >> 2;
    private static final int HOT_PC_SLOTS = 8;
    private static final int HOT_PC_SAMPLE_MASK = 0x3FF;
    private static final int TRACE_SLOTS = 32;
    private static final int NI_SYSCALL_START = 0x8003A674;
    private static final int NI_SYSCALL_END = 0x8003A9B0;
    private static final int MTIME_INSTR_DIVISOR = 128;

    private int mstatus = 0, misa = 0x40001101, mie = 0, mtvec = 0, mscratch = 0;
    private int mepc = 0, mcause = 0, mtval = 0, mip = 0;
    private int priv = 3;
    private boolean enteredUserMode = false;

    private byte[] ram;
    private MemoryBus bus;
    public boolean running = false;
    public int cycleCount = 0;

    public int trapCount = 0;
    public int lastTrapCause = 0;
    public int lastTrapPc = 0;
    public int lastTrapTval = 0;
    public int lastInstr = 0;
    public int repeatedTrapCount = 0;
    public int mtipCount = 0;
    public int wfiCount = 0;
    public int samePcCount = 0;
    public int lrCount = 0;
    public int scCount = 0;
    public int scFailCount = 0;
    public int amoCount = 0;
    public int scAssistCount = 0;
    public int scChangedCount = 0;
    public String stopReason = "";

    private int reservation = -1;
    private int lrAddress = -1;
    private int lrValue = 0;
    private int lastLrPc = 0;
    private int lastScAddress = -1;
    private int lastScObserved = 0;
    private int lastScExpected = 0;
    private int lastScStoreValue = 0;
    private int lastScPc = 0;
    private int lastScFlags = 0;
    private int watchLockAddr = -1;
    private int watchLrCount = 0;
    private int watchScCount = 0;
    private int watchScFailCount = 0;
    private int watchWriteCount = 0;
    private int watchLastLrValue = 0;
    private int watchLastScObserved = 0;
    private int watchLastScStoreValue = 0;
    private int watchLastScResult = 0;
    private int watchLastScFlags = 0;
    private int watchLastWriteAddr = -1;
    private int watchLastWriteSize = 0;
    private int watchLastWriteOld = 0;
    private int watchLastWriteNew = 0;
    private int watchLastWriterPc = 0;
    private int mutexRegionVisits = 0;
    private int mutexRegionLastPc = 0;
    private int mutexRegionLastLockAddr = -1;
    private int mutexRegionLastLockWord = 0;
    private int mutexRegionLastA1 = 0;
    private int mutexRegionLastA2 = 0;
    private int[] mutexRegionHits = new int[MUTEX_REGION_SLOTS];
    private int[] hotPcAddr = new int[HOT_PC_SLOTS];
    private int[] hotPcHits = new int[HOT_PC_SLOTS];
    private int hotLastPc = 0;
    private int hotLastA0 = 0;
    private int hotLastA0Word = 0;
    private int niSyscallHits = 0;
    private int niLastPc = 0;
    private int niLastNr = 0;
    private int niLastA0 = 0;
    private int niLastA1 = 0;
    private int niLastA2 = 0;
    private int[] recentPcTrace = new int[TRACE_SLOTS];
    private int recentPcHead = 0;
    private int recentPcCount = 0;
    private int machineTimeRemainder = 0;
    private boolean waitingForInterrupt = false;
    private int observedPc = 0xFFFFFFFF;

    private static final int OP_LUI = 0x37;
    private static final int OP_AUIPC = 0x17;
    private static final int OP_JAL = 0x6F;
    private static final int OP_JALR = 0x67;
    private static final int OP_BRANCH = 0x63;
    private static final int OP_LOAD = 0x03;
    private static final int OP_STORE = 0x23;
    private static final int OP_ALUI = 0x13;
    private static final int OP_ALU = 0x33;
    private static final int OP_FENCE = 0x0F;
    private static final int OP_SYSTEM = 0x73;
    private static final int OP_AMO = 0x2F;

    public RV32Core(MemoryBus bus) {
        this.bus = bus;
        this.ram = bus.ram;
        reset();
    }

    public void reset() {
        int i;
        for (i = 0; i < 32; i++)
            regs[i] = 0;
        pc = bus.resetVector();
        running = true;
        cycleCount = 0;
        mstatus = 0;
        mtvec = 0;
        mscratch = 0;
        mepc = 0;
        mcause = 0;
        mtval = 0;
        mip = 0;
        priv = 3;
        enteredUserMode = false;
        reservation = -1;
        trapCount = 0;
        lastTrapCause = 0;
        lastTrapPc = 0;
        lastTrapTval = 0;
        lastInstr = 0;
        repeatedTrapCount = 0;
        mtipCount = 0;
        wfiCount = 0;
        samePcCount = 0;
        lrCount = 0;
        scCount = 0;
        scFailCount = 0;
        amoCount = 0;
        scAssistCount = 0;
        scChangedCount = 0;
        stopReason = "";
        waitingForInterrupt = false;
        observedPc = 0xFFFFFFFF;
        lrAddress = -1;
        lrValue = 0;
        lastLrPc = 0;
        lastScAddress = -1;
        lastScObserved = 0;
        lastScExpected = 0;
        lastScStoreValue = 0;
        lastScPc = 0;
        lastScFlags = 0;
        watchLockAddr = -1;
        watchLrCount = 0;
        watchScCount = 0;
        watchScFailCount = 0;
        watchWriteCount = 0;
        watchLastLrValue = 0;
        watchLastScObserved = 0;
        watchLastScStoreValue = 0;
        watchLastScResult = 0;
        watchLastScFlags = 0;
        watchLastWriteAddr = -1;
        watchLastWriteSize = 0;
        watchLastWriteOld = 0;
        watchLastWriteNew = 0;
        watchLastWriterPc = 0;
        mutexRegionVisits = 0;
        mutexRegionLastPc = 0;
        mutexRegionLastLockAddr = -1;
        mutexRegionLastLockWord = 0;
        mutexRegionLastA1 = 0;
        mutexRegionLastA2 = 0;
        for (i = 0; i < MUTEX_REGION_SLOTS; i++)
            mutexRegionHits[i] = 0;
        for (i = 0; i < HOT_PC_SLOTS; i++) {
            hotPcAddr[i] = 0;
            hotPcHits[i] = 0;
        }
        hotLastPc = 0;
        hotLastA0 = 0;
        hotLastA0Word = 0;
        niSyscallHits = 0;
        niLastPc = 0;
        niLastNr = 0;
        niLastA0 = 0;
        niLastA1 = 0;
        niLastA2 = 0;
        for (i = 0; i < TRACE_SLOTS; i++)
            recentPcTrace[i] = 0;
        recentPcHead = 0;
        recentPcCount = 0;
        machineTimeRemainder = 0;
        bus.setMachineTime(0L);
    }

    private int csrRead(int csr) {
        switch (csr) {
            case CSR_MSTATUS:
                return mstatus;
            case CSR_MISA:
                return misa;
            case CSR_MIE:
                return mie;
            case CSR_MTVEC:
                return mtvec;
            case CSR_MSCRATCH:
                return mscratch;
            case CSR_MEPC:
                return mepc;
            case CSR_MCAUSE:
                return mcause;
            case CSR_MTVAL:
                return mtval;
            case CSR_MIP:
                return mip;
            case CSR_MHARTID:
                return 0;
            case CSR_CYCLE:
            case CSR_INSTRET:
                return cycleCount;
            case CSR_TIME:
                return (int) (bus.getMachineTime() & 0xFFFFFFFFL);
            case CSR_CYCLEH:
            case CSR_INSTRETH:
                return 0;
            case CSR_TIMEH:
                return (int) ((bus.getMachineTime() >>> 32) & 0xFFFFFFFFL);
            case CSR_MVENDORID:
            case CSR_MARCHID:
            case CSR_MIMPID:
                return 0;
            case 0x3A0:
                return 0;
            case 0x3B0:
                return 0;
            default:
                return 0;
        }
    }

    private void csrWrite(int csr, int val) {
        switch (csr) {
            case CSR_MSTATUS:
                mstatus = val;
                break;
            case CSR_MIE:
                mie = val;
                break;
            case CSR_MTVEC:
                mtvec = val;
                break;
            case CSR_MSCRATCH:
                mscratch = val;
                break;
            case CSR_MEPC:
                mepc = val;
                break;
            case CSR_MCAUSE:
                mcause = val;
                break;
            case CSR_MTVAL:
                mtval = val;
                break;
            case CSR_MIP:
                mip = val;
                break;
            case 0x3A0:
            case 0x3A1:
            case 0x3B0:
            case 0x3B1:
            case 0x3B2:
            case 0x3B3:
                break;
        }
    }

    private int trap(int cause, int tval, int trapPc) {
        int target;
        int oldMie;
        int newMstatus;

        mepc = trapPc;
        mcause = cause;
        mtval = tval;

        oldMie = mstatus & MSTATUS_MIE;
        newMstatus = mstatus & ~(MSTATUS_MIE | MSTATUS_MPIE | MSTATUS_MPP_MASK);
        newMstatus |= (oldMie << 4);
        newMstatus |= (priv & 0x3) << MSTATUS_MPP_SHIFT;
        mstatus = newMstatus;
        priv = 3;

        trapCount++;
        if (lastTrapCause == cause && lastTrapPc == trapPc && lastTrapTval == tval) {
            repeatedTrapCount++;
        } else {
            repeatedTrapCount = 1;
        }
        lastTrapCause = cause;
        lastTrapPc = trapPc;
        lastTrapTval = tval;

        target = mtvec & ~0x3;
        if ((mtvec & 0x3) != 0 && (cause & 0x80000000) != 0) {
            target += (cause & 0x7FFFFFFF) * 4;
        }
        return target;
    }

    private void updateInterrupts() {
        if (bus.isMachineTimerPending()) {
            mip |= MIP_MTIP;
        } else {
            mip &= ~MIP_MTIP;
        }
    }

    private boolean serviceInterrupt() {
        int pending;

        updateInterrupts();
        if (priv == 3 && (mstatus & MSTATUS_MIE) == 0)
            return false;

        pending = mip & mie;
        if ((pending & MIP_MTIP) != 0) {
            mtipCount++;
            waitingForInterrupt = false;
            pc = trap(0x80000007, 0, pc);
            regs[0] = 0;
            return true;
        }
        return false;
    }

    private void advanceTimeForWfi() {
        long syntheticInstrs;
        long delta = bus.timeUntilMachineTimer();
        long now = bus.getMachineTime();

        if (delta == Long.MAX_VALUE) {
            advanceMachineTimeByInstructions(MTIME_INSTR_DIVISOR);
            safeAddCycles(MTIME_INSTR_DIVISOR);
            return;
        }

        if (delta <= 0L) {
            bus.setMachineTime(now);
            return;
        }

        bus.setMachineTime(now + delta);
        machineTimeRemainder = 0;
        syntheticInstrs = delta * MTIME_INSTR_DIVISOR;
        safeAddCycles(syntheticInstrs);
    }

    private void advanceMachineTimeByInstructions(int retiredInstrs) {
        long total = machineTimeRemainder + retiredInstrs;
        long delta = total / MTIME_INSTR_DIVISOR;

        machineTimeRemainder = (int) (total % MTIME_INSTR_DIVISOR);
        if (delta != 0L)
            bus.advanceMachineTime(delta);
    }

    private void safeAddCycles(long delta) {
        long total = (long) cycleCount + delta;

        if (total > 0x7FFFFFFFL)
            cycleCount = 0x7FFFFFFF;
        else
            cycleCount = (int) total;
    }

    private int wordAddress(int addr) {
        return addr & ~3;
    }

    private void invalidateReservation(int addr, int size) {
        long start;
        long end;
        long resStart;
        long resEnd;

        if (reservation < 0)
            return;

        start = u32(addr);
        end = start + size;
        resStart = u32(reservation);
        resEnd = resStart + 4;

        if (start < resEnd && end > resStart)
            reservation = -1;
    }

    private boolean overlapsWatch(int addr, int size) {
        long start;
        long end;
        long watchStart;
        long watchEnd;

        if (watchLockAddr < 0)
            return false;

        start = u32(addr);
        end = start + size;
        watchStart = u32(watchLockAddr);
        watchEnd = watchStart + 4;
        return start < watchEnd && end > watchStart;
    }

    private int readWatchedWordBefore(int addr, int size) {
        if (!overlapsWatch(addr, size))
            return 0;
        return bus.loadWord(watchLockAddr);
    }

    private void recordWatchedWrite(int addr, int size, int oldWatchWord) {
        if (!overlapsWatch(addr, size))
            return;

        watchWriteCount++;
        watchLastWriteAddr = addr;
        watchLastWriteSize = size;
        watchLastWriteOld = oldWatchWord;
        watchLastWriteNew = bus.loadWord(watchLockAddr);
        watchLastWriterPc = pc;
    }

    private void storeByteTracked(int addr, int value) {
        int oldWatchWord = readWatchedWordBefore(addr, 1);
        bus.storeByte(addr, (byte) value);
        invalidateReservation(addr, 1);
        recordWatchedWrite(addr, 1, oldWatchWord);
    }

    private void storeHalfTracked(int addr, int value) {
        int oldWatchWord = readWatchedWordBefore(addr, 2);
        bus.storeHalf(addr, (short) value);
        invalidateReservation(addr, 2);
        recordWatchedWrite(addr, 2, oldWatchWord);
    }

    private void storeWordTracked(int addr, int value, boolean invalidate) {
        int oldWatchWord = readWatchedWordBefore(addr, 4);
        bus.storeWord(addr, value);
        if (invalidate)
            invalidateReservation(addr, 4);
        recordWatchedWrite(addr, 4, oldWatchWord);
    }

    private void activateWatch(int addrWord) {
        if (watchLockAddr == addrWord)
            return;

        watchLockAddr = addrWord;
        watchLrCount = 0;
        watchScCount = 0;
        watchScFailCount = 0;
        watchWriteCount = 0;
        watchLastLrValue = 0;
        watchLastScObserved = 0;
        watchLastScStoreValue = 0;
        watchLastScResult = 0;
        watchLastScFlags = 0;
        watchLastWriteAddr = -1;
        watchLastWriteSize = 0;
        watchLastWriteOld = 0;
        watchLastWriteNew = 0;
        watchLastWriterPc = 0;
    }

    private void noteWatchLr(int addr, int old) {
        if (pc != WATCH_MUTEX_LR_PC)
            return;

        activateWatch(wordAddress(addr));
        watchLrCount++;
        watchLastLrValue = old;
    }

    private void noteWatchSc(int addr, int observed, int newValue, int result, int flags) {
        int addrWord = wordAddress(addr);

        if (pc != WATCH_MUTEX_SC_PC && watchLockAddr != addrWord)
            return;

        activateWatch(addrWord);
        watchScCount++;
        if (result != 0)
            watchScFailCount++;
        watchLastScObserved = observed;
        watchLastScStoreValue = newValue;
        watchLastScResult = result;
        watchLastScFlags = flags;
    }

    private boolean isMutexRegionPc(int addr) {
        return addr >= MUTEX_REGION_START && addr < MUTEX_REGION_END;
    }

    private void trackMutexRegion() {
        int idx;

        if (!isMutexRegionPc(pc))
            return;

        mutexRegionVisits++;
        mutexRegionLastPc = pc;
        mutexRegionLastLockAddr = regs[10];
        mutexRegionLastA1 = regs[11];
        mutexRegionLastA2 = regs[12];
        if (mutexRegionLastLockAddr != 0) {
            try {
                mutexRegionLastLockWord = bus.loadWord(mutexRegionLastLockAddr);
            } catch (Exception e) {
                mutexRegionLastLockWord = 0;
            }
        } else {
            mutexRegionLastLockWord = 0;
        }

        idx = (pc - MUTEX_REGION_START) >> 2;
        if (idx >= 0 && idx < MUTEX_REGION_SLOTS)
            mutexRegionHits[idx]++;
    }

    private void trackHotPc() {
        int i;

        if ((cycleCount & HOT_PC_SAMPLE_MASK) != 0)
            return;

        hotLastPc = pc;
        hotLastA0 = regs[10];
        if (hotLastA0 != 0) {
            try {
                hotLastA0Word = bus.loadWord(hotLastA0);
            } catch (Exception e) {
                hotLastA0Word = 0;
            }
        } else {
            hotLastA0Word = 0;
        }

        for (i = 0; i < HOT_PC_SLOTS; i++) {
            if (hotPcHits[i] > 0 && hotPcAddr[i] == pc) {
                hotPcHits[i]++;
                return;
            }
        }

        for (i = 0; i < HOT_PC_SLOTS; i++) {
            if (hotPcHits[i] == 0) {
                hotPcAddr[i] = pc;
                hotPcHits[i] = 1;
                return;
            }
        }

        for (i = 0; i < HOT_PC_SLOTS; i++)
            hotPcHits[i]--;
    }

    private void recordRecentPc() {
        int prevIndex;

        if (recentPcCount > 0) {
            prevIndex = recentPcHead - 1;
            if (prevIndex < 0)
                prevIndex = TRACE_SLOTS - 1;
            if (recentPcTrace[prevIndex] == pc)
                return;
        }

        recentPcTrace[recentPcHead] = pc;
        recentPcHead++;
        if (recentPcHead >= TRACE_SLOTS)
            recentPcHead = 0;
        if (recentPcCount < TRACE_SLOTS)
            recentPcCount++;
    }

    private boolean isNiSyscallPc(int addr) {
        return addr >= NI_SYSCALL_START && addr < NI_SYSCALL_END;
    }

    private void trackNiSyscall() {
        if (!isNiSyscallPc(pc))
            return;

        niSyscallHits++;
        niLastPc = pc;
        niLastNr = regs[17];
        niLastA0 = regs[10];
        niLastA1 = regs[11];
        niLastA2 = regs[12];
    }

    public boolean step() {
        int instr;
        int opcode;
        int nextPc;

        if (!running)
            return false;

        if (pc == observedPc) {
            samePcCount++;
        } else {
            observedPc = pc;
            samePcCount = 1;
        }

        recordRecentPc();
        trackNiSyscall();
        trackHotPc();
        trackMutexRegion();

        if (waitingForInterrupt) {
            advanceTimeForWfi();
            if (serviceInterrupt())
                return running;
            waitingForInterrupt = false;
        }

        cycleCount++;
        advanceMachineTimeByInstructions(1);

        if (serviceInterrupt())
            return running;

        if (bus.isRamAddress(pc)) {
            int a = bus.ramOffsetOf(pc);
            if (a >= 0 && a <= ram.length - 4) {
                instr = (ram[a] & 0xFF) |
                        ((ram[a + 1] & 0xFF) << 8) |
                        ((ram[a + 2] & 0xFF) << 16) |
                        ((ram[a + 3] & 0xFF) << 24);
            } else {
                instr = bus.loadWord(pc);
            }
        } else {
            instr = bus.loadWord(pc);
        }

        lastInstr = instr;
        opcode = instr & 0x7F;
        nextPc = pc + 4;

        switch (opcode) {
            case OP_LUI: {
                int rd = (instr >> 7) & 0x1F;
                setReg(rd, instr & 0xFFFFF000);
                break;
            }
            case OP_AUIPC: {
                int rd = (instr >> 7) & 0x1F;
                setReg(rd, pc + (instr & 0xFFFFF000));
                break;
            }
            case OP_JAL: {
                int rd = (instr >> 7) & 0x1F;
                int imm = decodeJImm(instr);
                setReg(rd, nextPc);
                nextPc = pc + imm;
                break;
            }
            case OP_JALR: {
                int rd = (instr >> 7) & 0x1F;
                int rs1 = (instr >> 15) & 0x1F;
                int imm = instr >> 20;
                int t = (regs[rs1] + imm) & ~1;
                setReg(rd, nextPc);
                nextPc = t;
                break;
            }
            case OP_BRANCH: {
                int rs1 = (instr >> 15) & 0x1F;
                int rs2 = (instr >> 20) & 0x1F;
                int fn3 = (instr >> 12) & 0x07;
                int imm = decodeBImm(instr);
                boolean t = false;
                switch (fn3) {
                    case 0:
                        t = regs[rs1] == regs[rs2];
                        break;
                    case 1:
                        t = regs[rs1] != regs[rs2];
                        break;
                    case 4:
                        t = regs[rs1] < regs[rs2];
                        break;
                    case 5:
                        t = regs[rs1] >= regs[rs2];
                        break;
                    case 6:
                        t = u32(regs[rs1]) < u32(regs[rs2]);
                        break;
                    case 7:
                        t = u32(regs[rs1]) >= u32(regs[rs2]);
                        break;
                }
                if (t)
                    nextPc = pc + imm;
                break;
            }
            case OP_LOAD: {
                int rd = (instr >> 7) & 0x1F;
                int rs1 = (instr >> 15) & 0x1F;
                int fn3 = (instr >> 12) & 0x07;
                int imm = instr >> 20;
                int addr = regs[rs1] + imm;
                int val = 0;
                boolean writeRd = true;
                switch (fn3) {
                    case 0:
                        val = (byte) bus.loadByte(addr);
                        break;
                    case 1:
                        val = (short) bus.loadHalf(addr);
                        break;
                    case 2:
                        val = bus.loadWord(addr);
                        break;
                    case 4:
                        val = bus.loadByte(addr) & 0xFF;
                        break;
                    case 5:
                        val = bus.loadHalf(addr) & 0xFFFF;
                        break;
                    default:
                        nextPc = trap(2, instr, pc);
                        writeRd = false;
                        break;
                }
                if (writeRd)
                    setReg(rd, val);
                break;
            }
            case OP_STORE: {
                int rs1 = (instr >> 15) & 0x1F;
                int rs2 = (instr >> 20) & 0x1F;
                int fn3 = (instr >> 12) & 0x07;
                int imm = decodeSImm(instr);
                int addr = regs[rs1] + imm;
                switch (fn3) {
                    case 0:
                        storeByteTracked(addr, regs[rs2]);
                        break;
                    case 1:
                        storeHalfTracked(addr, regs[rs2]);
                        break;
                    case 2:
                        storeWordTracked(addr, regs[rs2], true);
                        break;
                    default:
                        nextPc = trap(2, instr, pc);
                        break;
                }
                break;
            }
            case OP_ALUI: {
                int rd = (instr >> 7) & 0x1F;
                int rs1 = (instr >> 15) & 0x1F;
                int fn3 = (instr >> 12) & 0x07;
                int imm = instr >> 20;
                int shamt = (instr >> 20) & 0x1F;
                int result = 0;
                switch (fn3) {
                    case 0:
                        result = regs[rs1] + imm;
                        break;
                    case 1:
                        result = regs[rs1] << shamt;
                        break;
                    case 2:
                        result = (regs[rs1] < imm) ? 1 : 0;
                        break;
                    case 3:
                        result = (u32(regs[rs1]) < u32(imm)) ? 1 : 0;
                        break;
                    case 4:
                        result = regs[rs1] ^ imm;
                        break;
                    case 5:
                        result = ((instr & 0x40000000) != 0) ? (regs[rs1] >> shamt) : (regs[rs1] >>> shamt);
                        break;
                    case 6:
                        result = regs[rs1] | imm;
                        break;
                    case 7:
                        result = regs[rs1] & imm;
                        break;
                }
                setReg(rd, result);
                break;
            }
            case OP_ALU: {
                int rd = (instr >> 7) & 0x1F;
                int rs1 = (instr >> 15) & 0x1F;
                int rs2 = (instr >> 20) & 0x1F;
                int fn3 = (instr >> 12) & 0x07;
                int fn7 = (instr >> 25) & 0x7F;
                int result = 0;
                boolean writeRd = true;

                if (fn7 == 0x01) {
                    long lhs;
                    long rhs;
                    switch (fn3) {
                        case 0:
                            result = (int) ((long) regs[rs1] * (long) regs[rs2]);
                            break;
                        case 1:
                            result = (int) ((((long) regs[rs1]) * ((long) regs[rs2])) >> 32);
                            break;
                        case 2:
                            lhs = regs[rs1];
                            rhs = u32(regs[rs2]);
                            result = (int) ((lhs * rhs) >> 32);
                            break;
                        case 3:
                            result = (int) ((u32(regs[rs1]) * u32(regs[rs2])) >>> 32);
                            break;
                        case 4:
                            if (regs[rs2] == 0)
                                result = -1;
                            else if (regs[rs1] == 0x80000000 && regs[rs2] == -1)
                                result = 0x80000000;
                            else
                                result = regs[rs1] / regs[rs2];
                            break;
                        case 5:
                            if (regs[rs2] == 0)
                                result = -1;
                            else
                                result = (int) (u32(regs[rs1]) / u32(regs[rs2]));
                            break;
                        case 6:
                            if (regs[rs2] == 0)
                                result = regs[rs1];
                            else if (regs[rs1] == 0x80000000 && regs[rs2] == -1)
                                result = 0;
                            else
                                result = regs[rs1] % regs[rs2];
                            break;
                        case 7:
                            if (regs[rs2] == 0)
                                result = regs[rs1];
                            else
                                result = (int) (u32(regs[rs1]) % u32(regs[rs2]));
                            break;
                        default:
                            nextPc = trap(2, instr, pc);
                            writeRd = false;
                            break;
                    }
                } else {
                    switch (fn3) {
                        case 0:
                            result = (fn7 == 0x20) ? (regs[rs1] - regs[rs2]) : (regs[rs1] + regs[rs2]);
                            break;
                        case 1:
                            result = regs[rs1] << (regs[rs2] & 0x1F);
                            break;
                        case 2:
                            result = (regs[rs1] < regs[rs2]) ? 1 : 0;
                            break;
                        case 3:
                            result = (u32(regs[rs1]) < u32(regs[rs2])) ? 1 : 0;
                            break;
                        case 4:
                            result = regs[rs1] ^ regs[rs2];
                            break;
                        case 5:
                            result = (fn7 == 0x20) ? (regs[rs1] >> (regs[rs2] & 0x1F)) : (regs[rs1] >>> (regs[rs2] & 0x1F));
                            break;
                        case 6:
                            result = regs[rs1] | regs[rs2];
                            break;
                        case 7:
                            result = regs[rs1] & regs[rs2];
                            break;
                    }
                }
                if (writeRd)
                    setReg(rd, result);
                break;
            }
            case OP_AMO: {
                int rd = (instr >> 7) & 0x1F;
                int rs1 = (instr >> 15) & 0x1F;
                int rs2 = (instr >> 20) & 0x1F;
                int fn3 = (instr >> 12) & 0x07;
                int fn5 = (instr >> 27) & 0x1F;
                int addr = regs[rs1];
                int old = bus.loadWord(addr);
                int nw = old;
                int scResult = 1;

                if (fn3 != 2) {
                    nextPc = trap(2, instr, pc);
                    break;
                }

                amoCount++;
                switch (fn5) {
                    case 0x02:
                        lrCount++;
                        reservation = wordAddress(addr);
                        lrAddress = reservation;
                        lrValue = old;
                        lastLrPc = pc;
                        noteWatchLr(addr, old);
                        setReg(rd, old);
                        break;
                    case 0x03:
                        int addrWord = wordAddress(addr);
                        boolean reservationMatch;
                        boolean assistedSuccess;
                        boolean sameTrackedWord;
                        boolean valueStillMatches;

                        scCount++;
                        lastScAddress = addrWord;
                        lastScObserved = old;
                        lastScExpected = lrValue;
                        lastScStoreValue = regs[rs2];
                        lastScPc = pc;
                        reservationMatch = (reservation == addrWord);
                        sameTrackedWord = (lrAddress == addrWord);
                        valueStillMatches = sameTrackedWord && old == lrValue;
                        assistedSuccess = !reservationMatch &&
                                          valueStillMatches;
                        lastScFlags = (reservationMatch ? 1 : 0) |
                                      (sameTrackedWord ? 2 : 0) |
                                      (valueStillMatches ? 4 : 0) |
                                      (assistedSuccess ? 8 : 0);
                        if (reservationMatch || assistedSuccess) {
                            storeWordTracked(addr, regs[rs2], false);
                            if (assistedSuccess)
                                scAssistCount++;
                            reservation = -1;
                            lrAddress = -1;
                            scResult = 0;
                        } else {
                            scFailCount++;
                            if (sameTrackedWord && !valueStillMatches)
                                scChangedCount++;
                            reservation = -1;
                            lrAddress = -1;
                        }
                        noteWatchSc(addr, old, regs[rs2], scResult, lastScFlags);
                        setReg(rd, scResult);
                        break;
                    case 0x00:
                        nw = old + regs[rs2];
                        storeWordTracked(addr, nw, false);
                        reservation = -1;
                        setReg(rd, old);
                        break;
                    case 0x01:
                        nw = regs[rs2];
                        storeWordTracked(addr, nw, false);
                        reservation = -1;
                        setReg(rd, old);
                        break;
                    case 0x04:
                        nw = old ^ regs[rs2];
                        storeWordTracked(addr, nw, false);
                        reservation = -1;
                        setReg(rd, old);
                        break;
                    case 0x08:
                        nw = old | regs[rs2];
                        storeWordTracked(addr, nw, false);
                        reservation = -1;
                        setReg(rd, old);
                        break;
                    case 0x0C:
                        nw = old & regs[rs2];
                        storeWordTracked(addr, nw, false);
                        reservation = -1;
                        setReg(rd, old);
                        break;
                    case 0x10:
                        nw = (old < regs[rs2]) ? old : regs[rs2];
                        storeWordTracked(addr, nw, false);
                        reservation = -1;
                        setReg(rd, old);
                        break;
                    case 0x14:
                        nw = (old > regs[rs2]) ? old : regs[rs2];
                        storeWordTracked(addr, nw, false);
                        reservation = -1;
                        setReg(rd, old);
                        break;
                    case 0x18:
                        nw = (u32(old) < u32(regs[rs2])) ? old : regs[rs2];
                        storeWordTracked(addr, nw, false);
                        reservation = -1;
                        setReg(rd, old);
                        break;
                    case 0x1C:
                        nw = (u32(old) > u32(regs[rs2])) ? old : regs[rs2];
                        storeWordTracked(addr, nw, false);
                        reservation = -1;
                        setReg(rd, old);
                        break;
                    default:
                        nextPc = trap(2, instr, pc);
                        break;
                }
                break;
            }
            case OP_FENCE:
                break;
            case OP_SYSTEM: {
                int fn3 = (instr >> 12) & 0x07;
                int fn12 = (instr >> 20) & 0xFFF;
                int rd = (instr >> 7) & 0x1F;
                int rs1 = (instr >> 15) & 0x1F;
                int csr = (instr >> 20) & 0xFFF;
                if (fn3 == 0) {
                    switch (fn12) {
                        case 0x000:
                            nextPc = trap((priv == 0) ? 8 : ((priv == 1) ? 9 : 11), 0, pc);
                            break;
                        case 0x001:
                            nextPc = trap(3, 0, pc);
                            break;
                        case 0x302: {
                            int nextPriv;
                            int mpie = (mstatus & MSTATUS_MPIE) >> 4;
                            if (priv != 3) {
                                nextPc = trap(2, instr, pc);
                                break;
                            }
                            nextPriv = (mstatus & MSTATUS_MPP_MASK) >> MSTATUS_MPP_SHIFT;
                            nextPc = mepc;
                            mstatus = (mstatus & ~(MSTATUS_MIE | MSTATUS_MPIE | MSTATUS_MPP_MASK)) |
                                      mpie | MSTATUS_MPIE;
                            priv = nextPriv;
                            if (priv == 0)
                                enteredUserMode = true;
                            waitingForInterrupt = false;
                            break;
                        }
                        case 0x105:
                            if (priv != 3) {
                                nextPc = trap(2, instr, pc);
                                break;
                            }
                            wfiCount++;
                            if ((mip & mie) == 0) {
                                waitingForInterrupt = true;
                            }
                            break;
                        default:
                            nextPc = trap(2, instr, pc);
                            break;
                    }
                } else {
                    int old = csrRead(csr);
                    int nw = 0;
                    if (priv != 3) {
                        nextPc = trap(2, instr, pc);
                        break;
                    }
                    switch (fn3) {
                        case 1:
                            nw = regs[rs1];
                            setReg(rd, old);
                            csrWrite(csr, nw);
                            break;
                        case 2:
                            nw = old | regs[rs1];
                            setReg(rd, old);
                            if (rs1 != 0)
                                csrWrite(csr, nw);
                            break;
                        case 3:
                            nw = old & ~regs[rs1];
                            setReg(rd, old);
                            if (rs1 != 0)
                                csrWrite(csr, nw);
                            break;
                        case 5:
                            nw = rs1;
                            setReg(rd, old);
                            csrWrite(csr, nw);
                            break;
                        case 6:
                            nw = old | rs1;
                            setReg(rd, old);
                            if (rs1 != 0)
                                csrWrite(csr, nw);
                            break;
                        case 7:
                            nw = old & ~rs1;
                            setReg(rd, old);
                            if (rs1 != 0)
                                csrWrite(csr, nw);
                            break;
                        default:
                            nextPc = trap(2, instr, pc);
                            break;
                    }
                }
                break;
            }
            default:
                nextPc = trap(2, instr, pc);
                break;
        }

        regs[0] = 0;
        pc = nextPc;
        return running;
    }

    public String getDebugSummary() {
        return "pc=0x" + hex(pc) +
               " instr=0x" + hex(lastInstr) +
               " traps=" + trapCount +
               " mcause=0x" + hex(lastTrapCause) +
               " mepc=0x" + hex(mepc);
    }

    public String getHeartbeatSummary() {
        return "pc=0x" + hex(pc) +
               " instr=0x" + hex(lastInstr) +
               " same=" + samePcCount +
               " mtip=" + mtipCount +
               " wfi=" + wfiCount +
               " lr=" + lrCount +
               " sc=" + scCount +
               " scf=" + scFailCount +
               " scx=" + scAssistCount +
               " scc=" + scChangedCount +
               " amo=" + amoCount +
               " mstatus=0x" + hex(mstatus) +
               " mie=0x" + hex(mie) +
               " mip=0x" + hex(mip) +
               " ttn=" + bus.timeUntilMachineTimer() +
               " cyc=" + cycleCount;
    }

    public String getQuietStateSummary() {
        return "pc=" + shortPc(pc) +
               " i=" + shortHex(lastInstr) +
               " ra=" + shortPc(regs[1]) +
               " sp=" + shortPc(regs[2]) +
               " p=" + priv +
               " same=" + samePcCount +
               " trap=" + trapCount +
               " mtip=" + mtipCount +
               " wfi=" + wfiCount +
               " lr=" + lrCount +
               " sc=" + scCount +
               " sf=" + scFailCount +
               " gt=" + bus.getMachineTime();
    }

    public boolean hasEnteredUserMode() {
        return enteredUserMode;
    }

    public String getAtomicProbeSummary() {
        int lockAddr = regs[10];
        int lockWord = 0;

        try {
            lockWord = bus.loadWord(lockAddr);
        } catch (Exception e) {
            lockWord = 0;
        }

        return "a0=0x" + hex(lockAddr) +
               " mem=0x" + hex(lockWord) +
               " res=0x" + hex(reservation) +
               " lr@=0x" + hex(lrAddress) +
               " lrv=0x" + hex(lrValue) +
               " lpc=0x" + hex(lastLrPc) +
               " sc@=0x" + hex(lastScAddress) +
               " sex=0x" + hex(lastScExpected) +
               " saw=0x" + hex(lastScObserved) +
               " snw=0x" + hex(lastScStoreValue) +
               " spc=0x" + hex(lastScPc) +
               " flg=0x" + hex(lastScFlags) +
               " a2=0x" + hex(regs[12]) +
               " a4=0x" + hex(regs[14]) +
               " a5=0x" + hex(regs[15]);
    }

    public String getLockWatchSummary() {
        int mem = 0;

        if (watchLockAddr >= 0) {
            try {
                mem = bus.loadWord(watchLockAddr);
            } catch (Exception e) {
                mem = 0;
            }
        }

        return "addr=0x" + hex(watchLockAddr) +
               " mem=0x" + hex(mem) +
               " lr#=" + watchLrCount +
               " sc#=" + watchScCount +
               " sf#=" + watchScFailCount +
               " wr#=" + watchWriteCount +
               " lrv=0x" + hex(watchLastLrValue) +
               " sov=0x" + hex(watchLastScObserved) +
               " snw=0x" + hex(watchLastScStoreValue) +
               " srs=" + watchLastScResult +
               " sfg=0x" + hex(watchLastScFlags) +
               " wpc=0x" + hex(watchLastWriterPc) +
               " wad=0x" + hex(watchLastWriteAddr) +
               " wsz=" + watchLastWriteSize +
               " wov=0x" + hex(watchLastWriteOld) +
               " wnv=0x" + hex(watchLastWriteNew);
    }

    public String getMutexRegionSummary() {
        int best0 = -1;
        int best1 = -1;
        int best2 = -1;
        int i;

        for (i = 0; i < MUTEX_REGION_SLOTS; i++) {
            if (best0 < 0 || mutexRegionHits[i] > mutexRegionHits[best0]) {
                best2 = best1;
                best1 = best0;
                best0 = i;
            } else if (best1 < 0 || mutexRegionHits[i] > mutexRegionHits[best1]) {
                best2 = best1;
                best1 = i;
            } else if (best2 < 0 || mutexRegionHits[i] > mutexRegionHits[best2]) {
                best2 = i;
            }
        }

        return "vis=" + mutexRegionVisits +
               " last=0x" + hex(mutexRegionLastPc) +
               " a0=0x" + hex(mutexRegionLastLockAddr) +
               " mem=0x" + hex(mutexRegionLastLockWord) +
               " own=0x" + hex(mutexRegionLastLockWord & ~7) +
               " flg=0x" + hex(mutexRegionLastLockWord & 7) +
               " a1=0x" + hex(mutexRegionLastA1) +
               " a2=0x" + hex(mutexRegionLastA2) +
               " hot0=0x" + hex(hitPc(best0)) + "x" + hitValue(best0) +
               " hot1=0x" + hex(hitPc(best1)) + "x" + hitValue(best1) +
               " hot2=0x" + hex(hitPc(best2)) + "x" + hitValue(best2);
    }

    public String getHotPcSummary() {
        int best0 = -1;
        int best1 = -1;
        int best2 = -1;
        int i;

        for (i = 0; i < HOT_PC_SLOTS; i++) {
            if (best0 < 0 || hotPcHits[i] > hotPcHits[best0]) {
                best2 = best1;
                best1 = best0;
                best0 = i;
            } else if (best1 < 0 || hotPcHits[i] > hotPcHits[best1]) {
                best2 = best1;
                best1 = i;
            } else if (best2 < 0 || hotPcHits[i] > hotPcHits[best2]) {
                best2 = i;
            }
        }

        return "last=0x" + hex(hotLastPc) +
               " a0=0x" + hex(hotLastA0) +
               " mem=0x" + hex(hotLastA0Word) +
               " hot0=0x" + hotPcLabel(best0) +
               " hot1=0x" + hotPcLabel(best1) +
               " hot2=0x" + hotPcLabel(best2);
    }

    public String getQuietHotSummary() {
        int best0 = -1;
        int best1 = -1;
        int best2 = -1;
        int i;

        for (i = 0; i < HOT_PC_SLOTS; i++) {
            if (best0 < 0 || hotPcHits[i] > hotPcHits[best0]) {
                best2 = best1;
                best1 = best0;
                best0 = i;
            } else if (best1 < 0 || hotPcHits[i] > hotPcHits[best1]) {
                best2 = best1;
                best1 = i;
            } else if (best2 < 0 || hotPcHits[i] > hotPcHits[best2]) {
                best2 = i;
            }
        }

        return "last=" + shortPc(hotLastPc) +
               " a0=" + shortPc(hotLastA0) +
               " mem=" + shortHex(hotLastA0Word) +
               " h0=" + shortHotPc(best0) +
               " h1=" + shortHotPc(best1) +
               " h2=" + shortHotPc(best2);
    }

    public String getRecentPcTraceSummary() {
        int keep = recentPcCount;
        int start;
        int i;
        StringBuffer out = new StringBuffer();

        if (keep > 10)
            keep = 10;
        if (keep == 0)
            return "trace=none";

        start = recentPcHead - keep;
        if (start < 0)
            start += TRACE_SLOTS;

        out.append("trace=");
        for (i = 0; i < keep; i++) {
            if (i != 0)
                out.append('>');
            out.append(shortPc(recentPcTrace[(start + i) % TRACE_SLOTS]));
        }
        return out.toString();
    }

    public String getNiSyscallSummary() {
        if (niSyscallHits == 0)
            return "hits=0";

        return "nr=" + niLastNr +
               " hits=" + niSyscallHits +
               " pc=" + shortPc(niLastPc) +
               " ra=" + shortPc(regs[1]) +
               " sp=" + shortPc(regs[2]) +
               " a0=" + shortHex(niLastA0);
    }

    private void setReg(int rd, int val) {
        if (rd != 0)
            regs[rd] = val;
    }

    private int hitValue(int idx) {
        if (idx < 0 || idx >= MUTEX_REGION_SLOTS)
            return 0;
        return mutexRegionHits[idx];
    }

    private int hitPc(int idx) {
        if (idx < 0 || idx >= MUTEX_REGION_SLOTS)
            return 0;
        return MUTEX_REGION_START + (idx << 2);
    }

    private String hotPcLabel(int idx) {
        if (idx < 0 || idx >= HOT_PC_SLOTS || hotPcHits[idx] == 0)
            return "00000000x0";
        return hex(hotPcAddr[idx]) + "x" + hotPcHits[idx];
    }

    private String shortHotPc(int idx) {
        if (idx < 0 || idx >= HOT_PC_SLOTS || hotPcHits[idx] == 0)
            return "none";
        return shortPc(hotPcAddr[idx]) + "x" + hotPcHits[idx];
    }

    private String shortPc(int addr) {
        String s;
        int ramSize = bus.getRamSize();

        if (addr >= MemoryBus.RAM_BASE && addr < MemoryBus.RAM_BASE + ramSize) {
            s = Integer.toHexString(addr - MemoryBus.RAM_BASE);
            while (s.length() < 6)
                s = "0" + s;
            return s;
        }
        return hex(addr);
    }

    private String shortHex(int v) {
        String s = Integer.toHexString(v);
        while (s.length() < 8)
            s = "0" + s;
        return s;
    }

    private long u32(int v) {
        return v & 0xFFFFFFFFL;
    }

    private String hex(int v) {
        String s = Integer.toHexString(v);
        while (s.length() < 8)
            s = "0" + s;
        return s;
    }

    private int decodeJImm(int i) {
        int i20 = (i >> 31) & 1;
        int i10 = (i >> 21) & 0x3FF;
        int i11 = (i >> 20) & 1;
        int i19 = (i >> 12) & 0xFF;
        int imm = (i20 << 20) | (i19 << 12) | (i11 << 11) | (i10 << 1);
        if (i20 != 0)
            imm |= 0xFFE00000;
        return imm;
    }

    private int decodeBImm(int i) {
        int i12 = (i >> 31) & 1;
        int i10 = (i >> 25) & 0x3F;
        int i4 = (i >> 8) & 0x0F;
        int i11 = (i >> 7) & 1;
        int imm = (i12 << 12) | (i11 << 11) | (i10 << 5) | (i4 << 1);
        if (i12 != 0)
            imm |= 0xFFFFE000;
        return imm;
    }

    private int decodeSImm(int i) {
        int imm = (((i >> 25) & 0x7F) << 5) | ((i >> 7) & 0x1F);
        if ((imm & 0x800) != 0)
            imm |= 0xFFFFF000;
        return imm;
    }
}
