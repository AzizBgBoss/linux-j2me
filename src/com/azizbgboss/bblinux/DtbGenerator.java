package com.azizbgboss.bblinux;

import java.io.ByteArrayOutputStream;
import java.util.Hashtable;

public final class DtbGenerator {

    private static final int FDT_MAGIC = 0xD00DFEED;
    private static final int FDT_VERSION = 17;
    private static final int FDT_LAST_COMP_VERSION = 16;

    private static final int FDT_BEGIN_NODE = 1;
    private static final int FDT_END_NODE = 2;
    private static final int FDT_PROP = 3;
    private static final int FDT_END = 9;

    private static final int CPU_INTC_PHANDLE = 1;
    private static final String BOOTARGS_BASE =
        "earlycon=uart8250,mmio,0x10000000 console=ttyS0 loglevel=8 keep_bootcon";
    private static final String BOOTARGS_INITRAMFS =
        BOOTARGS_BASE + " rdinit=/sbin/init";

    private ByteArrayOutputStream structOut = new ByteArrayOutputStream();
    private ByteArrayOutputStream stringsOut = new ByteArrayOutputStream();
    private Hashtable stringOffsets = new Hashtable();
    private String bootArgs = BOOTARGS_BASE;
    private long initrdStart = -1L;
    private long initrdEnd = -1L;
    private long memorySize = MemoryBus.DEFAULT_RAM_SIZE;

    public static byte[] createMinimalTree() {
        DtbGenerator gen = new DtbGenerator();
        return gen.build();
    }

    public static byte[] createMinimalTree(long memorySize) {
        DtbGenerator gen = new DtbGenerator();
        gen.memorySize = memorySize;
        return gen.build();
    }

    public static byte[] createMinimalTree(long initrdStart, long initrdEnd) {
        DtbGenerator gen = new DtbGenerator();
        if (initrdStart >= 0L && initrdEnd > initrdStart) {
            gen.bootArgs = BOOTARGS_INITRAMFS;
            gen.initrdStart = initrdStart;
            gen.initrdEnd = initrdEnd;
        }
        gen.memorySize = MemoryBus.DEFAULT_RAM_SIZE;
        return gen.build();
    }

    public static byte[] createMinimalTree(long memorySize, long initrdStart, long initrdEnd) {
        DtbGenerator gen = new DtbGenerator();
        gen.memorySize = memorySize;
        if (initrdStart >= 0L && initrdEnd > initrdStart) {
            gen.bootArgs = BOOTARGS_INITRAMFS;
            gen.initrdStart = initrdStart;
            gen.initrdEnd = initrdEnd;
        }
        return gen.build();
    }

