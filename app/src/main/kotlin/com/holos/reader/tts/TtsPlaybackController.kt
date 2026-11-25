package com.holos.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.holos.reader.epub.ReaderBlock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Look-ahead Producer–Consumer TTS playback controller for EPUB 3 chapters.
 *
 * - Initializes ONNX engine & stress dictionary off the UI thread (zero freezes/ANRs/crashes).
 * - Producer thread synthesizes upcoming paragraphs ahead of time into a bounded PCM queue
 *   so there are ZERO dead-air pauses between sentences and paragraphs.
 * - Consumer thread streams PCM float audio to [AudioTrack] and emits [Listener.onActiveBlockChanged]
 *   at the EXACT moment each paragraph begins playing, keeping text highlighting 100% synchronized.
 */
class TtsPlaybackController(private val context: Context) {

    interface Listener {
        fun onPlaybackStateChanged(isPlaying: Boolean, isPaused: Boolean, statusText: String)
        fun onActiveBlockChanged(blockIndex: Int)
        fun onChapterFinished()
        fun onError(message: String)
    }

    private sealed class Packet {
        data class Audio(val blockIndex: Int, val samples: FloatArray) : Packet()
        data class BlockEnd(val blockIndex: Int) : Packet()
        data object ChapterEnd : Packet()
    }

    private val cancelFlag = AtomicBoolean(false)
    private val pausedFlag = AtomicBoolean(false)
    private val playingFlag = AtomicBoolean(false)
    private val sleepDeadlineMs = AtomicLong(0L)
    private val sleepAtChapterEnd = AtomicBoolean(false)
    private val activeBlock = AtomicInteger(-1)
    private val sessionGen = AtomicLong(0L)

    @Volatile
    var listener: Listener? = null

    @Volatile
    private var initThread: Thread? = null
    @Volatile
    private var producerThread: Thread? = null
    @Volatile
    private var consumerThread: Thread? = null
    @Volatile
    private var currentTrack: AudioTrack? = null

    val isPlaying: Boolean get() = playingFlag.get()
    val isPaused: Boolean get() = pausedFlag.get()
    val currentBlockIndex: Int get() = activeBlock.get()

    fun setSleepTimerMinutes(minutes: Int) {
        sleepAtChapterEnd.set(false)
        sleepDeadlineMs.set(
            if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        )
    }

    fun setSleepAtChapterEnd(enabled: Boolean) {
        sleepDeadlineMs.set(0L)
        sleepAtChapterEnd.set(enabled)
    }

