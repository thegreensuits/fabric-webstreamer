package fr.thegentlesuits.webstreamer.render;

import fr.thegentlesuits.webstreamer.WebStreamerClient;
import fr.thegentlesuits.webstreamer.audio.AudioStreamingBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * <p>A custom FFMPEG frame grabber working with image frames priority, this means that multiple
 * audio frames can be grabbed before finding the right image frame for the right timestamp.</p>
 * <p>The fact that FFMPEG will return the same {@link Frame} instance on every call requires us
 * to "bufferize" audio frames between each grab.</p>
 *
 * <p>Ported from fabric-webstreamer by Theo Rozier (LGPL-3.0); video output switched to RGBA
 * for direct GPU texture upload.</p>
 */
@Environment(EnvType.CLIENT)
public class FrameGrabber {

	private final DisplayLayerResources pools;
	private final URI uri;
	/** fMP4/CMAF initialization segment (EXT-X-MAP) to prepend, or null for self-contained segments. */
	private final byte[] initData;

	private ByteBuffer buffer;
	private FFmpegFrameGrabber grabber;
	private long refTimestamp;
	private long deltaTimestamp;
	private Frame lastFrame;

	private ShortBuffer tempAudioBuffer;

	private ArrayDeque<AudioStreamingBuffer> startAudioBuffers;

	public FrameGrabber(DisplayLayerResources pools, URI uri, byte[] initData) {
		this.pools = pools;
		this.uri = uri;
		this.initData = initData;
	}

	public void start() throws IOException {

		if (this.grabber != null || this.buffer != null) {
			throw new IllegalStateException("already started");
		}

		try {

			HttpRequest req = HttpRequest.newBuilder(this.uri).GET().timeout(Duration.ofSeconds(1)).build();
			this.buffer = this.pools.allocRawFileBuffer();
			this.buffer.clear();
			if (this.initData != null) {
				// CMAF: track headers (moov) come from the init segment; media segments
				// only carry moof/mdat and are undecodable on their own.
				this.buffer.put(this.initData);
			}
			this.pools.getHttpClient().send(req, info -> new BufferResponseSubscriber(this.buffer));
			ByteArrayInputStream grabberStream = new ByteArrayInputStream(this.buffer.array(), this.buffer.position(), this.buffer.remaining());

			this.grabber = new FFmpegFrameGrabber(grabberStream);
			this.grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
			// FFmpeg 8 no longer auto-probes stream inputs reliably: name the demuxer
			// from the segment extension (Twitch and most HLS use MPEG-TS).
			String format = formatFromUri(this.uri);
			if (format != null) {
				this.grabber.setFormat(format);
			}
			this.grabber.startUnsafe();

			this.tempAudioBuffer = this.pools.allocAudioBuffer();

			this.refTimestamp = 0L;
			this.deltaTimestamp = 0L;
			this.lastFrame = null;

			this.startAudioBuffers = new ArrayDeque<>();

			Frame frame;
			while ((frame = this.grabber.grab()) != null) {
				if (frame.image != null) {
					this.refTimestamp = frame.timestamp;
					this.lastFrame = frame;
					break;
				} else if (frame.samples != null) {
					this.startAudioBuffers.addLast(AudioStreamingBuffer.fromFrame(this.tempAudioBuffer, frame));
				}
			}

		} catch (IOException | InterruptedException | RuntimeException e) {

			if (this.grabber != null) {
				this.grabber.releaseUnsafe();
			}

			if (this.buffer != null) {
				this.pools.freeRawFileBuffer(this.buffer);
				this.buffer = null;
			}

			if (this.tempAudioBuffer != null) {
				this.pools.freeAudioBuffer(this.tempAudioBuffer);
				this.tempAudioBuffer = null;
			}

			if (e instanceof IOException ioe) {
				throw ioe;
			} else if (e instanceof InterruptedException) {
				throw new IOException(e);
			} else {
				throw (RuntimeException) e;
			}

		}

	}

	private static String formatFromUri(URI uri) {
		String path = uri.getPath();
		if (path == null) {
			return "mpegts";
		}
		if (path.endsWith(".m4s") || path.endsWith(".mp4")) {
			return "mp4";
		}
		if (path.endsWith(".aac")) {
			return "aac";
		}
		// .ts and anything unknown: MPEG-TS is the overwhelming default for HLS.
		return "mpegts";
	}

