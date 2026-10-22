package com.aicode.core.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.StatFs
import android.os.SystemClock
import android.view.Choreographer
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A single sample point (roughly one per second). */
data class PerfSample(
    /** Epoch milliseconds. */
    val t: Long,
    /** The module the user was in at sampling time (mapped from the navigation route, see [setModule]). */
    val module: String,
    /** This process's CPU as a percentage of total device compute (all cores together = 100%). */
    val cpuPct: Float,
    val pssMb: Float,
    val rssMb: Float,
    val javaHeapMb: Float,
    val javaHeapMaxMb: Float,
    val nativePssMb: Float,
    val threads: Int,
    /** Frames rendered in the past 1 second. */
    val fps: Int,
    /** Janky frames in the past 1 second (frame interval > 33ms). */
    val jank: Int
)

/** Cumulative process usage (since sampling was enabled). */
data class PerfTotals(
    val startedAt: Long = 0L,
    val samples: Int = 0,
    val frames: Long = 0L,
    val jankFrames: Long = 0L,
    val peakRssMb: Float = 0f,
    /** Cumulative process CPU time (milliseconds, converted from utime+stime). */
    val totalCpuMs: Long = 0L
)

/** Whole-device snapshot. */
data class PerfSystem(
    val memTotalMb: Float = 0f,
    val memAvailMb: Float = 0f,
    val storageTotalMb: Float = 0f,
    val storageAvailMb: Float = 0f,
    val cpuCores: Int = Runtime.getRuntime().availableProcessors()
)

/** Overview of the persisted record files. */
data class PerfFilesInfo(
    val count: Int = 0,
    val totalBytes: Long = 0L,
    val oldest: Long = 0L,
    val newest: Long = 0L
)

/**
 * Performance sampler: provides live usage, per-module aggregation and long-term persisted data for the "Performance panel".
 *
 * Design trade-offs (pragmatic version):
 * - Sampling is enabled manually by the user (Settings -> Data & diagnostics -> Performance panel), one point every [SAMPLE_INTERVAL_MS];
 * - Live data uses an in-memory ring buffer (the most recent [RING_MAX] points, enough for 1 hour), with aggregation computed on the fly in the UI layer;
 * - Long-term analysis relies on JSONL files on disk (`filesDir/perf/perf-yyyyMMdd.jsonl`, one file per day, with a sequence number automatically added over 8MB),
 *   keeping the most recent [KEEP_DAYS] days, with old files cleaned up automatically;
 * - Module attribution does not instrument each screen; it directly reuses MainActivity's navigation routes (chat/settings/terminal/...),
 *   received passively via [setModule];
 * - CPU usage is calculated as a "percentage of total device compute" (all cores together = 100%), consistent with system monitoring tools.
 *
 * Sampling itself only reads /proc and does one appendText, with negligible overhead; FPS is counted via Choreographer frame callbacks.
 */