    fun startFrom(blocks: List<ReaderBlock>, startBlockIndex: Int) {
        stop()
        if (blocks.isEmpty()) return

        val gen = sessionGen.incrementAndGet()
        cancelFlag.set(false)
        pausedFlag.set(false)
        playingFlag.set(true)

        val startIdx = startBlockIndex.coerceIn(0, blocks.lastIndex)
        listener?.onPlaybackStateChanged(isPlaying = true, isPaused = false, statusText = "Підготовка голосу…")

        val bootstrap = Thread({
            val engine = try {
                HolosEngineHolder.getEngine(context)
            } catch (t: Throwable) {
                if (isCurrent(gen)) {
                    playingFlag.set(false)
                    listener?.onError("Помилка завантаження моделі: ${t.message ?: t.javaClass.simpleName}")
                }
                return@Thread
            }
            if (!isCurrent(gen)) return@Thread
            if (engine == null) {
                playingFlag.set(false)
                listener?.onError("Спочатку завантажте модель HolosTTS (~320 МБ) на головному екрані.")
                return@Thread
            }

            val stressifier = try {
                HolosEngineHolder.getStressifier(context)
            } catch (t: Throwable) {
                if (isCurrent(gen)) {
                    playingFlag.set(false)
                    listener?.onError("Помилка ініціалізації наголосів: ${t.message ?: t.javaClass.simpleName}")
                }
                return@Thread
            }
            if (!isCurrent(gen)) return@Thread

            val prefs = ReaderPreferences(context)
            val customDir = HolosEngineHolder.getCustomVoicesDir(context)
            val voice = prefs.selectedVoice?.takeIf {
                it.startsWith(CustomVoiceLoader.CUSTOM_PREFIX) || it in engine.voices
            } ?: engine.voices.firstOrNull()
            val speed = prefs.speechSpeed

            // If this chapter has no speakable blocks from startIdx onward (e.g. cover page), advance cleanly
            val hasSpeakable = (startIdx until blocks.size).any { blocks[it].isSpeakable }
            if (!hasSpeakable) {
                playingFlag.set(false)
                if (isCurrent(gen)) {
                    listener?.onChapterFinished()
                }
                return@Thread
            }

            val queue = LinkedBlockingQueue<Packet>(24)
            listener?.onPlaybackStateChanged(isPlaying = true, isPaused = false, statusText = "Синтез…")

            // Producer: pre-synthesizes current + upcoming paragraphs ahead of playback
            val prod = Thread({
                try {
                    for (i in startIdx until blocks.size) {
                        if (!isCurrent(gen)) return@Thread
                        val block = blocks[i]
                        if (!block.isSpeakable) continue

                        HolosSafeSynthesizer.synthesizeParagraphStreaming(
                            engine = engine,
                            stressifier = stressifier,
                            rawParagraph = block.text,
                            voice = voice,
                            speed = speed,
                            cancelled = cancelFlag,
                            customVoicesDir = customDir,
                        ) { chunk ->
                            while (isCurrent(gen)) {
                                if (queue.offer(Packet.Audio(i, chunk), 100, TimeUnit.MILLISECONDS)) break
                            }
                        }
                        if (isCurrent(gen)) {
                            // Brief 260 ms paragraph pause
                            val paraPause = FloatArray((engine.sampleRate * 260) / 1000)
                            while (isCurrent(gen)) {
                                if (queue.offer(Packet.Audio(i, paraPause), 100, TimeUnit.MILLISECONDS)) break
                            }
                            queue.offer(Packet.BlockEnd(i), 200, TimeUnit.MILLISECONDS)
                        }
                    }
                    if (isCurrent(gen)) {
                        queue.offer(Packet.ChapterEnd, 500, TimeUnit.MILLISECONDS)
                    }
                } catch (t: Throwable) {
                    if (isCurrent(gen)) {
                        cancelFlag.set(true)
                        listener?.onError("Помилка синтезу: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
            }, "HolosProducer")

            // Consumer: plays PCM float chunks and drives synchronized paragraph highlighting
            val cons = Thread({
                val track = try {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(engine.sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(2 * engine.sampleRate * 4)
                        .build()
                } catch (t: Throwable) {
                    if (isCurrent(gen)) {
                        playingFlag.set(false)
                        cancelFlag.set(true)
                        listener?.onError("Помилка аудіовиходу: ${t.message ?: t.javaClass.simpleName}")
                    }
                    return@Thread
                }
                currentTrack = track

                var lastReportedBlock = -1
                var chapterEndedNaturally = false

                try {
                    while (isCurrent(gen)) {
                        val deadline = sleepDeadlineMs.get()
                        if (deadline > 0L && System.currentTimeMillis() >= deadline) {
                            sleepDeadlineMs.set(0L)
                            cancelFlag.set(true)
                            listener?.onPlaybackStateChanged(false, false, "Таймер сну завершено")
                            break
                        }

                        while (pausedFlag.get() && isCurrent(gen)) {
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                            Thread.sleep(40)
                        }
                        if (!isCurrent(gen)) break

                        val packet = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        when (packet) {
                            is Packet.Audio -> {
                                if (packet.blockIndex != lastReportedBlock) {
                                    lastReportedBlock = packet.blockIndex
                                    activeBlock.set(packet.blockIndex)
                                    listener?.onActiveBlockChanged(packet.blockIndex)
                                    listener?.onPlaybackStateChanged(true, false, "Озвучення…")
                                }
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    track.play()
                                }
                                track.write(packet.samples, 0, packet.samples.size, AudioTrack.WRITE_BLOCKING)
                            }
                            is Packet.BlockEnd -> Unit
                            is Packet.ChapterEnd -> {
                                chapterEndedNaturally = true
                                break
                            }
                        }
                    }
                    if (chapterEndedNaturally && isCurrent(gen)) {
                        Thread.sleep(350)
                    }
                } catch (_: InterruptedException) {
                    // Stopped normally
                } catch (t: Throwable) {
                    if (isCurrent(gen)) {
                        listener?.onError("Помилка відтворення: ${t.message ?: t.javaClass.simpleName}")
                    }
                } finally {
                    try {
                        track.pause()
                        track.flush()
                        track.stop()
                        track.release()
                    } catch (_: Exception) {
                    }
                    if (currentTrack === track) currentTrack = null
                    if (sessionGen.get() == gen) {
                        playingFlag.set(false)
                        pausedFlag.set(false)
                        if (chapterEndedNaturally && !cancelFlag.get()) {
                            if (sleepAtChapterEnd.getAndSet(false)) {
                                listener?.onPlaybackStateChanged(false, false, "Зупинено наприкінці розділу")
                            } else {
                                listener?.onChapterFinished()
                            }
                        } else {
                            listener?.onPlaybackStateChanged(false, false, "Зупинено")
                        }
                    }
                }
            }, "HolosConsumer")

            if (!isCurrent(gen)) return@Thread
            producerThread = prod
            consumerThread = cons
            prod.start()
            cons.start()
        }, "HolosBootstrap")

        initThread = bootstrap
        bootstrap.start()
    }

    private fun isCurrent(gen: Long): Boolean = !cancelFlag.get() && sessionGen.get() == gen

    fun togglePause(): Boolean {
        if (!playingFlag.get()) return false
        val nowPaused = !pausedFlag.get()
        pausedFlag.set(nowPaused)
        if (nowPaused) {
            try { currentTrack?.pause() } catch (_: Exception) {}
            listener?.onPlaybackStateChanged(true, true, "Пауза")
        } else {
            try { currentTrack?.play() } catch (_: Exception) {}
            listener?.onPlaybackStateChanged(true, false, "Озвучення…")
        }
        return nowPaused
    }

    fun pauseIfPlaying() {
        if (playingFlag.get() && !pausedFlag.get()) {
            togglePause()
        }
    }

    fun stop() {
        sessionGen.incrementAndGet()
        cancelFlag.set(true)
        pausedFlag.set(false)
        try {
            currentTrack?.pause()
            currentTrack?.flush()
        } catch (_: Exception) {
        }
        initThread?.interrupt()
        producerThread?.interrupt()
        consumerThread?.interrupt()
        try { initThread?.join(200) } catch (_: Exception) {}
        try { producerThread?.join(350) } catch (_: Exception) {}
        try { consumerThread?.join(350) } catch (_: Exception) {}
        initThread = null
        producerThread = null
        consumerThread = null
        playingFlag.set(false)
    }
}