	public void stop() {

		if (this.grabber == null || this.buffer == null || this.tempAudioBuffer == null) {
			throw new IllegalStateException("Frame grabber is not started.");
		}

		try {
			this.grabber.releaseUnsafe();
		} catch (IOException ignored) { }

		this.pools.freeRawFileBuffer(this.buffer);
		this.pools.freeAudioBuffer(this.tempAudioBuffer);

		this.buffer = null;
		this.grabber = null;
		this.tempAudioBuffer = null;

		if (this.startAudioBuffers != null) {
			this.startAudioBuffers.forEach(AudioStreamingBuffer::free);
			this.startAudioBuffers = null;
		}

	}

	/**
	 * Grab the image frame at the corresponding timestamp, the grabber will attempt
	 * to get the closest frame before timestamp.
	 * @param timestamp The relative timestamp in microseconds. Relative to the first image frame.
	 * @param audioBufferConsumer A consumer for audio buffers decoded during image frame selection.
	 * @return The grabbed frame or null if frame has not updated since last grab.
	 */
	public Frame grabAt(long timestamp, Consumer<AudioStreamingBuffer> audioBufferConsumer) throws IOException {

		if (this.startAudioBuffers != null) {
			// Called once after start with audio buffers placed before the first frame.
			this.startAudioBuffers.forEach(audioBufferConsumer);
			this.startAudioBuffers = null;
		}

		long realTimestamp = timestamp + this.refTimestamp;

		if (this.lastFrame != null) {
			if (this.lastFrame.timestamp <= realTimestamp) {
				Frame frame = this.lastFrame;
				this.lastFrame = null;
				return frame;
			} else {
				return null;
			}
		}

		Frame frame;
		while ((frame = this.grabber.grab()) != null) {
			if (frame.image != null) {

				if (this.deltaTimestamp == 0) {
					this.deltaTimestamp = frame.timestamp - this.refTimestamp;
				}

				if (frame.timestamp <= realTimestamp) {
					// Delta of the current frame with the targeted timestamp
					long delta = realTimestamp - frame.timestamp;
					if (delta <= this.deltaTimestamp) {
						return frame;
					}
				} else {
					this.lastFrame = frame;
					break;
				}

			} else if (frame.samples != null) {
				audioBufferConsumer.accept(AudioStreamingBuffer.fromFrame(this.tempAudioBuffer, frame));
			}
		}

		return null;

	}

	public void grabRemaining(Consumer<AudioStreamingBuffer> audioBufferConsumer) throws IOException {
		Frame frame;
		while ((frame = this.grabber.grab()) != null) {
			if (frame.samples != null) {
				audioBufferConsumer.accept(AudioStreamingBuffer.fromFrame(this.tempAudioBuffer, frame));
			}
		}
	}

	/**
	 * Internal class that serves as an HTTP response subscriber that fills a given {@link ByteBuffer}.
	 * The buffer is automatically rewinded before pushing data into it.
	 */
	private static class BufferResponseSubscriber implements HttpResponse.BodySubscriber<Object> {

		private final CompletableFuture<Object> future = new CompletableFuture<>();
		private final ByteBuffer buffer;
		private Flow.Subscription subscription;

		public BufferResponseSubscriber(ByteBuffer buffer) {
			this.buffer = buffer;
		}

		@Override
		public CompletionStage<Object> getBody() {
			return this.future;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			if (this.subscription != null) {
				this.subscription.cancel();
			}
			this.subscription = subscription;
			this.subscription.request(Long.MAX_VALUE);
			// No clear here: the caller may have pre-filled the buffer (init segment);
			// the body is appended after it.
		}

		@Override
		public void onNext(List<ByteBuffer> item) {
			for (ByteBuffer buf : item) {
				try {
					this.buffer.put(buf);
				} catch (BufferOverflowException e) {
					WebStreamerClient.LOGGER.error("Cannot fill the full raw file buffer because of overflow. current pos: {}, incoming buf: {}", this.buffer.position(), buf.remaining());
					this.future.completeExceptionally(e);
				}
			}
		}

		@Override
		public void onError(Throwable throwable) {
			this.future.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			this.buffer.flip();
			this.future.complete(null);
		}

	}

}
