import java.io.*;
import java.nio.*;
import java.nio.file.*;

/**
 * 极简 ELF 裁剪：只保留程序头里被加载的段，删掉符号表 / 调试段 / 段头表。
 * Android 动态链接器只读程序头，不读段头表，因此这样裁剪后仍可正常 dlopen。
 */
public class Shrink {

    public static void main(String[] a) throws Exception {
        File in = new File(a[0]);
        File out = new File(a[1]);
        byte[] b = Files.readAllBytes(in.toPath());
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        if (b.length < 64 || b[0] != 0x7F || b[1] != 'E' || b[2] != 'L' || b[3] != 'F') {
            System.out.println("不是 ELF: " + in);
            return;
        }
        int cls = b[4] & 0xFF;        // 1=32, 2=64
        boolean is64 = cls == 2;
        long phoff = is64 ? buf.getLong(0x20) : (buf.getInt(0x1C) & 0xFFFFFFFFL);
        int phentsize = is64 ? (buf.getShort(0x36) & 0xFFFF) : (buf.getShort(0x2A) & 0xFFFF);
        int phnum = is64 ? (buf.getShort(0x38) & 0xFFFF) : (buf.getShort(0x2C) & 0xFFFF);
        long shoff = is64 ? buf.getLong(0x28) : (buf.getInt(0x20) & 0xFFFFFFFFL);

        long keep = 0;
        for (int i = 0; i < phnum; i++) {
            int base = (int) (phoff + (long) i * phentsize);
            if (base + phentsize > b.length) break;
            long off = is64 ? buf.getLong(base + 0x08) : (buf.getInt(base + 0x04) & 0xFFFFFFFFL);
            long fsz = is64 ? buf.getLong(base + 0x20) : (buf.getInt(base + 0x10) & 0xFFFFFFFFL);
            long end = off + fsz;
            if (end > keep) keep = end;
        }
        if (keep <= 0 || keep > b.length) keep = b.length;

        // 段头表指向文件尾部，裁掉数据后必须一并失效
        if (is64) {
            buf.putLong(0x28, 0);        // e_shoff
            buf.putShort(0x3A, (short) 0); // e_shentsize
            buf.putShort(0x3C, (short) 0); // e_shnum
            buf.putShort(0x3E, (short) 0); // e_shstrndx
        } else {
            buf.putInt(0x20, 0);
            buf.putShort(0x2E, (short) 0);
            buf.putShort(0x30, (short) 0);
            buf.putShort(0x32, (short) 0);
        }

        out.getParentFile().mkdirs();
        try (FileOutputStream fo = new FileOutputStream(out)) {
            fo.write(b, 0, (int) keep);
        }
        System.out.printf("%s  %.1f MB -> %.1f MB (段表原偏移 %d)%n",
                in.getName(), b.length / 1048576.0, keep / 1048576.0, shoff);
    }
}
