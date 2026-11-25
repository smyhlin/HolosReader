package com.holos.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.holos.reader.epub.ReaderBlock
import com.holos.reader.ui.BionicTextHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
 * - Supports optional adaptive multi-voice narration ([AdaptiveVoiceRouter]) switching smoothly
 *   between Narrator, [СИСТЕМА], and Dialogue (`—`) voices within and across paragraphs.
 * - Consumer thread streams PCM float audio to a 4-second [AudioTrack] buffer with a 1.2 s jitter
 *   pre-buffer to eliminate underruns/stutters on mobile CPUs.
 * - Sample-accurate hardware clock sync ([AudioTrack.getPlaybackHeadPosition]) emits BOTH
 *   [Listener.onActiveBlockChanged] and [Listener.onActiveWordChanged] at the EXACT moment each
 *   paragraph & word reaches the speaker (1:1).
 */
class TtsPlaybackController(private val context: Context) {

    interface Listener {
        fun onPlaybackStateChanged(isPlaying: Boolean, isPaused: Boolean, statusText: String)
        fun onActiveBlockChanged(blockIndex: Int)
        fun onActiveWordChanged(blockIndex: Int, wordStart: Int, wordEnd: Int) {}
        fun onChapterFinished()
        fun onError(message: String)
    }

    private sealed class Packet {
        data class Audio(val blockIndex: Int, val samples: FloatArray) : Packet()
        data class BlockEnd(val blockIndex: Int, val speechFrames: Long) : Packet()
        data object ChapterEnd : Packet()
    }

    private data class BlockMarker(
        val blockIndex: Int,
        val startFrame: Long,
        val text: String,
    )

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
    private var syncThread: Thread? = null
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
            val defaultVoice = prefs.selectedVoice?.takeIf {
                it.startsWith(CustomVoiceLoader.CUSTOM_PREFIX) || it in engine.voices
            } ?: engine.voices.firstOrNull()
            val speed = prefs.speechSpeed

            val hasSpeakable = (startIdx until blocks.size).any { blocks[it].isSpeakable }
            if (!hasSpeakable) {
                playingFlag.set(false)
                if (isCurrent(gen)) {
                    listener?.onChapterFinished()
                }
                return@Thread
            }

            val queue = LinkedBlockingQueue<Packet>(48)
            val blockSpeechFrames = ConcurrentHashMap<Int, Long>()
            listener?.onPlaybackStateChanged(isPlaying = true, isPaused = false, statusText = "Синтез…")

