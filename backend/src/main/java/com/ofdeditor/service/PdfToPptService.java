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
 * 调用 Python（PyMuPDF + python-pptx）将 PDF 转为 PowerPoint（.pptx）。
 */
@Slf4j
@Service
public class PdfToPptService {

    @Value("${ofd.pdf2pptx.python:python}")
    private String pythonCommand;

    @Value("${ofd.pdf2pptx.script-path:}")
    private String scriptPathOverride;

    @Value("${ofd.pdf2pptx.timeout-seconds:900}")
    private int timeoutSeconds;

    @Value("${ofd.pdf2pptx.resolution-dpi:150}")
    private int resolutionDpi;

    public byte[] convert(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择 PDF 文件");
        }
        Path tempPdf = Files.createTempFile("pdf_in_", ".pdf");
        Path tempPptx = Files.createTempFile("pptx_out_", ".pptx");
        try {
            file.transferTo(tempPdf);
            log.info("开始 PDF->PPT: {}", file.getOriginalFilename());
            runPdf2Pptx(tempPdf, tempPptx);
            byte[] result = Files.readAllBytes(tempPptx);
            log.info("PDF->PPT 完成，输出大小: {} bytes", result.length);
            return result;
        } finally {
            Files.deleteIfExists(tempPdf);
            Files.deleteIfExists(tempPptx);
        }
    }

    public byte[] convertBytes(byte[] pdfBytes, String logName) throws Exception {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF 内容为空");
        }
        Path tempPdf = Files.createTempFile("pdf_in_", ".pdf");
        Path tempPptx = Files.createTempFile("pptx_out_", ".pptx");
        try {
            Files.write(tempPdf, pdfBytes);
            log.info("开始 PDF->PPT: {}", logName);
            runPdf2Pptx(tempPdf, tempPptx);
            return Files.readAllBytes(tempPptx);
        } finally {
            Files.deleteIfExists(tempPdf);
            Files.deleteIfExists(tempPptx);
        }
    }

    private void runPdf2Pptx(Path inputPdf, Path outputPptx) throws Exception {
        Path script = resolveScriptPath();
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonCommand);
        cmd.add(script.toString());
        cmd.add(inputPdf.toAbsolutePath().toString());
        cmd.add(outputPptx.toAbsolutePath().toString());
        cmd.add(String.valueOf(resolutionDpi));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                log.debug("[pdf2pptx] {}", line);
            }
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("PDF 转 PPT 超时（超过 " + timeoutSeconds + " 秒）");
        }
        int code = process.exitValue();
        if (code != 0) {
            String msg = output.length() > 0 ? output.toString().trim() : ("exit code " + code);
            throw new IllegalStateException("PDF 转 PPT 失败: " + msg);
        }
        if (!Files.isRegularFile(outputPptx) || Files.size(outputPptx) == 0) {
            throw new IllegalStateException("未生成有效的 pptx 文件");
        }
    }

    private Path resolveScriptPath() {
        if (scriptPathOverride != null && !scriptPathOverride.isBlank()) {
            Path p = Path.of(scriptPathOverride.trim());
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("pdf2pptx 脚本不存在: " + p.toAbsolutePath());
            }
            return p.toAbsolutePath();
        }
        String rel = "tools/pdf_to_pptx.py";
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
                "未找到 tools/pdf_to_pptx.py。请在后端目录执行: pip install -r requirements-pdf2pptx.txt");
    }
}
