import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** 把一个目录打成 APK 结构的 zip：resources.arsc 不压缩且 4 字节对齐，其余 deflate。 */
public class ZipPack {

    static final class E {
        String name;
        byte[] raw;
        byte[] data;
        int method;
        long crc;
        int align;
    }

    static void walk(File root, File f, List<E> out) throws IOException {
        File[] kids = f.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, Comparator.comparing(File::getName));
        for (File k : kids) {
            if (k.isDirectory()) {
                walk(root, k, out);
            } else {
                String rel = root.toPath().relativize(k.toPath()).toString().replace(File.separatorChar, '/');
                E e = new E();
                e.name = rel;
                e.raw = Files.readAllBytes(k.toPath());
                boolean store = rel.equals("resources.arsc");
                e.method = store ? 0 : 8;
                e.align = store ? 4 : 1;
                CRC32 c = new CRC32();
                c.update(e.raw);
                e.crc = c.getValue();
                if (store) {
                    e.data = e.raw;
                } else {
                    Deflater def = new Deflater(9, true);
                    def.setInput(e.raw);
                    def.finish();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, e.raw.length / 2));
                    byte[] buf = new byte[65536];
                    while (!def.finished()) {
                        int n = def.deflate(buf);
                        bos.write(buf, 0, n);
                    }
                    def.end();
                    e.data = bos.toByteArray();
                    if (e.data.length >= e.raw.length) {   // 压缩没收益就存原样
                        e.method = 0;
                        e.data = e.raw;
                    }
                }
                out.add(e);
            }
        }
    }

    static void le32(OutputStream o, long v) throws IOException {
        o.write((int) (v & 0xff));
        o.write((int) ((v >>> 8) & 0xff));
        o.write((int) ((v >>> 16) & 0xff));
        o.write((int) ((v >>> 24) & 0xff));
    }

    static void le16(OutputStream o, int v) throws IOException {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        File outFile = new File(a[1]);
        List<E> list = new ArrayList<>();
        walk(dir, dir, list);

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        long off = 0;
        List<long[]> cd = new ArrayList<>();
        List<E> cdE = new ArrayList<>();

        for (E e : list) {
            byte[] name = e.name.getBytes("UTF-8");
            int extraLen = 0;
            // 对齐填充：使用 0xd935 扩展字段
            if (e.align > 1) {
                int base = (int) (off + 30 + name.length);
                int pad = (e.align - (base % e.align)) % e.align;
                extraLen = 4 + pad;
                if (extraLen == 4) extraLen = 0;
            }
            long localOff = off;
            le32(out, 0x04034b50L);
            le16(out, 20);
            le16(out, 0x0800);
            le16(out, e.method);
            le16(out, 0);
            le16(out, 0x21);
            le32(out, e.crc);
            le32(out, e.data.length);
            le32(out, e.raw.length);
            le16(out, name.length);
            le16(out, extraLen);
            out.write(name);
            if (extraLen > 0) {
                le16(out, 0xd935);
                le16(out, extraLen - 4);
                for (int i = 0; i < extraLen - 4; i++) out.write(0);
            }
            out.write(e.data);
            off = out.size();
            cd.add(new long[]{localOff, e.crc, e.data.length, e.raw.length});
            cdE.add(e);
        }

        long cdStart = off;
        for (int i = 0; i < cdE.size(); i++) {
            E e = cdE.get(i);
            long[] v = cd.get(i);
            byte[] name = e.name.getBytes("UTF-8");
            le32(out, 0x02014b50L);
            le16(out, 20);
            le16(out, 20);
            le16(out, 0x0800);
            le16(out, e.method);
            le16(out, 0);
            le16(out, 0x21);
            le32(out, v[1]);
            le32(out, v[2]);
            le32(out, v[3]);
            le16(out, name.length);
            le16(out, 0);
            le16(out, 0);
            le16(out, 0);
            le16(out, 0);
            le32(out, 0);
            le32(out, v[0]);
            out.write(name);
        }
        long cdSize = out.size() - cdStart;
        le32(out, 0x06054b50L);
        le16(out, 0);
        le16(out, 0);
        le16(out, cdE.size());
        le16(out, cdE.size());
        le32(out, cdSize);
        le32(out, cdStart);
        le16(out, 0);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            out.writeTo(fos);
        }
        System.out.println("packed " + cdE.size() + " entries -> " + outFile + " (" + outFile.length() + " bytes)");
    }
}
