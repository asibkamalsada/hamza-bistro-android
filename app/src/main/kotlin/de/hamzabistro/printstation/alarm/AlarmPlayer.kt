package de.hamzabistro.printstation.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import de.hamzabistro.printstation.station.AlarmSound
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Makes the noise: the alarm that rings in a loop until it is stopped, and
 * the chime that sounds once.
 *
 * On the ALARM stream, like an alarm clock, which is the whole point of
 * having an app: it sounds with the phone set to silent or vibrate, and
 * through Do Not Disturb as long as alarms are let through (Android's
 * default). With [full] it also turns the alarm volume up for as long as it
 * rings, and back to where it was afterwards — a kitchen tablet whose alarm
 * volume somebody turned down is the failure this is here to prevent.
 *
 * The site's three sounds (src/app/util/alert-sound.ts) are synthesised here
 * the same way, so nothing has to be shipped and nothing can fail to load;
 * [AlarmSound.DEVICE] is the device's own alarm tone.
 */
class AlarmPlayer(context: Context) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val main = Handler(Looper.getMainLooper())

    private var track: AudioTrack? = null
    private var ringtone: Ringtone? = null
    private var restoreVolume: Int? = null

    /** Whether the loop is sounding. */
    @Volatile
    var looping = false
        private set

    /** Starts the loop, or leaves it running when it already is. */
    @Synchronized
    fun startLoop(sound: AlarmSound, full: Boolean, vibrate: Boolean) {
        if (looping) return
        looping = true
        if (full) raiseVolume()
        if (sound == AlarmSound.DEVICE) {
            ringtone = deviceTone()?.apply {
                isLooping = true
                play()
            }
            // A device without an alarm tone still rings.
            if (ringtone == null) track = play(AlarmSound.BEEPS, loop = true)
        } else {
            track = play(sound, loop = true)
        }
        if (vibrate) vibrate(LOOP_VIBRATION, repeat = 0)
    }

    @Synchronized
    fun stopLoop() {
        if (!looping) return
        looping = false
        track?.release()
        track = null
        ringtone?.stop()
        ringtone = null
        vibrator.cancel()
        restoreVolume()
    }

    /** The sound once — a pre-order to start, a printer that stopped. Not while the loop rings. */
    @Synchronized
    fun chime(sound: AlarmSound, full: Boolean, vibrate: Boolean) {
        if (looping) return
        if (full) raiseVolume()
        val once = play(if (sound == AlarmSound.DEVICE) AlarmSound.BELL else sound, loop = false) ?: return
        if (vibrate) vibrate(CHIME_VIBRATION, repeat = -1)
        main.postDelayed(
            {
                synchronized(this) {
                    once.release()
                    if (!looping) restoreVolume()
                }
            },
            CHIME_MS,
        )
    }

    private fun play(sound: AlarmSound, loop: Boolean): AudioTrack? {
        val samples = synthesise(sound, loop)
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(ALARM)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
                .apply {
                    write(samples, 0, samples.size)
                    if (loop) setLoopPoints(0, samples.size, -1)
                    play()
                }
        } catch (e: Exception) {
            // No audio at all (a device without a speaker): the notification
            // and the vibration still say it.
            null
        }
    }

    private fun deviceTone(): Ringtone? {
        val uri =
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return null
        return RingtoneManager.getRingtone(context, uri)?.apply { audioAttributes = ALARM }
    }

    private fun vibrate(pattern: LongArray, repeat: Int) {
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(pattern, repeat)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, ALARM)
        }
    }

    private fun raiseVolume() {
        if (restoreVolume != null) return
        try {
            val now = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (now < max) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                restoreVolume = now
            }
        } catch (e: SecurityException) {
            // Do Not Disturb may forbid changing volumes; it rings at the volume it has.
        }
    }

    private fun restoreVolume() {
        val volume = restoreVolume ?: return
        restoreVolume = null
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
    }

    companion object {
        private const val RATE = 22_050
        private const val CHIME_MS = 2_500L

        private val ALARM: AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        /** Long, long, longer — nobody mistakes it for a message from a friend. */
        private val LOOP_VIBRATION = longArrayOf(0, 600, 300, 600, 300, 1_000, 800)
        private val CHIME_VIBRATION = longArrayOf(0, 300, 150, 300)

        private enum class Wave { SQUARE, SINE, SAW }

        /** One tone: when it starts and how long it sounds, in seconds, and its pitch. */
        private class Tone(val at: Double, val length: Double, val wave: Wave, val from: Double, val to: Double = from)

        /**
         * One round of the sound, with the pause after it that makes a loop
         * read as rings rather than a drone. The tones are the site's.
         */
        private fun pattern(sound: AlarmSound): Pair<List<Tone>, Double> =
            when (sound) {
                // Three rising square beeps, twice.
                AlarmSound.BEEPS ->
                    (0..5).map { i ->
                        val group = i / 3
                        val n = i % 3
                        Tone(at = group * 0.6 + n * 0.18, length = 0.15, wave = Wave.SQUARE, from = 880.0 + n * 220)
                    } to 1.6
                // A two-note chime with a long tail.
                AlarmSound.BELL, AlarmSound.DEVICE ->
                    listOf(Tone(0.0, 0.9, Wave.SINE, 988.0), Tone(0.35, 1.1, Wave.SINE, 784.0)) to 1.8
                // Two sawtooth sweeps up.
                AlarmSound.SIREN ->
                    listOf(Tone(0.0, 0.5, Wave.SAW, 600.0, 1300.0), Tone(0.5, 0.5, Wave.SAW, 600.0, 1300.0)) to 1.3
            }

        /** The sound as 16-bit samples. */
        private fun synthesise(sound: AlarmSound, loop: Boolean): ShortArray {
            val (tones, round) = pattern(sound)
            val seconds = if (loop) round else tones.maxOf { it.at + it.length } + 0.05
            val out = DoubleArray((seconds * RATE).toInt())
            for (tone in tones) {
                val start = (tone.at * RATE).toInt()
                val count = (tone.length * RATE).toInt()
                var phase = 0.0
                for (i in 0 until count) {
                    val index = start + i
                    if (index >= out.size) break
                    val t = i.toDouble() / count
                    val frequency = tone.from + (tone.to - tone.from) * t
                    phase += frequency / RATE
                    val cycle = phase - phase.toLong()
                    val value =
                        when (tone.wave) {
                            Wave.SQUARE -> if (cycle < 0.5) 0.55 else -0.55
                            Wave.SAW -> (cycle * 2 - 1) * 0.65
                            Wave.SINE -> sin(2 * PI * cycle) * 0.9
                        }
                    // Ramped in and out, so it does not click; a bell dies away.
                    val attack = (i.toDouble() / (0.01 * RATE)).coerceAtMost(1.0)
                    val release = ((count - i).toDouble() / (0.02 * RATE)).coerceAtMost(1.0)
                    val decay = if (tone.wave == Wave.SINE) exp(-3.0 * t) else 1.0
                    out[index] += value * attack * release * decay
                }
            }
            return ShortArray(out.size) { (out[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
        }
    }
}
