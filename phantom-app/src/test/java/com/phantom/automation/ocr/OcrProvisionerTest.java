package com.phantom.automation.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Pure-JVM tests for the OCR provisioning helpers (no device needed). */
public final class OcrProvisionerTest {
    @Test
    public void tarGzRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("phantom-ocr-test").toFile();
        File archive = new File(dir, "test.tar.gz");
        Files.write(archive.toPath(), OcrProvisioner.makeTestArchive());
        File dest = new File(dir, "out");
        OcrProvisioner.extractTarGz(archive, dest);
        File extracted = new File(dest, "models/a.txt");
        assertTrue("expected extracted file, got: " + dest,
                extracted.isFile());
        assertEquals("hello", new String(Files.readAllBytes(extracted.toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void notProvisionedOnEmptyDir() throws Exception {
        File dir = Files.createTempDirectory("phantom-ocr-empty").toFile();
        assertFalse(OcrProvisioner.isProvisioned(dir));
    }

    @Test
    public void pathShapes() throws Exception {
        File files = new File("/data/data/com.phantom.automation/files");
        assertEquals(new File(files, "ocr/models/ch_PP-OCRv2"),
                OcrProvisioner.modelDir(files));
        assertEquals(new File(files, "ocr/labels/ppocr_keys_v1.txt"),
                OcrProvisioner.labelFile(files));
        assertEquals(new File(files, "ocr/lib/arm64-v8a"),
                OcrProvisioner.libDir(files));
    }
}
