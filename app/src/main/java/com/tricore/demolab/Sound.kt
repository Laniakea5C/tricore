package com.tricore.demolab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Everything is synthesised locally.
 *
 *  - music: a 4 minute seamless dark drone (22.05 kHz mono), streamed through
 *    AudioTrack so the long loop costs no memory pressure on SoundPool.
 *  - click: short plastic switch via SoundPool, always available regardless of
 *    the music toggle.
 */
object Sfx {
    private const val MUSIC_RATE = 22050

    private var pool: SoundPool? = null
    private var clickId = 0
    private var ready = false

    private var musicTrack: AudioTrack? = null
    private var musicThread: Thread? = null
    private var musicData: ShortArray? = null
    private var musicBuilding = false

    @Volatile
    private var musicWanted = false

    var musicEnabled by mutableStateOf(true)
        private set

    fun init(context: Context) {
        if (ready) return
        runCatching {
            appCtx = context.applicationContext
            val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
            val clickFile = File(dir, "click_v4.wav")
            if (!clickFile.exists()) clickFile.writeBytes(wav(buildClick(), 44100))

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val sp = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attributes).build()
            pool = sp
            clickId = sp.load(clickFile.absolutePath, 1)
            sp.setOnLoadCompleteListener { _, _, status -> if (status == 0) ready = true }

            musicEnabled = context.getSharedPreferences("tricore_sound", Context.MODE_PRIVATE)
                .getBoolean("music", true)
            musicData = loadCachedMusic(context)
        }.onFailure { Log.e("Sfx", "init failed", it) }
    }

    fun toggleMusic(context: Context) {
        musicEnabled = !musicEnabled
        context.getSharedPreferences("tricore_sound", Context.MODE_PRIVATE)
            .edit().putBoolean("music", musicEnabled).apply()
        if (musicEnabled && musicWanted) ensureMusic(context) else stopMusic()
    }

    fun resumeMusic(context: Context) {
        musicWanted = true
        appCtx = context.applicationContext
        if (musicEnabled) ensureMusic(context.applicationContext)
    }

    fun pauseMusic() {
        musicWanted = false
        stopMusic()
    }

    /** the switch sound is independent from the music toggle */
    fun click(pitch: Float = 1f, volume: Float = 0.85f) {
        if (!ready) return
        pool?.play(clickId, volume, volume, 1, 0, pitch)
    }

    fun on() = click(pitch = 1.10f)
    fun off() = click(pitch = 0.90f)

    // ------------------------------------------------------------------ music

    private var appCtx: Context? = null

    private fun ensureMusic(context: Context) {
        if (musicTrack != null) return
        val data = musicData
        if (data != null) {
            startStream(data)
            return
        }
        if (musicBuilding) { return }
        Log.i("Sfx", "ensureMusic: building 4 min loop")
        musicBuilding = true
        thread(name = "sfx-build") {
            runCatching {
                val t0 = System.currentTimeMillis()
                val built = buildMusic()
                Log.i("Sfx", "built samples=${built.size} in ${System.currentTimeMillis() - t0}ms")
                musicData = built
                saveCachedMusic(context, built)
                if (musicWanted && musicEnabled) startStream(built)
            }.onFailure { Log.e("Sfx", "music build failed", it) }
            musicBuilding = false
        }
    }

    private fun startStream(data: ShortArray) {
        if (musicTrack != null) return
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MUSIC_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(MUSIC_RATE * 2)
                .build()
            track.setVolume(0.35f)
            track.play()
            Log.i("Sfx", "stream started len=${data.size}")
            musicTrack = track
            musicThread = thread(name = "sfx-stream") {
                try {
                    var offset = 0
                    val chunk = 1024
                    while (true) {
                        val t = musicTrack ?: break
                        val len = minOf(chunk, data.size - offset).coerceAtLeast(1)
                        val written = runCatching { t.write(data, offset, len) }.getOrDefault(-1)
                        if (written <= 0) break
                        offset = (offset + written) % data.size
                    }
                } catch (t: Throwable) {
                    Log.w("Sfx", "writer finished", t)
                }
            }
        }.onFailure { Log.e("Sfx", "stream failed", it) }
    }

    private fun stopMusic() {
        Log.i("Sfx", "stopMusic called")
        runCatching {
            musicTrack?.pause()
            musicTrack?.flush()
            musicTrack?.release()
        }
        musicTrack = null
        musicThread = null
    }

    // ------------------------------------------------------------------ cache

    private fun cacheFile(context: Context) = File(File(context.cacheDir, "sfx"), "music_v5.pcm")

    private fun loadCachedMusic(context: Context): ShortArray? = runCatching {
        val f = cacheFile(context)
        if (!f.exists() || f.length() < 1_000_000) return null
        val bytes = f.readBytes()
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        out
    }.getOrNull()

    private fun saveCachedMusic(context: Context, data: ShortArray) {
        runCatching {
            FileOutputStream(cacheFile(context)).use { out ->
                val buf = ByteArray(1 shl 16)
                var idx = 0
                var p = 0
                for (s in data) {
                    val v = s.toInt()
                    buf[p] = (v and 0xFF).toByte()
                    buf[p + 1] = ((v shr 8) and 0xFF).toByte()
                    p += 2
                    if (p == buf.size) {
                        out.write(buf, 0, p); p = 0; idx++
                    }
                }
                if (p > 0) out.write(buf, 0, p)
            }
        }
    }

    // ------------------------------------------------------------------ synthesis

    private fun saw(x: Double): Double = 2.0 * (x - kotlin.math.floor(x + 0.5))

    /**
     * 240 s drone at 22.05 kHz. Every modulation period divides the loop length, and
     * the tail is crossfaded over 4 s, so the seam is inaudible.
     */
    private fun buildMusic(): ShortArray {
        val seconds = 240.0
        val n = (MUSIC_RATE * seconds).toInt()
        val mix = FloatArray(n)
        val rnd = Random(9)

        // --- slow evolving drone: E1 / B1 / E2, detuned saws, heavy lowpass
        val tones = doubleArrayOf(41.20, 61.74, 82.41, 123.47)
        var lp1 = 0f
        var lp2 = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / MUSIC_RATE
            var v = 0.0
            for (f in tones) v += saw(f * 0.9962 * t) + saw(f * t) + saw(f * 1.0038 * t)
            v *= 0.010
            // 0.05 Hz and 0.1 Hz both divide 240 s exactly
            val lfo = 0.60 + 0.25 * sin(2.0 * PI * 0.05 * t) + 0.15 * sin(2.0 * PI * 0.1 * t + 2.1)
            lp1 += ((v * lfo).toFloat() - lp1) * 0.045f
            lp2 += (lp1 - lp2) * 0.060f
            mix[i] += lp2 * 1.2f
        }

        // --- noise wash, 0.025 Hz swell
        var noiseLp = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / MUSIC_RATE
            val noise = rnd.nextFloat() * 2f - 1f
            noiseLp += (noise - noiseLp) * 0.018f
            val swell = 0.5 + 0.5 * sin(2.0 * PI * 0.025 * t + 1.0)
            mix[i] += noiseLp * 0.05f * swell.toFloat()
        }

        // --- sparse low bells every 20 s (12 per loop)
        val bells = doubleArrayOf(164.81, 123.47, 98.00, 82.41)
        for (k in 0 until 12) {
            val start = ((k * 20.0 + 3.0) * MUSIC_RATE).toInt()
            val freq = bells[k % bells.size]
            val len = (9.0 * MUSIC_RATE).toInt()
            for (i in 0 until len) {
                val pos = start + i
                if (pos >= n) break
                val t = i.toDouble() / MUSIC_RATE
                val env = exp(-t * 0.75)
                val v = sin(2.0 * PI * freq * t) * 0.6 +
                    sin(2.0 * PI * freq * 2.0 * t) * 0.16 +
                    sin(2.0 * PI * freq * 3.01 * t) * 0.06
                mix[pos] += (v * env).toFloat() * 0.050f
            }
        }

        // --- heartbeat sub every 10 s
        for (k in 0 until 24) {
            val start = ((k * 10.0) * MUSIC_RATE).toInt()
            val len = (2.0 * MUSIC_RATE).toInt()
            for (i in 0 until len) {
                val pos = start + i
                if (pos >= n) break
                val t = i.toDouble() / MUSIC_RATE
                mix[pos] += sin(2.0 * PI * 41.2 * t).toFloat() * exp(-t * 3.0).toFloat() * 0.09f
            }
        }

        // --- long dark reverb
        val l1 = (0.173 * MUSIC_RATE).toInt()
        val l2 = (0.211 * MUSIC_RATE).toInt()
        val l3 = (0.293 * MUSIC_RATE).toInt()
        val c1 = FloatArray(n)
        val c2 = FloatArray(n)
        val c3 = FloatArray(n)
        for (i in 0 until n) {
            val x = mix[i]
            c1[i] = x + (if (i >= l1) c1[i - l1] * 0.52f else 0f)
            c2[i] = x + (if (i >= l2) c2[i - l2] * 0.48f else 0f)
            c3[i] = x + (if (i >= l3) c3[i - l3] * 0.44f else 0f)
        }

        val out = ShortArray(n)
        var peak = 0.0001f
        val shaped = FloatArray(n)
        for (i in 0 until n) {
            var v = mix[i] * 0.62f + (c1[i] + c2[i] + c3[i]) / 3f * 0.42f
            v = tanh(v * 1.2f)
            shaped[i] = v
            if (abs(v) > peak) peak = abs(v)
        }
        val gain = 0.30f / peak
        for (i in 0 until n) {
            out[i] = (shaped[i] * gain * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // 4 s crossfade so the loop point is inaudible
        val fade = (MUSIC_RATE * 4.0).toInt()
        for (i in 0 until fade) {
            val k = i.toFloat() / fade
            out[i] = ((out[i] * k) + (out[n - fade + i] * (1 - k))).toInt().toShort()
        }
        return out
    }

    /** ~140 ms UI switch: sharp plastic tick + short body + tiny room tail. */
    private fun buildClick(): ShortArray {
        val n = (44100 * 0.14).toInt()
        val raw = FloatArray(n)
        val random = Random(11)
        var prev = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / 44100f
            val attack = (t / 0.0018f).coerceAtMost(1f)
            val noise = random.nextFloat() * 2f - 1f
            val hp = noise - prev
            prev = noise
            val tick = hp * exp(-t * 140f).toFloat() * 0.55f
            val body = (sin(2.0 * PI * 330.0 * t).toFloat() * 0.55f +
                sin(2.0 * PI * 220.0 * t + 0.5f).toFloat() * 0.45f) * exp(-t * 26f).toFloat()
            val edge = sin(2.0 * PI * 1320.0 * t).toFloat() * exp(-t * 60f).toFloat() * 0.20f
            raw[i] = (tick + body * 0.55f + edge) * attack
        }
        val d = (0.021 * 44100).toInt()
        val tail = FloatArray(n)
        for (i in 0 until n) tail[i] = raw[i] + if (i >= d) tail[i - d] * 0.30f else 0f

        val out = ShortArray(n)
        for (i in 0 until n) {
            var v = raw[i] + tail[i] * 0.35f
            v = tanh(v * 2.2f) * 0.62f
            out[i] = (v * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val fade = (44100 * 0.02).toInt()
        for (i in 0 until fade) {
            val idx = n - fade + i
            out[idx] = (out[idx] * (1f - i.toFloat() / fade)).toInt().toShort()
        }
        return out
    }

    private fun wav(samples: ShortArray, rate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArray(44 + dataSize)
        fun putInt(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            out[offset + 2] = ((value shr 16) and 0xFF).toByte()
            out[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        putInt(4, 36 + dataSize)
        out[8] = 'W'.code.toByte(); out[9] = 'A'.code.toByte()
        out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()
        out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte()
        out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, rate)
        putInt(28, rate * 2)
        putShort(32, 2)
        putShort(34, 16)
        out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte()
        out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
        putInt(40, dataSize)
        var p = 44
        for (s in samples) {
            out[p] = (s.toInt() and 0xFF).toByte()
            out[p + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            p += 2
        }
        return out
    }
}
