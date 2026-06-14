package com.ofdeditor.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.File;
import java.io.InputStream;

/**
 * 为「文本注释」烘焙提供可渲染中文的字体。
 * 优先从系统字体目录加载常见中文字体；找不到时返回 null（调用方跳过文本绘制，仅画框）。
 *
 * 注意：PDType0Font 与 PDDocument 绑定，必须按文档加载，不能跨文档缓存。
 */
@Slf4j
final class FontProvider {

    private FontProvider() {
    }

    /** 单文件 TTF 优先（加载简单、子集化稳定） */
    private static final String[] TTF_CANDIDATES = {
            "C:/Windows/Fonts/simfang.ttf",   // 仿宋
            "C:/Windows/Fonts/simkai.ttf",    // 楷体
            "C:/Windows/Fonts/simhei.ttf",    // 黑体
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/System/Library/Fonts/PingFang.ttc",
    };

    /** TTC 字体集合（需指定其中一个字体名） */
    private static final String[][] TTC_CANDIDATES = {
            {"C:/Windows/Fonts/msyh.ttc", "Microsoft YaHei"},
            {"C:/Windows/Fonts/simsun.ttc", "SimSun"},
            {"C:/Windows/Fonts/msyh.ttf", "Microsoft YaHei"},
    };

    static PDFont cjkFont(PDDocument doc) {
        for (String path : TTF_CANDIDATES) {
            File f = new File(path);
            if (f.isFile()) {
                try {
                    return PDType0Font.load(doc, f);
                } catch (Exception e) {
                    log.debug("加载字体失败 {}: {}", path, e.getMessage());
                }
            }
        }
        for (String[] entry : TTC_CANDIDATES) {
            File f = new File(entry[0]);
            if (f.isFile()) {
                try {
                    TrueTypeCollection ttc = new TrueTypeCollection(f);
                    TrueTypeFont ttf = ttc.getFontByName(entry[1]);
                    if (ttf == null) continue;
                    return PDType0Font.load(doc, ttf, true);
                } catch (Exception e) {
                    log.debug("加载 TTC 字体失败 {}: {}", entry[0], e.getMessage());
                }
            }
        }
        // classpath 兜底：resources/fonts/cjk.ttf（如有打包）
        try (InputStream in = FontProvider.class.getResourceAsStream("/fonts/cjk.ttf")) {
            if (in != null) {
                return PDType0Font.load(doc, in, true);
            }
        } catch (Exception e) {
            log.debug("加载内置字体失败: {}", e.getMessage());
        }
        return null;
    }
}
