package com.ofdeditor.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 将两个文件打包为单一二进制响应，供前端解包后分别保存。
 * 格式：[name1 utf8][len1 int][bytes1][name2 utf8][len2 int][bytes2]
 */
public final class SplitPayloadUtil {

    public record PackedFile(String filename, byte[] bytes) {}

    private SplitPayloadUtil() {}

    public static byte[] pack(String name1, byte[] data1, String name2, byte[] data2) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            writeFileEntry(out, name1, data1);
            writeFileEntry(out, name2, data2);
        }
        return baos.toByteArray();
    }

    public static List<PackedFile> unpack(byte[] payload) throws IOException {
        List<PackedFile> files = new ArrayList<>(2);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            while (in.available() > 0) {
                String name = in.readUTF();
                int len = in.readInt();
                if (len < 0 || len > in.available()) {
                    throw new IOException("无效的拆分包长度: " + len);
                }
                byte[] data = in.readNBytes(len);
                files.add(new PackedFile(name, data));
            }
        }
        return files;
    }

    private static void writeFileEntry(DataOutputStream out, String name, byte[] data) throws IOException {
        out.writeUTF(name != null ? name : "part.bin");
        out.writeInt(data != null ? data.length : 0);
        if (data != null && data.length > 0) {
            out.write(data);
        }
    }

    public static String buildPartFilename(String baseName, int partIndex, String ext) {
        String base = baseName == null ? "" : baseName.trim();
        if (base.isEmpty()) base = "文档";
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.toLowerCase().endsWith("." + ext.toLowerCase())) {
            base = base.substring(0, base.length() - ext.length() - 1);
        }
        String suffix = partIndex == 1 ? "第一部分" : "第二部分";
        return base + suffix + "." + ext;
    }

    public static void validateSplit(int pageCount, int splitAfterPage) {
        if (pageCount <= 1) {
            throw new IllegalArgumentException("仅 1 页的文件无法拆分");
        }
        if (splitAfterPage < 1 || splitAfterPage >= pageCount) {
            throw new IllegalArgumentException(
                    "拆分页码无效：请输入 1～" + (pageCount - 1) + "（第一部分最后一页的页码）");
        }
    }
}