            // Producer: pre-synthesizes current + upcoming paragraphs ahead of playback
            val prod = Thread({
                try {
                    for (i in startIdx until blocks.size) {
                        if (!isCurrent(gen)) return@Thread
                        val block = blocks[i]
                        if (!block.isSpeakable) continue

                        var speechFrames = 0L
                        if (prefs.adaptiveVoicesEnabled) {
                            val segments = AdaptiveVoiceRouter.segmentParagraph(block.text)
                            for ((segIdx, seg) in segments.withIndex()) {
                                if (!isCurrent(gen)) return@Thread
                                val segVoice = AdaptiveVoiceRouter.resolveVoice(seg.role, prefs, engine.voices)
                                HolosSafeSynthesizer.synthesizeParagraphStreaming(
                                    engine = engine,
                                    stressifier = stressifier,
                                    rawParagraph = seg.text,
                                    voice = segVoice,
                                    speed = speed,
                                    cancelled = cancelFlag,
                                    customVoicesDir = customDir,
                                ) { chunk ->
                                    speechFrames += chunk.size.toLong()
                                    while (isCurrent(gen)) {
                                        if (queue.offer(Packet.Audio(i, chunk), 100, TimeUnit.MILLISECONDS)) break
                                    }
                                }
                                if (segIdx < segments.lastIndex && isCurrent(gen)) {
                                    val rolePause = FloatArray((engine.sampleRate * 135) / 1000)
                                    speechFrames += rolePause.size.toLong()
                                    while (isCurrent(gen)) {
                                        if (queue.offer(Packet.Audio(i, rolePause), 100, TimeUnit.MILLISECONDS)) break
                                    }
                                }
                            }
                        } else {
                            HolosSafeSynthesizer.synthesizeParagraphStreaming(
                                engine = engine,
                                stressifier = stressifier,
                                rawParagraph = block.text,
                                voice = defaultVoice,
                                speed = speed,
                                cancelled = cancelFlag,
                                customVoicesDir = customDir,
                            ) { chunk ->
                                speechFrames += chunk.size.toLong()
                                while (isCurrent(gen)) {
                                    if (queue.offer(Packet.Audio(i, chunk), 100, TimeUnit.MILLISECONDS)) break
                                }
                            }
                        }
                        if (isCurrent(gen)) {
                            blockSpeechFrames[i] = speechFrames
                            // Brief 260 ms paragraph pause
                            val paraPause = FloatArray((engine.sampleRate * 260) / 1000)
                            while (isCurrent(gen)) {
                                if (queue.offer(Packet.Audio(i, paraPause), 100, TimeUnit.MILLISECONDS)) break
                            }
                            queue.offer(Packet.BlockEnd(i, speechFrames), 200, TimeUnit.MILLISECONDS)
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

            // Consumer: plays PCM float chunks and drives 1:1 hardware-clock paragraph & word highlighting
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
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                        .setBufferSizeInBytes(4 * engine.sampleRate * 4)
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

                val pendingMarkers = ConcurrentLinkedQueue<BlockMarker>()
                val syncRunning = AtomicBoolean(true)
                var lastEnqueuedBlock = -1
                var totalFramesWritten = 0L
                var chapterEndedNaturally = false

                // Hardware-clock 1:1 synchronizer: advances paragraph AND active word at exact speaker frame
                val sync = Thread({
                    var currentMarker: BlockMarker? = null
                    var currentTimeline: List<BionicTextHelper.WordSpan> = emptyList()
                    var lastWordStart = -1
                    var lastWordEnd = -1

                    while (syncRunning.get() && isCurrent(gen)) {
                        val playedFrames = try {
                            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        } catch (_: Exception) {
                            break
                        }
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            while (true) {
                                val head = pendingMarkers.peek() ?: break
                                if (playedFrames >= head.startFrame) {
                                    pendingMarkers.poll()
                                    if (currentMarker?.blockIndex != head.blockIndex) {
                                        currentMarker = head
                                        currentTimeline = BionicTextHelper.buildWordTimeline(head.text)
                                        lastWordStart = -1
                                        lastWordEnd = -1
                                        activeBlock.set(head.blockIndex)
                                        listener?.onActiveBlockChanged(head.blockIndex)
                                        listener?.onPlaybackStateChanged(true, false, "Озвучення…")
                                    }
                                } else {
                                    break
                                }
                            }

                            val cm = currentMarker
                            if (cm != null && currentTimeline.isNotEmpty()) {
                                val estFrames = ((cm.text.length / (14.5f * speed)) * engine.sampleRate).toLong()
                                    .coerceAtLeast(engine.sampleRate / 4L)
                                val durFrames = (blockSpeechFrames[cm.blockIndex] ?: estFrames).coerceAtLeast(1L)
                                val elapsed = (playedFrames - cm.startFrame).coerceAtLeast(0L)
                                val frac = (elapsed.toFloat() / durFrames.toFloat()).coerceIn(0f, 0.9999f)
                                val word = BionicTextHelper.findWordAt(currentTimeline, frac)
                                if (word != null && (word.start != lastWordStart || word.end != lastWordEnd)) {
                                    lastWordStart = word.start
                                    lastWordEnd = word.end
                                    listener?.onActiveWordChanged(cm.blockIndex, word.start, word.end)
                                }
                            }
                        }
                        try {
                            Thread.sleep(25)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }, "HolosSync")
                syncThread = sync
                sync.start()

                val prebufferTargetSamples = (engine.sampleRate * 12) / 10
                val prebuffer = ArrayList<Packet.Audio>(8)
                var prebufferSamples = 0
                var playbackStarted = false

                fun writeAudioPacket(ap: Packet.Audio) {
                    if (ap.blockIndex != lastEnqueuedBlock) {
                        lastEnqueuedBlock = ap.blockIndex
                        val txt = blocks.getOrNull(ap.blockIndex)?.text.orEmpty()
                        pendingMarkers.add(BlockMarker(ap.blockIndex, totalFramesWritten, txt))
                    }
                    val written = track.write(ap.samples, 0, ap.samples.size, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) {
                        totalFramesWritten += written.toLong()
                    }
                }

                fun flushPrebuffer() {
                    if (prebuffer.isEmpty()) return
                    for (ap in prebuffer) {
                        if (!isCurrent(gen)) return
                        writeAudioPacket(ap)
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play()
                        }
                    }
                    prebuffer.clear()
                    prebufferSamples = 0
                    playbackStarted = true
                }

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
                                if (!playbackStarted) {
                                    prebuffer.add(packet)
                                    prebufferSamples += packet.samples.size
                                    if (prebufferSamples >= prebufferTargetSamples) {
                                        flushPrebuffer()
                                    }
                                } else {
                                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                        track.play()
                                    }
                                    writeAudioPacket(packet)
                                }
                            }
                            is Packet.BlockEnd -> {
                                blockSpeechFrames[packet.blockIndex] = packet.speechFrames
                                if (!playbackStarted) flushPrebuffer()
                            }
                            is Packet.ChapterEnd -> {
                                if (!playbackStarted) flushPrebuffer()
                                chapterEndedNaturally = true
                                break
                            }
                        }
                    }

                    // Drain the hardware AudioTrack buffer completely so the final words of the chapter are never cut off
                    if (chapterEndedNaturally && isCurrent(gen) && totalFramesWritten > 0L) {
                        var lastPos = -1L
                        var stalledMs = 0
                        val targetEnd = (totalFramesWritten - (engine.sampleRate / 25)).coerceAtLeast(0L)
                        while (isCurrent(gen)) {
                            while (pausedFlag.get() && isCurrent(gen)) {
                                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                                Thread.sleep(40)
                            }
                            if (!isCurrent(gen)) break
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                            val pos = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            if (pos >= targetEnd) break
                            if (pos == lastPos) {
                                stalledMs += 40
                                if (stalledMs >= 1500) break
                            } else {
                                lastPos = pos
                                stalledMs = 0
                            }
                            Thread.sleep(40)
                        }
                    }
                } catch (_: InterruptedException) {
                    // Stopped normally
                } catch (t: Throwable) {
                    if (isCurrent(gen)) {
                        listener?.onError("Помилка відтворення: ${t.message ?: t.javaClass.simpleName}")
                    }
                } finally {
                    syncRunning.set(false)
                    sync.interrupt()
                    try { sync.join(150) } catch (_: Exception) {}
                    if (syncThread === sync) syncThread = null
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
            prod.priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
            cons.priority = (Thread.MAX_PRIORITY - 1).coerceAtLeast(Thread.NORM_PRIORITY)
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
        syncThread?.interrupt()
        try { initThread?.join(200) } catch (_: Exception) {}
        try { producerThread?.join(350) } catch (_: Exception) {}
        try { consumerThread?.join(350) } catch (_: Exception) {}
        try { syncThread?.join(150) } catch (_: Exception) {}
        initThread = null
        producerThread = null
        consumerThread = null
        syncThread = null
        playingFlag.set(false)
    }
}
