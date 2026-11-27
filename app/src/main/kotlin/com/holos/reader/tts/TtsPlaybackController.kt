package com.holos.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.holos.reader.epub.ReaderBlock
import com.holos.reader.ui.AppStrings
import com.holos.reader.ui.BionicTextHelper
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Look-ahead Producer–Consumer TTS playback controller for EPUB 3 chapters (v5.1):
 *
 * - Stores `applicationContext` only (zero Activity context leaks).
 * - Per-session [AtomicBoolean] cancellation token eliminates zombie synthesis across rapid seeks.
 * - Non-blocking [stop] never stalls the UI thread.
 * - Synchronized `trackLock` + full [AudioTrack.write] partial/error handling.
 * - Supports real pre-rendered offline chapter WAV playback (zero ONNX CPU usage) with `.idx` sync.
 * - Supports true A–B block loop ([setAbLoop] / [clearAbLoop]).
 */
class TtsPlaybackController(context: Context) : AutoCloseable {

    interface Listener {
        fun onPlaybackStateChanged(isPlaying: Boolean, isPaused: Boolean, statusText: String)
        fun onActiveBlockChanged(blockIndex: Int)
        fun onActiveWordChanged(blockIndex: Int, wordStart: Int, wordEnd: Int) {}
        fun onChapterFinished(stopForSleepTimer: Boolean = false)
        fun onError(message: String)
        /** Pause lasted long enough that the current paragraph should be spoken again. */
        fun onLongResumeRewind(generation: Long) {}
    }

    data class OfflineBlockIndex(
        val blockIndex: Int,
        val startSample: Long,
        val speechSamples: Long,
    )

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

    private val appContext: Context = context.applicationContext
    private val trackLock = Any()

    @Volatile
    private var activeCancel: AtomicBoolean? = null
    private val pausedFlag = AtomicBoolean(false)
    private val pauseLock = ReentrantLock()
    private val pauseChanged = pauseLock.newCondition()
    private val listeningPrefs = ReaderPreferences(appContext)
    @Volatile private var pausedAtElapsedMs = 0L
    // Gain and the first-play flag are protected by trackLock.
    private var trackHasPlayed = false
    private var edgeFrom = 1f
    private var edgeTo = 1f
    private var edgeGain = 1f
    private var edgeStartedAt = 0L
    private var lastAppliedGain = -1f
    private val playingFlag = AtomicBoolean(false)
    private val sleepTracker = SleepDeadlineTracker()
    private val sleepAtChapterEnd = AtomicBoolean(false)
    private val activeBlock = AtomicInteger(-1)
    private val sessionGen = AtomicLong(0L)

    // True A-B loop bounds (inclusive block indices; -1 = disabled)
    @Volatile
    var abLoopStart: Int = -1
        private set
    @Volatile
    var abLoopEnd: Int = -1
        private set

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

    fun setAbLoop(startBlock: Int, endBlock: Int) {
        if (startBlock >= 0 && endBlock >= startBlock) {
            abLoopStart = startBlock
            abLoopEnd = endBlock
        } else {
            clearAbLoop()
        }
    }

    fun clearAbLoop() {
        abLoopStart = -1
        abLoopEnd = -1
    }