    private byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] structBlock;
        byte[] stringsBlock;
        int offMemRsvmap;
        int offDtStruct;
        int offDtStrings;
        int totalsize;

        beginNode("");
        propU32("#address-cells", 2);
        propU32("#size-cells", 2);
        propStringList("compatible", new String[] { "aziz,bblinux", "simple-bus" });
        propString("model", "Aziz BBLinux");

        beginNode("chosen");
        propString("bootargs", bootArgs);
        propString("stdout-path", "/soc/serial@10000000");
        if (initrdStart >= 0L && initrdEnd > initrdStart) {
            propU64("linux,initrd-start", initrdStart);
            propU64("linux,initrd-end", initrdEnd);
        }
        endNode();

        beginNode("memory@80000000");
        propString("device_type", "memory");
        propReg64(0x80000000L, memorySize);
        endNode();

        beginNode("cpus");
        propU32("#address-cells", 1);
        propU32("#size-cells", 0);
        propU32("timebase-frequency", 1000000);

        beginNode("cpu@0");
        propString("device_type", "cpu");
        propString("compatible", "riscv");
        propU32("reg", 0);
        propString("status", "okay");
        propString("riscv,isa", "rv32ima_zicsr_zifencei");

        beginNode("interrupt-controller");
        propNull("interrupt-controller");
        propU32("#interrupt-cells", 1);
        propString("compatible", "riscv,cpu-intc");
        propU32("phandle", CPU_INTC_PHANDLE);
        endNode();

        endNode();
        endNode();

        beginNode("soc");
        propU32("#address-cells", 2);
        propU32("#size-cells", 2);
        propString("compatible", "simple-bus");
        propEmpty("ranges");

        beginNode("serial@10000000");
        propString("compatible", "ns16550a");
        propReg64(0x10000000L, 0x100L);
        propU32("clock-frequency", 3686400);
        propU32("current-speed", 115200);
        propString("status", "okay");
        endNode();

        beginNode("clint@2000000");
        propStringList("compatible", new String[] { "sifive,clint0", "riscv,clint0" });
        propReg64(0x02000000L, 0x10000L);
        propU32Array("interrupts-extended", new int[] {
            CPU_INTC_PHANDLE, 3,
            CPU_INTC_PHANDLE, 7
        });
        propString("status", "okay");
        endNode();

        endNode();
        endNode();
        writeU32(structOut, FDT_END);

        structBlock = structOut.toByteArray();
        stringsBlock = stringsOut.toByteArray();

        offMemRsvmap = 40;
        offDtStruct = offMemRsvmap + 16;
        offDtStrings = offDtStruct + structBlock.length;
        totalsize = offDtStrings + stringsBlock.length;

        writeU32(out, FDT_MAGIC);
        writeU32(out, totalsize);
        writeU32(out, offDtStruct);
        writeU32(out, offDtStrings);
        writeU32(out, offMemRsvmap);
        writeU32(out, FDT_VERSION);
        writeU32(out, FDT_LAST_COMP_VERSION);
        writeU32(out, 0);
        writeU32(out, stringsBlock.length);
        writeU32(out, structBlock.length);

        writeU64(out, 0L);
        writeU64(out, 0L);

        writeBytes(out, structBlock);
        writeBytes(out, stringsBlock);
        return out.toByteArray();
    }

    private void beginNode(String name) {
        writeU32(structOut, FDT_BEGIN_NODE);
        writeStringAligned(structOut, name);
    }

    private void endNode() {
        writeU32(structOut, FDT_END_NODE);
    }

    private void propNull(String name) {
        propRaw(name, new byte[0]);
    }

    private void propEmpty(String name) {
        propRaw(name, new byte[0]);
    }

    private void propString(String name, String value) {
        propRaw(name, cString(value));
    }

    private void propStringList(String name, String[] values) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int i;
        for (i = 0; i < values.length; i++)
            writeBytes(buf, cString(values[i]));
        propRaw(name, buf.toByteArray());
    }

    private void propU32(String name, int value) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeU32(buf, value);
        propRaw(name, buf.toByteArray());
    }

    private void propU64(String name, long value) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeU64(buf, value);
        propRaw(name, buf.toByteArray());
    }

    private void propU32Array(String name, int[] values) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int i;
        for (i = 0; i < values.length; i++)
            writeU32(buf, values[i]);
        propRaw(name, buf.toByteArray());
    }

    private void propReg64(long addr, long size) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeU32(buf, (int) ((addr >>> 32) & 0xFFFFFFFFL));
        writeU32(buf, (int) (addr & 0xFFFFFFFFL));
        writeU32(buf, (int) ((size >>> 32) & 0xFFFFFFFFL));
        writeU32(buf, (int) (size & 0xFFFFFFFFL));
        propRaw("reg", buf.toByteArray());
    }

    private void propRaw(String name, byte[] data) {
        writeU32(structOut, FDT_PROP);
        writeU32(structOut, data.length);
        writeU32(structOut, stringOffset(name));
        writeBytes(structOut, data);
        align4(structOut);
    }

    private int stringOffset(String s) {
        Integer boxed = (Integer) stringOffsets.get(s);
        int off;
        if (boxed != null)
            return boxed.intValue();
        off = stringsOut.size();
        stringOffsets.put(s, new Integer(off));
        writeBytes(stringsOut, cString(s));
        return off;
    }

    private byte[] cString(String s) {
        byte[] data = new byte[s.length() + 1];
        int i;
        for (i = 0; i < s.length(); i++)
            data[i] = (byte) s.charAt(i);
        data[s.length()] = 0;
        return data;
    }

    private void writeStringAligned(ByteArrayOutputStream out, String s) {
        writeBytes(out, cString(s));
        align4(out);
    }

    private void align4(ByteArrayOutputStream out) {
        while ((out.size() & 3) != 0)
            out.write(0);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU64(ByteArrayOutputStream out, long value) {
        writeU32(out, (int) ((value >>> 32) & 0xFFFFFFFFL));
        writeU32(out, (int) (value & 0xFFFFFFFFL));
    }
}
