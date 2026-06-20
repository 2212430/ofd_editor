package com.ofdeditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调用 Python pdf2docx 将 PDF 转为 Word（.docx）。
 */
@Slf4j
@Service
public class PdfToWordService {

    @Value("${ofd.pdf2docx.python:python}")
    private String pythonCommand;

    /** 可选：脚本绝对路径；为空时在常见目录中自动查找 backend/scripts/pdf_to_docx.py */
    @Value("${ofd.pdf2docx.script-path:}")
    private String scriptPathOverride;

    @Value("${ofd.pdf2docx.timeout-seconds:600}")
    private int timeoutSeconds;

    public byte[] convert(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择 PDF 文件");
        }
        Path tempPdf = Files.createTempFile("pdf_in_", ".pdf");
        Path tempDocx = Files.createTempFile("docx_out_", ".docx");
        try {
            file.transferTo(tempPdf);
            log.info("开始 PDF->Word (pdf2docx): {}", file.getOriginalFilename());
            runPdf2Docx(tempPdf, tempDocx);
            byte[] result = Files.readAllBytes(tempDocx);
            log.info("PDF->Word 完成，输出大小: {} bytes", result.length);
            return result;
        } finally {
            Files.deleteIfExists(tempPdf);
            Files.deleteIfExists(tempDocx);
        }
    }

    public byte[] convertBytes(byte[] pdfBytes, String logName) throws Exception {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF 内容为空");
        }
        Path tempPdf = Files.createTempFile("pdf_in_", ".pdf");
        Path tempDocx = Files.createTempFile("docx_out_", ".docx");
        try {
            Files.write(tempPdf, pdfBytes);
            log.info("开始 PDF->Word (pdf2docx): {}", logName);
            runPdf2Docx(tempPdf, tempDocx);
            return Files.readAllBytes(tempDocx);
        } finally {
            Files.deleteIfExists(tempPdf);
            Files.deleteIfExists(tempDocx);
        }
    }

    private void runPdf2Docx(Path inputPdf, Path outputDocx) throws Exception {
        Path script = resolveScriptPath();
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonCommand);
        cmd.add(script.toString());
        cmd.add(inputPdf.toAbsolutePath().toString());
        cmd.add(outputDocx.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                log.debug("[pdf2docx] {}", line);
            }
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("PDF 转 Word 超时（超过 " + timeoutSeconds + " 秒）");
        }
        int code = process.exitValue();
        if (code != 0) {
            String msg = output.length() > 0 ? output.toString().trim() : ("exit code " + code);
            throw new IllegalStateException("pdf2docx 转换失败: " + msg);
        }
        if (!Files.isRegularFile(outputDocx) || Files.size(outputDocx) == 0) {
            throw new IllegalStateException("pdf2docx 未生成有效的 docx 文件");
        }
    }

    private Path resolveScriptPath() {
        if (scriptPathOverride != null && !scriptPathOverride.isBlank()) {
            Path p = Path.of(scriptPathOverride.trim());
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("pdf2docx 脚本不存在: " + p.toAbsolutePath());
            }
            return p.toAbsolutePath();
        }
        String rel = "tools/pdf_to_docx.py";
        String userDir = System.getProperty("user.dir");
        Path[] candidates = {
                Path.of(userDir, rel),
                Path.of(userDir, "backend", rel),
                Path.of(userDir).getParent() != null
                        ? Path.of(userDir).getParent().resolve("backend").resolve(rel)
                        : null,
        };
        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c)) {
                return c.toAbsolutePath();
            }
        }
        throw new IllegalStateException(
                "未找到 tools/pdf_to_docx.py。请在后端目录执行: pip install -r requirements-pdf2docx.txt");
    }
}
