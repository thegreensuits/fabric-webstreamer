package fr.thegentlesuits.webstreamer.render;

import fr.thegentlesuits.webstreamer.WebStreamerClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared pools of heavy heap buffers plus a thread pool and an HTTP client, handed to
 * {@link DisplayLayerHls} instances.
 *
 * <p>Ported from fabric-webstreamer by Theo Rozier (LGPL-3.0).</p>
 */
@Environment(EnvType.CLIENT)
public class DisplayLayerResources {

	/** 8 MiB buffer for pre-storing a whole TransportStream file. */
	private static final int RAW_FILE_BUFFER_SIZE = 1 << 23;
	/** Limit to 256 MiB of raw file buffers. */
	private static final int RAW_FILE_BUFFER_LIMIT = 32;
	/** 8 KiB buffer for converting (16 or 8 bits) stereo to mono 16 bits audio stream. */
	private static final int AUDIO_BUFFER_SIZE = 8192;
	/** Limit to 512 KiB of audio buffers. */
	private static final int AUDIO_BUFFER_LIMIT = 64;

	private final AtomicInteger threadCounter = new AtomicInteger();
	private final ExecutorService executor = Executors.newFixedThreadPool(2,
			runnable -> new Thread(runnable, "WebStreamer Display Queue (" + this.threadCounter.getAndIncrement() + ")"));

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final List<ByteBuffer> rawFileBuffers = new ArrayList<>();
	private final List<ShortBuffer> audioBuffers = new ArrayList<>();

	private int rawFileBuffersCount = 0;
	private int audioBuffersCount = 0;

	public ExecutorService getExecutor() {
		return this.executor;
	}

	public HttpClient getHttpClient() {
		return this.httpClient;
	}

	/**
	 * Allocate an image buffer. Such buffers are backed by arrays to allow creating a
	 * {@link java.io.ByteArrayInputStream} from it.
	 */
	public ByteBuffer allocRawFileBuffer() {
		synchronized (this.rawFileBuffers) {
			if (!this.rawFileBuffers.isEmpty()) {
				return this.rawFileBuffers.remove(this.rawFileBuffers.size() - 1);
			}
			if (this.rawFileBuffersCount >= RAW_FILE_BUFFER_LIMIT) {
				throw new IllegalStateException("reached maximum number of allocated raw file buffers: " + RAW_FILE_BUFFER_LIMIT);
			}
			this.rawFileBuffersCount++;
			WebStreamerClient.LOGGER.debug("Number of allocated raw file buffers: {}", this.rawFileBuffersCount);
			return ByteBuffer.allocate(RAW_FILE_BUFFER_SIZE);
		}
	}

	public void freeRawFileBuffer(ByteBuffer buffer) {
		synchronized (this.rawFileBuffers) {
			this.rawFileBuffers.add(buffer);
		}
	}

	/**
	 * Allocate a sound buffer. Such buffers are backed by native memory in order to be
	 * directly usable as OpenAL buffer data.
	 */
	public ShortBuffer allocAudioBuffer() {
		synchronized (this.audioBuffers) {
			if (!this.audioBuffers.isEmpty()) {
				return this.audioBuffers.remove(this.audioBuffers.size() - 1);
			}
			if (this.audioBuffersCount >= AUDIO_BUFFER_LIMIT) {
				throw new IllegalStateException("reached maximum number of allocated audio buffers: " + AUDIO_BUFFER_LIMIT);
			}
			this.audioBuffersCount++;
			WebStreamerClient.LOGGER.debug("Number of allocated sound buffers: {}", this.audioBuffersCount);
			return ByteBuffer.allocateDirect(AUDIO_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
		}
	}

	public void freeAudioBuffer(ShortBuffer buffer) {
		synchronized (this.audioBuffers) {
			this.audioBuffers.add(buffer);
		}
	}

}