    private val sleepScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HolosSleepDeadline").apply { isDaemon = true }
    }
    @Volatile
    private var sleepFuture: java.util.concurrent.ScheduledFuture<*>? = null
    internal val focusGate = PlaybackFocusGate()
    val currentSessionGeneration: Long get() = sessionGen.get()

    fun onAudioFocusGranted(generation: Long) {
        focusGate.onFocusGranted(generation)
    }

    fun onAudioFocusDenied(generation: Long) {
        if (focusGate.onFocusDenied(generation) && sessionGen.get() == generation) {
            activeCancel?.set(true)
            playingFlag.set(false)
            pausedFlag.set(false)
            signalPauseWaiters()
            listener?.onPlaybackStateChanged(
                false,
                false,
                AppStrings.t(appContext, "Не вдалося отримати аудіофокус", "Audio focus denied"),
            )
        }
    }

    private fun cancelScheduledSleep() {
        sleepFuture?.cancel(false)
        sleepFuture = null
    }

    private fun scheduleSleepDeadline(gen: Long, sessionCancel: AtomicBoolean) {
        cancelScheduledSleep()
        if (!sleepTracker.hasActiveDeadline()) return
        val delayMs = sleepTracker.remainingMs()
        sleepFuture = sleepScheduler.schedule({
            if (sessionGen.get() == gen && !sessionCancel.get()) {
                handleSleepExpiry(sessionCancel)
            }
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun setSleepTimerMinutes(minutes: Int) {
        sleepAtChapterEnd.set(false)
        sleepTracker.setMinutes(minutes)
        val cancel = activeCancel
        if (minutes > 0 && cancel != null && playingFlag.get()) {
            scheduleSleepDeadline(sessionGen.get(), cancel)
        } else {
            cancelScheduledSleep()
        }
    }

    fun setSleepAtChapterEnd(enabled: Boolean) {
        sleepTracker.clear()
        cancelScheduledSleep()
        sleepAtChapterEnd.set(enabled)
    }

    private fun handleSleepExpiry(sessionCancel: AtomicBoolean): Boolean {
        if (!sleepTracker.consumeIfExpired()) return false
        cancelScheduledSleep()
        sessionCancel.set(true)
        playingFlag.set(false)
        pausedFlag.set(false)
        signalPauseWaiters()
        synchronized(trackLock) {
            try {
                currentTrack?.pause()
                currentTrack?.flush()
            } catch (_: Exception) {}
        }
        listener?.onPlaybackStateChanged(false, false, AppStrings.t(appContext, "Таймер сну завершено", "Sleep timer finished"))
        return true
    }

    fun startFrom(
        blocks: List<ReaderBlock>,
        startBlockIndex: Int,
        offlineWavFile: File? = null,
        requireFocusGrant: Boolean = false,
    ): Long {
        stop()
        if (blocks.isEmpty()) return 0L
        if (sleepTracker.consumeIfExpired()) {
            listener?.onPlaybackStateChanged(false, false, AppStrings.t(appContext, "Таймер сну завершено", "Sleep timer finished"))
            return 0L
        }

        val gen = sessionGen.incrementAndGet()
        val sessionCancel = AtomicBoolean(false)
        activeCancel = sessionCancel
        focusGate.beginSession(gen, requireFocusGrant)
        pausedFlag.set(false)
        playingFlag.set(true)
        scheduleSleepDeadline(gen, sessionCancel)

        val startIdx = startBlockIndex.coerceIn(0, blocks.lastIndex)
        listener?.onPlaybackStateChanged(
            isPlaying = true,
            isPaused = false,
            statusText = AppStrings.t(appContext, "Підготовка голосу…", "Preparing voice…"),
        )

        val bootstrap = Thread({
            if (!isCurrent(gen, sessionCancel) || handleSleepExpiry(sessionCancel)) {
                return@Thread
            }
            val prefs = ReaderPreferences(appContext)
            val speed = prefs.speechSpeed

            val spokenIds = NarrationPlan.spokenBlockIds(blocks)
            if (spokenIds.isEmpty() || spokenIds.none { it >= startIdx }) {
                if (isCurrent(gen, sessionCancel)) {
                    playingFlag.set(false)
                    listener?.onPlaybackStateChanged(false, false, AppStrings.t(appContext, "Немає тексту для озвучення", "No text to read"))
                }
                return@Thread
            }
            if (offlineWavFile != null) {
                val readLease = OfflineStorageManager.acquireRead(offlineWavFile)
                if (readLease != null) {
                    var handedOff = false
                    try {
                        val validated = OfflineAudioCache.validate(offlineWavFile, spokenIds)
                        if (validated != null && validated.entries.any { it.blockIndex >= startIdx }) {
                            startOfflineWavPipeline(gen, sessionCancel, blocks, startIdx, speed, validated, readLease)
                            handedOff = true
                            return@Thread
                        }
                    } finally {
                        if (!handedOff) readLease.close()
                    }
                }
            }

            // Live ONNX streaming pipeline
            val engineLease = try {
                HolosEngineHolder.acquireEngineLease(appContext)
            } catch (t: Throwable) {
                if (isCurrent(gen, sessionCancel)) {
                    playingFlag.set(false)
                    listener?.onError(
                        AppStrings.t(appContext, "Помилка завантаження моделі: ", "Model load error: ") +
                            (t.message ?: t.javaClass.simpleName)
                    )
                }
                return@Thread
            }
            if (!isCurrent(gen, sessionCancel)) {
                engineLease?.close()
                return@Thread
            }
            val engine = engineLease?.engine
            if (engine == null) {
                playingFlag.set(false)
                listener?.onError(
                    AppStrings.t(
                        appContext,
                        "Спочатку завантажте модель HolosTTS (~320 МБ) на головному екрані.",
                        "Please download the HolosTTS model (~320 MB) on the library screen first.",
                    )
                )
                return@Thread
            }

            val stressifier = try {
                HolosEngineHolder.getStressifier(appContext)
            } catch (t: Throwable) {
                if (isCurrent(gen, sessionCancel)) {
                    playingFlag.set(false)
                    listener?.onError(
                        AppStrings.t(appContext, "Помилка ініціалізації наголосів: ", "Stress init error: ") +
                            (t.message ?: t.javaClass.simpleName)
                    )
                }
                engineLease.close()
                return@Thread
            }
            if (!isCurrent(gen, sessionCancel)) {
                engineLease.close()
                return@Thread
            }

            val voicePlan = AdaptiveVoicePlan.capture(appContext, prefs)
            val roleEngines = try {
                AdaptiveEngineSet.acquire(appContext, engine, voicePlan)
            } catch (t: Throwable) {
                engineLease.close()
                if (isCurrent(gen, sessionCancel)) {
                    playingFlag.set(false)
                    listener?.onError(t.message ?: t.javaClass.simpleName)
                }
                return@Thread
            }
            val customDir = HolosEngineHolder.getCustomVoicesDir(appContext)
            val flashSrLease = if (roleEngines.hasHolos) HolosEngineHolder.acquireFlashSrLease(appContext) else null
            val emotionLease = HolosEngineHolder.acquireEmotionLease(appContext)
            val flashSr = flashSrLease?.engine
            val emotionClassifier = emotionLease?.engine
            val releaseAllLeases = {
                emotionLease?.close()
                flashSrLease?.close()
                roleEngines.close()
                engineLease.close()
            }
            val outputSampleRate = roleEngines.outputRate(flashSr != null)
            val defaultVoice = roleEngines.route(VoiceRole.NARRATOR).voice

            val hasSpeakable = (startIdx until blocks.size).any { blocks[it].isSpeakable }
            if (!hasSpeakable) {
                releaseAllLeases()
                playingFlag.set(false)
                if (isCurrent(gen, sessionCancel)) {
                    listener?.onChapterFinished()
                }
                return@Thread
            }

            val queue = LinkedBlockingQueue<Packet>(48)
            val blockSpeechFrames = ConcurrentHashMap<Int, Long>()
            listener?.onPlaybackStateChanged(
                isPlaying = true,
                isPaused = false,
                statusText = AppStrings.t(appContext, "Синтез…", "Synthesizing…"),
            )

            // Producer: pre-synthesizes current + upcoming paragraphs ahead of playback (with A-B loop & per-block isolation)
            val prod = Thread({
                try {
                    var i = startIdx
                    var totalProducedFrames = 0L
                    var loopIterationsWithoutSpeech = 0
                    while (i < blocks.size && isCurrent(gen, sessionCancel)) {
                        val block = blocks[i]
                        if (block.isSpeakable) {
                            var speechFrames = 0L
                            try {
                                if (voicePlan.enabled) {
                                    val segments = AdaptiveVoiceRouter.segmentParagraph(block.text)
                                    for ((segIdx, seg) in segments.withIndex()) {
                                        if (!isCurrent(gen, sessionCancel)) return@Thread
                                        val route = roleEngines.route(seg.role)
                                        HolosSafeSynthesizer.synthesizeParagraphStreaming(
                                            engine = route.engine,
                                            stressifier = stressifier,
                                            rawParagraph = seg.text,
                                            voice = route.voice,
                                            speed = speed,
                                            cancelled = sessionCancel,
                                            customVoicesDir = customDir,
                                            supertonicEnglishPronunciation = prefs.supertonicEnglishPronunciation,
                                            supertonicSteps = prefs.supertonicSteps,
                                            flashSr = flashSr,
                                            emotionClassifier = emotionClassifier,
                                        ) { chunk ->
                                            val output = roleEngines.toOutput(chunk, route, flashSr != null, outputSampleRate)
                                            speechFrames += output.size.toLong()
                                            offerBlocking(queue, Packet.Audio(i, output), gen, sessionCancel)
                                        }
                                        if (segIdx < segments.lastIndex && isCurrent(gen, sessionCancel)) {
                                            val rolePause = FloatArray((outputSampleRate * 135) / 1000)
                                            speechFrames += rolePause.size.toLong()
                                            offerBlocking(queue, Packet.Audio(i, rolePause), gen, sessionCancel)
                                        }
                                    }
                                } else {
                                    HolosSafeSynthesizer.synthesizeParagraphStreaming(
                                        engine = engine,
                                        stressifier = stressifier,
                                        rawParagraph = block.text,
                                        voice = defaultVoice,
                                        speed = speed,
                                        cancelled = sessionCancel,
                                        customVoicesDir = customDir,
                                        supertonicEnglishPronunciation = prefs.supertonicEnglishPronunciation,
                                        supertonicSteps = prefs.supertonicSteps,
                                        flashSr = flashSr,
                                        emotionClassifier = emotionClassifier,
                                    ) { chunk ->
                                        speechFrames += chunk.size.toLong()
                                        offerBlocking(queue, Packet.Audio(i, chunk), gen, sessionCancel)
                                    }
                                }
                            } catch (blockErr: Throwable) {
                                if (!isCurrent(gen, sessionCancel)) return@Thread
                                Log.w(TAG, "Skipping failed block #$i: ${blockErr.message}", blockErr)
                            }

                            if (isCurrent(gen, sessionCancel) && speechFrames > 0L) {
                                totalProducedFrames += speechFrames
                                loopIterationsWithoutSpeech = 0
                                blockSpeechFrames[i] = speechFrames
                                val paraPause = FloatArray((outputSampleRate * 260) / 1000)
                                offerBlocking(queue, Packet.Audio(i, paraPause), gen, sessionCancel)
                                offerBlocking(queue, Packet.BlockEnd(i, speechFrames), gen, sessionCancel)
                            }
                        }

                        // True A-B loop check
                        val loopA = abLoopStart
                        val loopB = abLoopEnd
                        if (loopA >= 0 && loopB >= loopA && i >= loopB) {
                            val hasSpeakableInLoop = (loopA.coerceIn(0, blocks.lastIndex)..loopB.coerceIn(0, blocks.lastIndex))
                                .any { blocks[it].isSpeakable }
                            if (hasSpeakableInLoop && loopIterationsWithoutSpeech < 2) {
                                loopIterationsWithoutSpeech++
                                i = loopA.coerceIn(0, blocks.lastIndex)
                            } else {
                                clearAbLoop()
                                i++
                            }
                        } else {
                            i++
                        }
                    }
                    if (isCurrent(gen, sessionCancel)) {
                        if (totalProducedFrames > 0L) {
                            offerBlocking(queue, Packet.ChapterEnd, gen, sessionCancel)
                        } else {
                            sessionCancel.set(true)
                            listener?.onError(
                                AppStrings.t(appContext, "Не вдалося синтезувати жодного абзацу розділу", "Failed to synthesize any paragraph in this chapter")
                            )
                        }
                    }
                } catch (t: Throwable) {
                    if (isCurrent(gen, sessionCancel)) {
                        sessionCancel.set(true)
                        listener?.onError(
                            AppStrings.t(appContext, "Помилка синтезу: ", "Synthesis error: ") +
                                (t.message ?: t.javaClass.simpleName)
                        )
                    }
                } finally {
                    releaseAllLeases()
                }
            }, "HolosProducer")

            launchConsumer(
                gen = gen,
                sessionCancel = sessionCancel,
                blocks = blocks,
                sampleRate = outputSampleRate,
                speed = speed,
                queue = queue,
                blockSpeechFrames = blockSpeechFrames,
                producer = prod,
                prebufferMs = 1200,
                playingLabel = AppStrings.t(appContext, "Озвучення…", "Playing…"),
                onProducerNotStarted = { releaseAllLeases() },
            )
        }, "HolosBootstrap")

        initThread = bootstrap
        bootstrap.start()
        return gen
    }

    /**
     * Streams a pre-rendered 16-bit PCM WAV chapter directly from disk without invoking ONNX!
     */
    private fun startOfflineWavPipeline(
        gen: Long,
        sessionCancel: AtomicBoolean,
        blocks: List<ReaderBlock>,
        startIdx: Int,
        speed: Float,
        validated: OfflineAudioCache.ValidatedChapter,
        readLease: OfflineStorageManager.Lease,
    ) {
        val wavFile = validated.wavFile
        val index = validated.entries
        val sampleRate = validated.sampleRate
        val queue = LinkedBlockingQueue<Packet>(48)
        val blockSpeechFrames = ConcurrentHashMap<Int, Long>()
        for (entry in index) {
            blockSpeechFrames[entry.blockIndex] = entry.speechSamples
        }
        val firstEntryIdx = index.indexOfFirst { it.blockIndex >= startIdx }.coerceAtLeast(0)

        listener?.onPlaybackStateChanged(
            isPlaying = true,
            isPaused = false,
            statusText = AppStrings.t(appContext, "Офлайн-аудіо…", "Offline audio…"),
        )

        val prod = Thread({
            try {
                RandomAccessFile(wavFile, "r").use { raf ->
                    val dataBytesTotal = (raf.length() - 44L).coerceAtLeast(0L)
                    val totalSamples = dataBytesTotal / 2L
                    val bufBytes = ByteArray(16384) // 8,192 16-bit samples per chunk (~340 ms)

                    var entryPos = firstEntryIdx
                    while (entryPos < index.size && isCurrent(gen, sessionCancel)) {
                        val entry = index[entryPos]
                        val nextStart = if (entryPos + 1 < index.size) index[entryPos + 1].startSample else totalSamples
                        val samplesToRead = (nextStart - entry.startSample).coerceAtLeast(0L)

                        raf.seek(44L + entry.startSample * 2L)
                        var remaining = samplesToRead
                        while (remaining > 0L && isCurrent(gen, sessionCancel)) {
                            val toReadSamples = minOf(remaining, (bufBytes.size / 2).toLong()).toInt()
                            val nBytes = raf.read(bufBytes, 0, toReadSamples * 2)
                            if (nBytes <= 0 || (nBytes and 1) != 0) {
                                throw java.io.EOFException("Premature EOF in offline WAV at block ${entry.blockIndex}")
                            }
                            val actualSamples = nBytes / 2
                            val floats = FloatArray(actualSamples)
                            var bi = 0
                            for (s in 0 until actualSamples) {
                                val lo = bufBytes[bi].toInt() and 0xFF
                                val hi = bufBytes[bi + 1].toInt()
                                val pcm16 = (hi shl 8) or lo
                                floats[s] = pcm16 / 32768f
                                bi += 2
                            }
                            offerBlocking(queue, Packet.Audio(entry.blockIndex, floats), gen, sessionCancel)
                            remaining -= actualSamples
                        }
                        if (isCurrent(gen, sessionCancel)) {
                            offerBlocking(queue, Packet.BlockEnd(entry.blockIndex, entry.speechSamples), gen, sessionCancel)
                        }

                        // True A-B loop check for offline WAV too!
                        val loopA = abLoopStart
                        val loopB = abLoopEnd
                        if (loopA >= 0 && loopB >= loopA && entry.blockIndex >= loopB) {
                            val targetPos = index.indexOfFirst { it.blockIndex >= loopA }
                            entryPos = if (targetPos >= 0) targetPos else entryPos + 1
                        } else {
                            entryPos++
                        }
                    }
                    if (isCurrent(gen, sessionCancel)) {
                        offerBlocking(queue, Packet.ChapterEnd, gen, sessionCancel)
                    }
                }
            } catch (t: Throwable) {
                if (isCurrent(gen, sessionCancel)) {
                    sessionCancel.set(true)
                    listener?.onError(
                        AppStrings.t(appContext, "Помилка офлайн-аудіо: ", "Offline audio error: ") +
                            (t.message ?: t.javaClass.simpleName)
                    )
                }
            } finally {
                readLease.close()
            }
        }, "HolosOfflineProducer")

        launchConsumer(
            gen = gen,
            sessionCancel = sessionCancel,
            blocks = blocks,
            sampleRate = sampleRate,
            speed = speed,
            queue = queue,
            blockSpeechFrames = blockSpeechFrames,
            producer = prod,
            prebufferMs = 200,
            playingLabel = AppStrings.t(appContext, "Офлайн-аудіо", "Offline audio"),
            onProducerNotStarted = readLease::close,
        )
    }

    private fun launchConsumer(
        gen: Long,
        sessionCancel: AtomicBoolean,
        blocks: List<ReaderBlock>,
        sampleRate: Int,
        speed: Float,
        queue: LinkedBlockingQueue<Packet>,
        blockSpeechFrames: ConcurrentHashMap<Int, Long>,
        producer: Thread,
        prebufferMs: Int,
        playingLabel: String,
        onProducerNotStarted: (() -> Unit)? = null,
    ) {
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
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                    .setBufferSizeInBytes(4 * sampleRate * 4)
                    .build()
            } catch (t: Throwable) {
                if (isCurrent(gen, sessionCancel)) {
                    playingFlag.set(false)
                    sessionCancel.set(true)
                    listener?.onError(
                        AppStrings.t(appContext, "Помилка аудіовиходу: ", "Audio output error: ") +
                            (t.message ?: t.javaClass.simpleName)
                    )
                }
                return@Thread
            }
            synchronized(trackLock) {
                currentTrack = track
                trackHasPlayed = false
                lastAppliedGain = -1f
                edgeGain = 1f
                edgeFrom = 1f
                edgeTo = 1f
            }

            val pendingMarkers = ConcurrentLinkedQueue<BlockMarker>()
            val syncRunning = AtomicBoolean(true)
            var lastEnqueuedBlock = -1
            var totalFramesWritten = 0L
            var chapterEndedNaturally = false

            val sync = Thread({
                var currentMarker: BlockMarker? = null
                var currentTimeline: List<BionicTextHelper.WordSpan> = emptyList()
                var lastWordStart = -1
                var lastWordEnd = -1

                while (syncRunning.get() && isCurrent(gen, sessionCancel)) {
                    var playedFrames = -1L
                    var isTrackPlaying = false
                    synchronized(trackLock) {
                        if (currentTrack === track) {
                            playedFrames = try {
                                track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            } catch (_: Exception) {
                                -1L
                            }
                            isTrackPlaying = try {
                                track.playState == AudioTrack.PLAYSTATE_PLAYING
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }
                    if (playedFrames < 0L) break
                    applyTrackVolume(track)

                    if (isTrackPlaying) {
                        while (true) {
                            val head = pendingMarkers.peek() ?: break
                            if (playedFrames >= head.startFrame) {
                                pendingMarkers.poll()
                                currentMarker = head
                                currentTimeline = BionicTextHelper.buildWordTimeline(head.text)
                                lastWordStart = -1
                                lastWordEnd = -1
                                activeBlock.set(head.blockIndex)
                                listener?.onActiveBlockChanged(head.blockIndex)
                                listener?.onPlaybackStateChanged(true, false, playingLabel)
                            } else {
                                break
                            }
                        }

                        val cm = currentMarker
                        if (cm != null && currentTimeline.isNotEmpty()) {
                            val estFrames = ((cm.text.length / (14.5f * speed)) * sampleRate).toLong()
                                .coerceAtLeast(sampleRate / 4L)
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

            val prebufferTargetSamples = (sampleRate * prebufferMs) / 1000
            val prebuffer = ArrayList<Packet.Audio>(8)
            var prebufferSamples = 0
            var playbackStarted = false

            fun ensureTrackStarted(): Boolean {
                if (!focusGate.awaitGrant(gen, sessionCancel)) {
                    if (isCurrent(gen, sessionCancel)) {
                        sessionCancel.set(true)
                        playingFlag.set(false)
                        pausedFlag.set(false)
                        listener?.onPlaybackStateChanged(
                            false,
                            false,
                            AppStrings.t(appContext, "Не вдалося отримати аудіофокус", "Audio focus denied"),
                        )
                    }
                    return false
                }
                return synchronized(trackLock) {
                    if (currentTrack === track && isCurrent(gen, sessionCancel) &&
                        track.playState != AudioTrack.PLAYSTATE_PLAYING && !pausedFlag.get()
                    ) {
                        if (!trackHasPlayed) {
                            edgeGain = if (listeningPrefs.audioEdgeFadeEnabled) 0f else 1f
                            beginEdgeRamp(1f, listeningPrefs.audioEdgeFadeEnabled)
                            trackHasPlayed = true
                        }
                        applyTrackVolume(track)
                        track.play()
                    }
                    currentTrack === track && isCurrent(gen, sessionCancel) &&
                        track.playState == AudioTrack.PLAYSTATE_PLAYING
                }
            }

            fun writeAudioPacket(ap: Packet.Audio): Boolean {
                if (ap.blockIndex != lastEnqueuedBlock) {
                    lastEnqueuedBlock = ap.blockIndex
                    val txt = blocks.getOrNull(ap.blockIndex)?.text.orEmpty()
                    pendingMarkers.add(BlockMarker(ap.blockIndex, totalFramesWritten, txt))
                }
                var offset = 0
                val total = ap.samples.size
                while (offset < total && isCurrent(gen, sessionCancel)) {
                    if (handleSleepExpiry(sessionCancel)) return false
                    if (!awaitUnpaused(gen, sessionCancel)) return false
                    if (!isCurrent(gen, sessionCancel)) return false
                    val w = try {
                        if (!ensureTrackStarted()) continue
                        track.write(ap.samples, offset, total - offset, AudioTrack.WRITE_BLOCKING)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioTrack.write failed", e)
                        return false
                    }
                    if (w < 0) {
                        Log.w(TAG, "AudioTrack.write returned error code $w")
                        return false
                    }
                    if (w == 0) {
                        Thread.sleep(10)
                    } else {
                        offset += w
                        totalFramesWritten += w.toLong()
                    }
                }
                return isCurrent(gen, sessionCancel)
            }

            fun flushPrebuffer(): Boolean {
                if (prebuffer.isEmpty()) return true
                // Start AudioTrack before any blocking write exceeding stopped buffer capacity (H13)
                if (!awaitUnpaused(gen, sessionCancel)) return false
                for (ap in prebuffer) {
                    if (!isCurrent(gen, sessionCancel)) return false
                    if (!writeAudioPacket(ap)) {
                        sessionCancel.set(true)
                        listener?.onError(AppStrings.t(appContext, "Помилка аудіовиводу", "Audio output error"))
                        return false
                    }
                }
                prebuffer.clear()
                prebufferSamples = 0
                playbackStarted = true
                return true
            }

            try {
                while (isCurrent(gen, sessionCancel)) {
                    if (handleSleepExpiry(sessionCancel)) break

                    while (pausedFlag.get() && isCurrent(gen, sessionCancel)) {
                        if (handleSleepExpiry(sessionCancel)) break
                        if (!awaitUnpaused(gen, sessionCancel)) break
                    }
                    if (!isCurrent(gen, sessionCancel)) break

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
                                if (!writeAudioPacket(packet)) break
                            }
                        }
                        is Packet.BlockEnd -> {
                            blockSpeechFrames[packet.blockIndex] = packet.speechFrames
                            lastEnqueuedBlock = -1 // allow re-emitting marker on A-B single-block loop
                            if (!playbackStarted) flushPrebuffer()
                        }
                        is Packet.ChapterEnd -> {
                            if (!playbackStarted) flushPrebuffer()
                            chapterEndedNaturally = true
                            break
                        }
                    }
                }

                // Drain hardware AudioTrack buffer so final words of chapter are never clipped
                if (chapterEndedNaturally && isCurrent(gen, sessionCancel) && totalFramesWritten > 0L) {
                    var lastPos = -1L
                    var stalledMs = 0
                    val targetEnd = (totalFramesWritten - (sampleRate / 25)).coerceAtLeast(0L)
                    while (isCurrent(gen, sessionCancel)) {
                        if (handleSleepExpiry(sessionCancel)) {
                            chapterEndedNaturally = false
                            break
                        }
                        while (pausedFlag.get() && isCurrent(gen, sessionCancel)) {
                            if (handleSleepExpiry(sessionCancel)) {
                                chapterEndedNaturally = false
                                break
                            }
                            if (!awaitUnpaused(gen, sessionCancel)) {
                                chapterEndedNaturally = false
                                break
                            }
                        }
                        if (!isCurrent(gen, sessionCancel)) break
                        ensureTrackStarted()
                        val pos = synchronized(trackLock) {
                            if (currentTrack !== track) -1L else (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                        }
                        if (pos < 0L || pos >= targetEnd) break
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
                if (isCurrent(gen, sessionCancel)) {
                    listener?.onError(
                        AppStrings.t(appContext, "Помилка відтворення: ", "Playback error: ") +
                            (t.message ?: t.javaClass.simpleName)
                    )
                }
            } finally {
                syncRunning.set(false)
                sync.interrupt()
                try { sync.join(120) } catch (_: Exception) {}
                if (syncThread === sync) syncThread = null
                synchronized(trackLock) {
                    if (currentTrack === track) currentTrack = null
                    try {
                        track.pause()
                        track.flush()
                        track.stop()
                        track.release()
                    } catch (_: Exception) {
                    }
                }
                if (sessionGen.get() == gen) {
                    playingFlag.set(false)
                    pausedFlag.set(false)
                    if (chapterEndedNaturally && !sessionCancel.get()) {
                        val stopForSleep = sleepAtChapterEnd.getAndSet(false)
                        if (stopForSleep) {
                            listener?.onPlaybackStateChanged(
                                false,
                                false,
                                AppStrings.t(appContext, "Зупинено наприкінці розділу", "Stopped at end of chapter"),
                            )
                        }
                        listener?.onChapterFinished(stopForSleepTimer = stopForSleep)
                    } else {
                        listener?.onPlaybackStateChanged(
                            false,
                            false,
                            AppStrings.t(appContext, "Зупинено", "Stopped"),
                        )
                    }
                }
            }
        }, "HolosConsumer")

        if (!isCurrent(gen, sessionCancel)) {
            onProducerNotStarted?.invoke()
            return
        }
        producer.priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
        cons.priority = (Thread.MAX_PRIORITY - 1).coerceAtLeast(Thread.NORM_PRIORITY)
        producerThread = producer
        consumerThread = cons
        producer.start()
        cons.start()
    }

    private fun offerBlocking(
        queue: LinkedBlockingQueue<Packet>,
        packet: Packet,
        gen: Long,
        sessionCancel: AtomicBoolean,
    ) {
        while (isCurrent(gen, sessionCancel)) {
            try {
                if (queue.offer(packet, 100, TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun isCurrent(gen: Long, sessionCancel: AtomicBoolean): Boolean =
        !sessionCancel.get() && sessionGen.get() == gen


    private fun signalPauseWaiters() {
        pauseLock.withLock { pauseChanged.signalAll() }
    }

    private fun shouldSmartRewind(): Boolean =
        listeningPrefs.smartResumeEnabled && pausedAtElapsedMs > 0L &&
            SystemClock.elapsedRealtime() - pausedAtElapsedMs >= PlaybackGain.SMART_RESUME_MS

    // Called under trackLock.
    private fun beginEdgeRamp(to: Float, fade: Boolean) {
        edgeFrom = if (fade) edgeGain else to
        edgeTo = to
        edgeStartedAt = SystemClock.elapsedRealtime()
        if (!fade) edgeGain = to
    }

    private fun applyTrackVolume(track: AudioTrack) {
        synchronized(trackLock) {
            if (currentTrack !== track) return
            val fade = listeningPrefs.audioEdgeFadeEnabled
            edgeGain = if (fade) PlaybackGain.edge(edgeFrom, edgeTo, SystemClock.elapsedRealtime() - edgeStartedAt) else edgeTo
            val sleepGain = PlaybackGain.sleepFade(
                sleepTracker.hasActiveDeadline(), sleepTracker.remainingMs(), listeningPrefs.sleepFadeEnabled,
            )
            val gain = PlaybackGain.combined(edgeGain, sleepGain)
            if (gain != lastAppliedGain) {
                lastAppliedGain = gain
                try { track.setVolume(gain) } catch (_: Exception) {}
            }
            if (pausedFlag.get() && edgeGain <= 0.02f && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                try { track.pause() } catch (_: Exception) {}
            }
        }
    }

    private fun awaitUnpaused(gen: Long, sessionCancel: AtomicBoolean): Boolean {
        while (pausedFlag.get() && isCurrent(gen, sessionCancel)) {
            if (handleSleepExpiry(sessionCancel)) return false
            pauseLock.withLock {
                if (pausedFlag.get() && isCurrent(gen, sessionCancel)) {
                    try {
                        pauseChanged.await(400, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
            }
        }
        return isCurrent(gen, sessionCancel)
    }

    fun resumeIfPaused(): Boolean {
        if (!playingFlag.get() || !pausedFlag.get()) return false
        return !togglePause()
    }

    fun togglePause(immediate: Boolean = false): Boolean {
        if (!playingFlag.get()) return false
        val nowPaused = !pausedFlag.get()
        if (!nowPaused && sleepTracker.consumeIfExpired()) {
            activeCancel?.set(true)
            stop()
            listener?.onPlaybackStateChanged(false, false, AppStrings.t(appContext, "Таймер сну завершено", "Sleep timer finished"))
            return true
        }
        val rewindListener = listener
        if (!nowPaused && rewindListener != null && shouldSmartRewind()) {
            stop()
            rewindListener.onLongResumeRewind(sessionGen.get())
            return false
        }
        synchronized(trackLock) {
            val fade = !immediate && listeningPrefs.audioEdgeFadeEnabled
            pausedFlag.set(nowPaused)
            pausedAtElapsedMs = if (nowPaused) SystemClock.elapsedRealtime() else 0L
            beginEdgeRamp(if (nowPaused) 0f else 1f, fade)
            val track = currentTrack
            if (track != null) {
                try {
                    if (nowPaused && !fade) track.pause()
                    applyTrackVolume(track)
                    if (!nowPaused && trackHasPlayed &&
                        focusGate.currentState(sessionGen.get()) == PlaybackFocusGate.State.GRANTED
                    ) {
                        track.play()
                    }
                } catch (_: Exception) {}
            }
        }
        signalPauseWaiters()
        if (nowPaused) {
            listener?.onPlaybackStateChanged(true, true, AppStrings.t(appContext, "Пауза", "Paused"))
        } else {
            listener?.onPlaybackStateChanged(true, false, AppStrings.t(appContext, "Озвучення…", "Playing…"))
        }
        return nowPaused
    }

    fun pauseIfPlaying(immediate: Boolean = false) {
        if (!playingFlag.get()) return
        if (!pausedFlag.get()) {
            togglePause(immediate)
        } else if (immediate) {
            synchronized(trackLock) {
                beginEdgeRamp(0f, fade = false)
                currentTrack?.let { applyTrackVolume(it) }
            }
        }
    }

    override fun close() {
        listener = null
        stop()
        sleepScheduler.shutdownNow()
    }

    /** Non-blocking stop: signals cancellation and interrupts worker threads without stalling the UI thread. */
    fun stop() {
        val prevGen = sessionGen.get()
        sessionGen.incrementAndGet()
        focusGate.cancel(prevGen)
        cancelScheduledSleep()
        activeCancel?.set(true)
        activeCancel = null
        pausedFlag.set(false)
        pausedAtElapsedMs = 0L
        signalPauseWaiters()
        synchronized(trackLock) {
            try {
                currentTrack?.pause()
                currentTrack?.flush()
            } catch (_: Exception) {
            }
        }
        initThread?.interrupt()
        producerThread?.interrupt()
        consumerThread?.interrupt()
        syncThread?.interrupt()
        initThread = null
        producerThread = null
        consumerThread = null
        syncThread = null
        playingFlag.set(false)
    }

    companion object {
        private const val TAG = "TtsPlaybackController"

        fun offlineDir(context: Context): File =
            File(context.applicationContext.filesDir, "offline_chapters").apply { mkdirs() }

        /**
         * Builds a deterministic offline chapter WAV filename incorporating voice, speed,
         * adaptive mode, and user stress overrides timestamp so stale renders are never played.
         */
        fun addonRevisionFingerprint(context: Context, addonId: OnnxAddonId): String {
            if (!OnnxAddonManager.isAddonReady(context, addonId)) return ""
            val dir = OnnxAddonManager.getAddonDir(context, addonId)
            return OnnxAddonCatalog.byId(addonId).requiredFiles.joinToString(",") { rel ->
                val f = File(dir, rel)
                "${rel}:${f.length()}:${f.lastModified()}"
            }
        }

        internal fun resolveOfflineSnapshot(
            context: Context,
            prefs: ReaderPreferences,
            effectiveModelOverride: TtsModelId? = null,
            flashSrActiveOverride: Boolean? = null,
            emotionActiveOverride: Boolean? = null,
            contextualStressActiveOverride: Boolean? = null,
            voicePlan: AdaptiveVoicePlan = AdaptiveVoicePlan.capture(context, prefs),
        ): AudioCacheRecipe.ResolvedSynthesisSnapshot {
            val appCtx = context.applicationContext
            val requestedModel = prefs.selectedTtsModelId
            val effectiveModel = effectiveModelOverride
                ?: if (HolosModelManager.isModelReady(appCtx, requestedModel)) requestedModel
                else HolosModelManager.downloadedModels(appCtx).firstOrNull() ?: requestedModel

            val isHolosFamily = effectiveModel != TtsModelId.SUPERTONIC_3 ||
                voicePlan.assignments.values.any { it.model != TtsModelId.SUPERTONIC_3 }
            val flashSrActive = flashSrActiveOverride
                ?: (isHolosFamily && prefs.flashSrEnabled && OnnxAddonManager.isAddonReady(appCtx, OnnxAddonId.FLASH_SR))
            val emotionActive = emotionActiveOverride
                ?: (prefs.emotionPacingEnabled && OnnxAddonManager.isAddonReady(appCtx, OnnxAddonId.UK_EMOTION))
            val contextualActive = contextualStressActiveOverride
                ?: (prefs.contextualStressEnabled && OnnxAddonManager.isAddonReady(appCtx, OnnxAddonId.CONTEXTUAL_STRESS))

            val stressFile = HolosEngineHolder.getUserStressFile(appCtx)
            val customVoicesDir = HolosEngineHolder.getCustomVoicesDir(appCtx)
            val voiceFp: (String?) -> String = { v ->
                if (v != null && v.startsWith(CustomVoiceLoader.CUSTOM_PREFIX)) {
                    val f = File(customVoicesDir, "${v.removePrefix(CustomVoiceLoader.CUSTOM_PREFIX)}.bin")
                    if (f.isFile) "${f.length()}:${f.lastModified()}" else "missing"
                } else ""
            }
            val snapshot = AudioCacheRecipe.resolve(
                parserVersion = com.holos.reader.epub.EpubStreamingParser.PARSER_VERSION,
                requestedModelId = requestedModel.id,
                effectiveModelId = effectiveModel.id,
                selectedVoice = voicePlan.narrator,
                adaptiveVoicesEnabled = voicePlan.enabled,
                systemVoice = voicePlan.system,
                dialogueVoice = voicePlan.dialogue,
                voiceFingerprintProvider = voiceFp,
                speechSpeed = prefs.speechSpeed,
                supertonicSteps = prefs.supertonicSteps,
                supertonicEnglishPronunciation = prefs.supertonicEnglishPronunciation,
                flashSrActive = flashSrActive,
                flashSrRevision = if (flashSrActive) {
                    "${com.holos.tts.FlashSrOnnx.PROCESSING_REVISION}:${addonRevisionFingerprint(appCtx, OnnxAddonId.FLASH_SR)}"
                } else "",
                emotionActive = emotionActive,
                emotionRevision = if (emotionActive) addonRevisionFingerprint(appCtx, OnnxAddonId.UK_EMOTION) else "",
                contextualStressActive = contextualActive,
                contextualStressRevision = if (contextualActive) addonRevisionFingerprint(appCtx, OnnxAddonId.CONTEXTUAL_STRESS) else "",
                stressOverridesMtime = stressFile.lastModified(),
                stressOverridesSize = stressFile.length(),
            )
            val roleIdentity = voicePlan.assignments.entries.joinToString("|") { (role, assignment) ->
                val voice = assignment.voice
                "${role.name}:${assignment.model.id}:${voice.length}:$voice:${voiceFp(voice)}"
            }
            return snapshot.copy(recipe = snapshot.recipe.copy(adaptiveRoleAssignments = roleIdentity))
        }

        fun offlineChapterFile(context: Context, bookId: String, chapterIndex: Int, recipe: AudioCacheRecipe): File {
            return File(offlineDir(context), "${bookId}_ch${chapterIndex}_${recipe.cacheKeyHex()}.wav")
        }

        fun offlineChapterFile(context: Context, bookId: String, chapterIndex: Int, prefs: ReaderPreferences): File {
            val snapshot = resolveOfflineSnapshot(context, prefs)
            return offlineChapterFile(context, bookId, chapterIndex, snapshot.recipe)
        }

        fun writeOfflineIndex(idxFile: File, entries: List<OfflineBlockIndex>) {
            val sb = StringBuilder()
            for (e in entries) {
                sb.append(e.blockIndex).append('\t')
                    .append(e.startSample).append('\t')
                    .append(e.speechSamples).append('\n')
            }
            idxFile.writeText(sb.toString(), Charsets.UTF_8)
        }

        fun readOfflineIndex(idxFile: File): List<OfflineBlockIndex> {
            return try {
                idxFile.readLines(Charsets.UTF_8).mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size >= 3) {
                        val b = parts[0].toIntOrNull() ?: return@mapNotNull null
                        val st = parts[1].toLongOrNull() ?: return@mapNotNull null
                        val sp = parts[2].toLongOrNull() ?: return@mapNotNull null
                        OfflineBlockIndex(b, st, sp)
                    } else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read offline index $idxFile", e)
                emptyList()
            }
        }

        private fun readWavSampleRate(wavFile: File): Int {
            return try {
                RandomAccessFile(wavFile, "r").use { raf ->
                    val hdr = ByteArray(28)
                    if (raf.read(hdr) == 28) {
                        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                        val sr = bb.getInt(24)
                        if (sr in 8000..96000) sr else 24000
                    } else 24000
                }
            } catch (_: Exception) {
                24000
            }
        }
    }
}