@Singleton
class PerfMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "PerfMonitor"
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val RING_MAX = 3_600

        /**
         * Sampling window sent to the UI (number of entries).
         *
         * The ring buffer keeps [RING_MAX] for export/long-term analysis, but do **not** copy the whole thing to the UI:
         * Calling toList() every second on a 3600-item list, then letting the Compose side groupBy and fully recompute,
         * amounts to one large object allocation + an O(n) traversal every second -- measured as one of the drivers of the heap sawtoothing between 24 and 136MB
         * (GC jitter) and dropping to 15fps.
         * The live panel only needs the last few minutes, so the window is set to 180 (3 minutes).
         */
        const val UI_SAMPLE_WINDOW = 180
        const val KEEP_DAYS = 7
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val DIR = "perf"
        val DAY_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US)
        val TS_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        /** Jank threshold: frame interval > 33ms (about 2 60Hz vsyncs). */
        const val JANK_THRESHOLD_NS = 33_000_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val choreographer = Choreographer.getInstance()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _module = MutableStateFlow("chat")
    val module: StateFlow<String> = _module.asStateFlow()

    private val _samples = MutableStateFlow<List<PerfSample>>(emptyList())
    /** The most recent [RING_MAX] sample points; the UI consumes them once per second for live display and aggregation. */
    val samples: StateFlow<List<PerfSample>> = _samples.asStateFlow()

    private val _totals = MutableStateFlow(PerfTotals())
    val totals: StateFlow<PerfTotals> = _totals.asStateFlow()

    private val _system = MutableStateFlow(PerfSystem())
    val system: StateFlow<PerfSystem> = _system.asStateFlow()

    private val _filesInfo = MutableStateFlow(PerfFilesInfo())
    val filesInfo: StateFlow<PerfFilesInfo> = _filesInfo.asStateFlow()

    private val ring = ArrayDeque<PerfSample>(RING_MAX)
    private var samplingJob: Job? = null
    private var fileInfoTick = 0

    // ── FPS frame callbacks ─────────────────────────────────────────────
    private val frameTimesNs = ArrayDeque<Long>(240)
    private val frameLock = Any()
    private var frameCallback: Choreographer.FrameCallback? = null

    private val frameCallbackImpl = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            synchronized(frameLock) {
                // Sampling has stopped (stopFrameCallbacks cleared frameCallback): terminate the re-posting chain immediately.
                // Otherwise a callback executing at the moment of stopping would post itself again, spinning every frame forever.
                if (frameCallback == null) return
                frameTimesNs.addLast(System.nanoTime())
                while (frameTimesNs.size > 240) frameTimesNs.removeFirst()
            }
            // Keeps posting the next frame; removed by [stopFrameCallbacks] when sampling stops.
            choreographer.postFrameCallback(this)
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            pruneOldFiles()
            refreshFilesInfo()
            _system.value = readSystemSnapshot()
        }
    }

    /** Called by MainActivity when the navigation route changes. */
    fun setModule(key: String) {
        if (_module.value != key) _module.value = key
    }

    fun setEnabled(on: Boolean) {
        if (_enabled.value == on) return
        _enabled.value = on
        samplingJob?.cancel()
        samplingJob = null
        if (on) {
            startFrameCallbacks()
            samplingJob = scope.launch { sampleLoop() }
            FileLogger.i(TAG, "Performance sampling enabled")
        } else {
            stopFrameCallbacks()
            FileLogger.i(TAG, "Performance sampling disabled")
        }
    }

    // ── Export / cleanup ────────────────────────────────────────────

    /** Merges all record files into a single JSONL and returns the export file in the cache (the caller decides where to save it). */
    suspend fun exportRawFile(): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "aicode-perf-raw-${TS_FORMAT.format(Date())}.jsonl")
        out.bufferedWriter().use { writer ->
            perfFilesSorted().forEach { f ->
                f.forEachLine { writer.appendLine(it) }
            }
        }
        out
    }

    /** Aggregates all historical samples by module and exports CSV (module,samples,seconds,avg_cpu_pct,avg_pss_mb,max_pss_mb). */
    suspend fun exportSummaryCsv(): File = withContext(Dispatchers.IO) {
        data class Agg(var samples: Int = 0, var cpuSum: Double = 0.0, var pssSum: Double = 0.0, var pssMax: Float = 0f)
        val agg = linkedMapOf<String, Agg>()
        perfFilesSorted().forEach { f ->
            f.forEachLine { line ->
                runCatching {
                    val o = JSONObject(line)
                    val m = o.optString("module", "other")
                    val a = agg.getOrPut(m) { Agg() }
                    a.samples++
                    a.cpuSum += o.optDouble("cpu_pct", 0.0)
                    val pss = o.optDouble("pss_mb", 0.0).toFloat()
                    a.pssSum += pss
                    if (pss > a.pssMax) a.pssMax = pss
                }
            }
        }
        val out = File(context.cacheDir, "aicode-perf-summary-${TS_FORMAT.format(Date())}.csv")
        out.bufferedWriter().use { w ->
            w.appendLine("module,samples,seconds,avg_cpu_pct,avg_pss_mb,max_pss_mb")
            agg.toSortedMap().forEach { (m, a) ->
                val avgCpu = if (a.samples > 0) a.cpuSum / a.samples else 0.0
                val avgPss = if (a.samples > 0) a.pssSum / a.samples else 0.0
                w.appendLine("$m,${a.samples},${a.samples},${"%.2f".format(avgCpu)},${"%.1f".format(avgPss)},${"%.1f".format(a.pssMax)}")
            }
        }
        out
    }

    /** Clears all persisted records (the in-memory ring buffer is kept). */
    fun clearHistory() {
        scope.launch(Dispatchers.IO) {
            perfDir().listFiles()?.forEach { it.delete() }
            refreshFilesInfo()
        }
    }

    // ── Sampling main loop ─────────────────────────────────────────────

    private suspend fun sampleLoop() {
        var lastSelfTicks = readSelfTicks()
        var lastDeviceTotal = readDeviceTotalTicks()
        var lastWallMs = SystemClock.elapsedRealtime()
        var lastCpuMs = selfCpuMs()
        while (scope.isActive && _enabled.value) {
            delay(SAMPLE_INTERVAL_MS)
            val nowWall = SystemClock.elapsedRealtime()
            val wallDeltaMs = (nowWall - lastWallMs).coerceAtLeast(1)
            lastWallMs = nowWall

            val selfTicks = readSelfTicks()
            val deviceTotal = readDeviceTotalTicks()
            val selfDelta = (selfTicks - lastSelfTicks).coerceAtLeast(0)
            val rawDeviceDelta = (deviceTotal - lastDeviceTotal).coerceAtLeast(0)
            lastSelfTicks = selfTicks
            lastDeviceTotal = deviceTotal
            // /proc/stat cannot be read on some devices or always returns 0 (measured: OnePlus Android 16),
            // and using it as the denominator then would amplify CPU% by hundreds of times -- user-exported data showed 6600%,
            // when it was actually just selfDelta=66 ticks. When the denominator is untrustworthy, fall back to "core count x ticksPerSecond"
            // as the total device capacity, keeping values in the reasonable range 0..(core count x 100)%.
            val capacityTicks = (
                Runtime.getRuntime().availableProcessors().coerceAtLeast(1) *
                    ticksPerSecond().toLong()
                ).coerceAtLeast(1L)
            val deviceDelta =
                if (rawDeviceDelta in 1 until capacityTicks * 8) rawDeviceDelta else capacityTicks
            val cpuPct = selfDelta * 100f / deviceDelta

            val mem = readMemoryInfo()
            val (fps, jank) = drainFrameStats()

            val sample = PerfSample(
                t = System.currentTimeMillis(),
                module = _module.value,
                cpuPct = cpuPct,
                pssMb = mem.totalPssMb,
                rssMb = mem.rssMb,
                javaHeapMb = mem.javaHeapMb,
                javaHeapMaxMb = mem.javaHeapMaxMb,
                nativePssMb = mem.nativePssMb,
                threads = mem.threads,
                fps = fps,
                jank = jank
            )

            ring.addLast(sample)
            while (ring.size > RING_MAX) ring.removeFirst()
            // Only hand the most recent UI_SAMPLE_WINDOW entries to the UI, avoiding copying the whole 3600-item buffer every second
            // (see the UI_SAMPLE_WINDOW comment: this is a driver of GC sawtoothing / frame drops).
            _samples.value = if (ring.size <= UI_SAMPLE_WINDOW) {
                ring.toList()
            } else {
                ring.drop(ring.size - UI_SAMPLE_WINDOW)
            }

            val nowCpuMs = selfCpuMs()
            val cpuMsDelta = (nowCpuMs - lastCpuMs).coerceAtLeast(0)
            lastCpuMs = nowCpuMs
            _totals.value = _totals.value.let {
                PerfTotals(
                    startedAt = if (it.startedAt == 0L) sample.t else it.startedAt,
                    samples = it.samples + 1,
                    frames = it.frames + fps,
                    jankFrames = it.jankFrames + jank,
                    peakRssMb = maxOf(it.peakRssMb, sample.rssMb),
                    totalCpuMs = it.totalCpuMs + cpuMsDelta
                )
            }

            withContext(Dispatchers.IO) {
                runCatching { appendToFile(sample) }
                    .onFailure { FileLogger.w(TAG, "Failed to write performance record: ${it.message}") }
                if (++fileInfoTick % 60 == 0) {
                    _system.value = readSystemSnapshot()
                    refreshFilesInfo()
                }
            }
        }
    }

    // ── Low-level reads ───────────────────────────────────────────────

    private data class MemInfo(
        val totalPssMb: Float,
        val nativePssMb: Float,
        val rssMb: Float,
        val javaHeapMb: Float,
        val javaHeapMaxMb: Float,
        val threads: Int
    )

    private fun readMemoryInfo(): MemInfo {
        // Prefer /proc/self/smaps_rollup: a pure file read with no binder IPC, and PSS is accurate in real time.
        // Previously ActivityManager.getProcessMemoryInfo was called once per second -- that is synchronous IPC,
        // which blocks the sampling thread when the system is busy; and some devices return cached values, with measured PSS stuck at a constant 350.7MB
        // without moving, completely losing any observational value. Only fall back to IPC when the rollup is unavailable.
        var totalPssKb = readRollupKb("Pss:")
        var nativePssKb = 0L
        if (totalPssKb <= 0) {
            runCatching {
                val info = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()
                if (info != null) {
                    totalPssKb = info.totalPss.toLong()
                    nativePssKb = info.nativePss.toLong()
                }
            }
        }
        val rt = Runtime.getRuntime()
        val usedHeap = rt.totalMemory() - rt.freeMemory()
        return MemInfo(
            totalPssMb = totalPssKb / 1024f,
            nativePssMb = nativePssKb / 1024f,
            rssMb = readProcStatusKb("VmRSS:") / 1024f,
            javaHeapMb = usedHeap / (1024f * 1024f),
            javaHeapMaxMb = rt.maxMemory() / (1024f * 1024f),
            threads = readProcStatusKb("Threads:").toInt()
        )
    }

    /** Reads a field of the form `Pss:  1234 kB` from /proc/self/smaps_rollup (KB, no IPC). */
    private fun readRollupKb(key: String): Long {
        return runCatching {
            File("/proc/self/smaps_rollup").useLines { lines ->
                lines.firstOrNull { it.startsWith(key) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(' ')
                    ?.firstOrNull()
                    ?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    private fun readProcStatusKb(key: String): Long {
        return runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith(key) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(' ')
                    ?.firstOrNull()
                    ?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    /** utime+stime from /proc/self/stat (clock ticks). */
    private fun readSelfTicks(): Long {
        return runCatching {
            val line = File("/proc/self/stat").readText()
            val after = line.substringAfterLast(')').trim()
            val t = after.split(Regex("\\s+"))
            // after starts at field3(state), utime=field14 -> index 11, stime=field15 -> index 12.
            (t[11].toLongOrNull() ?: 0L) + (t[12].toLongOrNull() ?: 0L)
        }.getOrDefault(0L)
    }

    /** Cumulative process CPU milliseconds (since process start). */
    private fun selfCpuMs(): Long = readSelfTicks() * 1000L / ticksPerSecond()

    /** Cumulative ticks for all cores on the first line of /proc/stat (user+nice+system+idle+iowait+irq+softirq+steal). */
    private fun readDeviceTotalTicks(): Long {
        return runCatching {
            File("/proc/stat").bufferedReader().use { reader ->
                val line = reader.readLine() ?: return 0L
                line.removePrefix("cpu").trim().split(Regex("\\s+"))
                    .sumOf { it.toLongOrNull() ?: 0L }
            }
        }.getOrDefault(1L)
    }

    private fun readSystemSnapshot(): PerfSystem {
        var memTotal = 0L
        var memAvail = 0L
        runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.forEach { l ->
                    when {
                        l.startsWith("MemTotal:") -> memTotal = l.filterKb()
                        l.startsWith("MemAvailable:") -> memAvail = l.filterKb()
                    }
                }
            }
        }
        var storageTotal = 0L
        var storageAvail = 0L
        runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            storageTotal = stat.totalBytes
            storageAvail = stat.availableBytes
        }
        return PerfSystem(
            memTotalMb = memTotal / 1024f,
            memAvailMb = memAvail / 1024f,
            storageTotalMb = storageTotal / (1024f * 1024f),
            storageAvailMb = storageAvail / (1024f * 1024f)
        )
    }

    private fun String.filterKb(): Long =
        substringAfter(':').trim().split(' ').firstOrNull()?.toLongOrNull() ?: 0L

    private fun drainFrameStats(): Pair<Int, Int> {
        val now = System.nanoTime()
        val window = 1_000_000_000L
        synchronized(frameLock) {
            var frames = 0
            var jank = 0
            var prev = 0L
            val it = frameTimesNs.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (now - t > window) { it.remove(); continue }
                if (prev != 0L && t - prev > JANK_THRESHOLD_NS) jank++
                prev = t
                frames++
            }
            return frames to jank
        }
    }

    // ── Persistence ─────────────────────────────────────────────────

    private fun perfDir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun perfFilesSorted(): List<File> =
        perfDir().listFiles { f -> f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name } ?: emptyList()

    private fun appendToFile(sample: PerfSample) {
        val day = DAY_FORMAT.format(Date(sample.t))
        var f = File(perfDir(), "perf-$day.jsonl")
        var idx = 2
        while (f.exists() && f.length() > MAX_FILE_BYTES) {
            f = File(perfDir(), "perf-$day-$idx.jsonl")
            idx++
        }
        f.appendText(sample.toJson().toString() + "\n")
    }

    private fun pruneOldFiles() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        perfFilesSorted().forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    private fun refreshFilesInfo() {
        val files = perfFilesSorted()
        _filesInfo.value = PerfFilesInfo(
            count = files.size,
            totalBytes = files.sumOf { it.length() },
            oldest = files.minOfOrNull { it.lastModified() } ?: 0L,
            newest = files.maxOfOrNull { it.lastModified() } ?: 0L
        )
    }

    private fun startFrameCallbacks() {
        if (frameCallback != null) return
        frameCallback = frameCallbackImpl
        runCatching { choreographer.postFrameCallback(frameCallbackImpl) }
    }

    private fun stopFrameCallbacks() {
        val cb = frameCallback ?: return
        runCatching { choreographer.removeFrameCallback(cb) }
        frameCallback = null
        synchronized(frameLock) { frameTimesNs.clear() }
    }

    private fun PerfSample.toJson(): JSONObject = JSONObject().apply {
        put("t", t)
        put("module", module)
        put("cpu_pct", cpuPct.toDouble())
        put("pss_mb", pssMb.toDouble())
        put("rss_mb", rssMb.toDouble())
        put("java_heap_mb", javaHeapMb.toDouble())
        put("java_heap_max_mb", javaHeapMaxMb.toDouble())
        put("native_pss_mb", nativePssMb.toDouble())
        put("threads", threads)
        put("fps", fps)
        put("jank", jank)
    }
}

/** Clock ticks/second: prefers sysconf(_SC_CLK_TCK), falls back to the common Linux value 100 when unavailable. */
private fun ticksPerSecond(): Long = runCatching {
    android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
}.getOrDefault(100L)
