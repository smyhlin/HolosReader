package com.holos.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxAddonCatalogTest {

    @Test
    fun allFourAddonsSupportDirectInAppDownload() {
        assertEquals(4, OnnxAddonCatalog.all.size)
        for (spec in OnnxAddonCatalog.all) {
            assertTrue("Expected directDownloadAvailable=true for ${spec.id}", spec.directDownloadAvailable)
            assertTrue("Expected non-empty requiredFiles for ${spec.id}", spec.requiredFiles.isNotEmpty())
        }
    }

    @Test
    fun addonReleaseUrlsPointToVerifiedHttpsEndpoints() {
        val urls = listOf(
            OnnxAddonManager.FLASH_SR_ONNX_URL,
            OnnxAddonManager.UK_EMOTION_ONNX_URL,
            OnnxAddonManager.UK_EMOTION_TOKENIZER_URL,
            OnnxAddonManager.CONTEXTUAL_STRESS_TOKENIZER_URL,
            OnnxAddonManager.CONTEXTUAL_STRESS_FP16_ONNX_URL,
            OnnxAddonManager.CONTEXTUAL_STRESS_INT8_ONNX_URL,
            OnnxAddonManager.VOICE_ENCODER_ONNX_URL,
        )
        for (url in urls) {
            assertTrue("URL must be HTTPS: $url", url.startsWith("https://"))
        }
        assertTrue(OnnxAddonManager.CONTEXTUAL_STRESS_FP16_ONNX_URL.contains("/onnx-addons-v1/uk_stress_contextual_fp16.onnx"))
        assertTrue(OnnxAddonManager.CONTEXTUAL_STRESS_INT8_ONNX_URL.contains("/onnx-addons-v1/uk_stress_contextual_int8.onnx"))
        assertTrue(OnnxAddonManager.VOICE_ENCODER_ONNX_URL.contains("/onnx-addons-v1/voice_encoder.onnx"))
    }
}
