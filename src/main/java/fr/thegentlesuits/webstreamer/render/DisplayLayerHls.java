package fr.thegentlesuits.webstreamer.render;

import fr.thegentlesuits.webstreamer.WebStreamerClient;
import fr.thegentlesuits.webstreamer.audio.AudioStreamingSource;
import fr.thegentlesuits.webstreamer.util.AsyncMap;
import fr.thegentlesuits.webstreamer.util.AsyncProcessor;
import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.model.MediaSegment;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * One instance per unique stream URI: follows the HLS media playlist, decodes segments
 * with {@link FrameGrabber}, uploads frames to a {@link DisplayTexture} and streams the
 * audio through an {@link AudioStreamingSource}. Every call happens on the render thread.
 *
 * <p>Ported from fabric-webstreamer by Theo Rozier (LGPL-3.0), flattened (HLS only) and
 * adapted to the 26.x texture pipeline.</p>
 */
@Environment(EnvType.CLIENT)
public class DisplayLayerHls {

	/** The timeout for a layer to be considered unused. */
	private static final long LAYER_UNUSED_TIMEOUT = 15L * 1000000000L;
	/** The latency forced, avoiding display freezes for loading. */
	private static final double SAFE_LATENCY = 8.0;
	/** The timeout for grabber's request. */
	private static final long GRABBER_REQUEST_TIMEOUT = 10L * 1000000000L;
	/** Interval of internal cleanups (unused grabbers). */
	private static final long CLEANUP_INTERVAL = 10L * 1000000000L;

	private static final long INITIAL_PLAYLIST_REQUEST_INTERVAL = 500000000L; // 0.5 seconds
	/** Interval of playlist requests when a past request has failed, to avoid spamming. */
	private static final long FAILING_PLAYLIST_REQUEST_INTERVAL = 5L * 1000000000L;

	private static int instanceCounter = 0;

	// Common //
	private final URI uri;
	private final DisplayLayerResources res;
	private final DisplayTexture tex;
	private final Identifier textureId;
	private final RenderType renderType;

	/** Time in nanoseconds (monotonic) of the last use. */
	private long lastUse = 0;

	// Playlist //
	private final MediaPlaylistParser hlsParser;
	private final AsyncProcessor<URI, MediaPlaylist, IOException> asyncPlaylist;
	private List<MediaSegment> playlistSegments;
	private int playlistOffset;
	private long playlistNextRequestTimestamp;
	private long playlistRequestInterval;
	private int playlistConsecutiveFailedRequest = 0;
	private long lastFetchTimestamp = 0;

	// Segment //
	private int segmentIndex = -1;
	private double segmentTimestamp = 0.0;
	private double segmentDuration = 0.0;

	// Grabber //

	/** One media segment to decode, with its optional fMP4 initialization segment. */
	private record GrabberRequest(URI segmentUri, URI initUri) { }

	private FrameGrabber grabber;
	private final AsyncMap<GrabberRequest, FrameGrabber, IOException> asyncGrabbers;
	/** Downloaded EXT-X-MAP initialization segments, keyed by URI (worker threads). */
	private final ConcurrentHashMap<URI, byte[]> initSegments = new ConcurrentHashMap<>();
	/**
	 * Current EXT-X-MAP URI for the playlist. The parser only attaches the map to the
	 * first segment following the tag, but per the HLS spec it applies to every
	 * subsequent segment — so it is remembered here for all of them.
	 */
	private URI currentInitUri;

	// Sound //
	private final AudioStreamingSource audioSource;
	private Vec3i nearestAudioPos;
	private float nearestAudioDist;
	private float nearestAudioDistance;
	private float nearestAudioVolume;

	// Timing //
	private long lastCleanup = 0;

	public DisplayLayerHls(URI uri, DisplayLayerResources res) {

		this.uri = uri;
		this.res = res;

		this.textureId = Identifier.fromNamespaceAndPath("webstreamer",
				"display/" + Integer.toHexString(uri.hashCode()) + "_" + (instanceCounter++));
		this.tex = new DisplayTexture(this.textureId.toString());
		// The GPU texture must exist before the first draw binds it — the first video
		// frame only arrives once the playlist is fetched. Dark gray, not black: the
		// quad is fullbright, so a loading screen stays visible even in dark rooms.
		this.tex.uploadSolid(24, 24, 30);
		Minecraft.getInstance().getTextureManager().register(this.textureId, this.tex);
		this.renderType = RenderTypes.text(this.textureId);

		this.hlsParser = new MediaPlaylistParser(ParsingMode.LENIENT);
		this.asyncPlaylist = new AsyncProcessor<>(this::requestPlaylistBlocking, true);
		this.asyncGrabbers = new AsyncMap<>(this::requestGrabberBlocking, grabber -> {
			WebStreamerClient.LOGGER.info(makeLog("Stopping requested but unused grabber."));
			grabber.stop();
		}, GRABBER_REQUEST_TIMEOUT);

		this.audioSource = new AudioStreamingSource();

		this.resetPlaylist();

		WebStreamerClient.LOGGER.info(makeLog("Allocate display layer: {}"), this.uri);

	}

	public RenderType renderType() {
		return this.renderType;
	}

	public void markUsed() {
		this.lastUse = System.nanoTime();
	}

	public int cost() {
		return 30;
	}

	/**
	 * Cleanup the layer if it has not been used for a long time (or forcibly with now == 0).
	 * @return True if the layer was freed and can be forgotten by the manager.
	 */
	public boolean cleanup(long now) {
		if (now == 0 || now - this.lastUse >= LAYER_UNUSED_TIMEOUT) {
			WebStreamerClient.LOGGER.info(makeLog("Free display layer: {}"), this.uri);
			Minecraft.getInstance().getTextureManager().release(this.textureId);
			this.asyncGrabbers.cleanup(this.res.getExecutor());
			if (this.grabber != null) {
				FrameGrabber grabber = this.grabber;
				this.res.getExecutor().execute(grabber::stop);
				this.grabber = null;
			}
			this.audioSource.free();
			return true;
		}
		return false;
	}

	/** True when the stream looks dead (repeated playlist failures) and users should re-resolve. */
	public boolean isLost() {
		return this.playlistConsecutiveFailedRequest >= 3;
	}

	// Audio //

	private void resetAudioSource() {
		if (this.nearestAudioPos != null) {
			this.audioSource.setPosition(this.nearestAudioPos);
			this.audioSource.setAttenuation(this.nearestAudioDistance);
			this.audioSource.setVolume(this.nearestAudioVolume);
		} else {
			this.audioSource.stop();
		}
		this.nearestAudioPos = null;
		this.nearestAudioDist = Float.MAX_VALUE;
		this.nearestAudioDistance = 0f;
		this.nearestAudioVolume = 0f;
	}

	/**
	 * Every display using this layer pushes its position and distance to the camera;
	 * the nearest one wins and becomes the audio emitter for the frame.
	 */
	public void pushAudioSource(Vec3i pos, float dist, float audioDistance, float audioVolume) {
		if (dist < this.nearestAudioDist) {
			this.nearestAudioPos = pos;
			this.nearestAudioDist = dist;
			this.nearestAudioDistance = audioDistance;
			this.nearestAudioVolume = audioVolume;
		}
	}

	// Playlist //

	private MediaSegment getSegment(int index) {
		try {
			return this.playlistSegments.get(index - this.playlistOffset);
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}

	private MediaSegment getCurrentSegment() {
		return this.getSegment(this.segmentIndex);
	}

	private int getLastSegmentIndex() {
		return this.playlistSegments == null ? 0 : this.playlistSegments.size() - 1 + this.playlistOffset;
	}

	private MediaPlaylist requestPlaylistBlocking(URI uri) throws IOException {
		try {
			HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(5)).build();
			HttpResponse<Stream<String>> res = this.res.getHttpClient()
					.send(request, HttpResponse.BodyHandlers.ofLines());
			if (res.statusCode() == 200) {
				return this.hlsParser.readPlaylist(res.body().iterator());
			} else {
				throw new IOException("HTTP request failed, status code: " + res.statusCode());
			}
		} catch (InterruptedException e) {
			throw new IOException(e);
		}
	}

	private void resetPlaylist() {
		this.playlistSegments = null;
		this.playlistNextRequestTimestamp = 0;
		this.playlistRequestInterval = INITIAL_PLAYLIST_REQUEST_INTERVAL;
	}

	private void requestPlaylist(long now) {
		if (now >= this.playlistNextRequestTimestamp) {
			this.asyncPlaylist.push(this.uri);
			this.playlistNextRequestTimestamp = now + this.playlistRequestInterval;
		}
	}

	private void fetchPlaylist() {
		this.asyncPlaylist.fetch(this.res.getExecutor(), playlist -> {
			this.playlistConsecutiveFailedRequest = 0;
			int newOffset = (int) playlist.mediaSequence();
			if (newOffset > this.playlistOffset) {
				this.playlistSegments = playlist.mediaSegments();
				this.playlistOffset = (int) playlist.mediaSequence();
				for (MediaSegment segment : this.playlistSegments) {
					segment.segmentMap().ifPresent(map -> this.currentInitUri = this.uri.resolve(map.uri()));
				}
				if (!this.playlistSegments.isEmpty()) {
					MediaSegment lastSegment = this.playlistSegments.get(this.playlistSegments.size() - 1);
					long newInterval = (long) (lastSegment.duration() * 1000000000.0 * 0.7);
					// Only change request interval if it represents more than 10% of the current interval.
					if (Math.abs(newInterval - this.playlistRequestInterval) >= this.playlistRequestInterval / 10) {
						this.playlistRequestInterval = newInterval;
					}
				}
			}
		}, e -> {
			// If failing, put timestamp to retry later.
			this.playlistRequestInterval = FAILING_PLAYLIST_REQUEST_INTERVAL;
			this.playlistConsecutiveFailedRequest++;
			WebStreamerClient.LOGGER.error(makeLog("Failed to request playlist, setting interval to {} seconds."), this.playlistRequestInterval / 1000000000, e);
		});
	}

	// Grabber //

	private FrameGrabber requestGrabberBlocking(GrabberRequest request) throws IOException {
		byte[] initData = null;
		if (request.initUri() != null) {
			initData = this.initSegments.get(request.initUri());
			if (initData == null) {
				initData = this.downloadInitSegment(request.initUri());
				this.initSegments.put(request.initUri(), initData);
			}
		}
		FrameGrabber grabber = new FrameGrabber(this.res, request.segmentUri(), initData);
		grabber.start();
		return grabber;
	}

	private byte[] downloadInitSegment(URI uri) throws IOException {
		try {
			HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(5)).build();
			HttpResponse<byte[]> res = this.res.getHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() != 200) {
				throw new IOException("init segment request failed, status code: " + res.statusCode());
			}
			WebStreamerClient.LOGGER.info(makeLog("Fetched init segment ({} bytes): {}"), res.body().length, uri);
			return res.body();
		} catch (InterruptedException e) {
			throw new IOException(e);
		}
	}

	private void requestGrabber(int index) {
		MediaSegment seg = this.getSegment(index);
		if (seg != null) {
			URI initUri = seg.segmentMap().map(map -> this.uri.resolve(map.uri())).orElse(this.currentInitUri);
			this.asyncGrabbers.push(this.res.getExecutor(),
					new GrabberRequest(this.uri.resolve(seg.uri()), initUri), index);
		}
	}

	private void pullGrabberAndUse(int index) {
		boolean requested = this.asyncGrabbers.pull(index, grabber -> {
			this.grabber = grabber;
		}, e -> WebStreamerClient.LOGGER.error(makeLog("Failed to create and start grabber."), e));
		if (!requested) {
			this.requestGrabber(index);
		}
	}

	private void resetGrabber(boolean toBeContinued) {
		if (this.grabber != null) {
			if (toBeContinued) {
				try {
					this.grabber.grabRemaining(this.audioSource::queueBuffer);
				} catch (IOException e) {
					WebStreamerClient.LOGGER.error(makeLog("Failed to grab remaining from current grabber."), e);
				}
			} else {
				this.audioSource.stop();
			}
			FrameGrabber grabber = this.grabber;
			this.res.getExecutor().execute(grabber::stop);
			this.grabber = null;
		}
	}

	private void fetch() throws IOException {

		long now = System.nanoTime();
		double elapsedTime = ((double) (now - this.lastFetchTimestamp) / 1000000000.0);
		this.lastFetchTimestamp = now;

		if (this.playlistSegments != null) {

			// Tries to pull the playlist if being requested.
			this.fetchPlaylist();

			// This algorithm tries to go forward in segments by elapsedTime.
			double remainingTime = elapsedTime;

			boolean resetPlaylist = false;
			boolean resetGrabber = false;

			for (;;) {

				// If we are too slow and the current segment is now out of the playlist.
				if (this.getCurrentSegment() == null) {
					WebStreamerClient.LOGGER.warn(makeLog("No current segment, reset playlist and grabber"));
					resetPlaylist = true;
					resetGrabber = true;
					break;
				}

				this.segmentTimestamp += remainingTime;
				if (this.segmentTimestamp > this.segmentDuration) {

					this.segmentIndex++;
					MediaSegment seg = this.getCurrentSegment();

					if (seg == null) {
						WebStreamerClient.LOGGER.warn(makeLog("No next segment, reset playlist and grabber"));
						resetPlaylist = true;
						resetGrabber = true;
						break;
					}

					resetGrabber = true;
					remainingTime = this.segmentTimestamp - this.segmentDuration;
					this.segmentDuration = seg.duration();
					this.segmentTimestamp = 0;

				} else {
					break;
				}

			}

			if (resetPlaylist) {
				this.resetPlaylist();
				this.resetGrabber(false);
			} else {

				if (resetGrabber) {
					this.resetGrabber(true);
				}

				int offsetFromLastSegment = this.getLastSegmentIndex() - this.segmentIndex;

				if (offsetFromLastSegment <= 1) {
					// We are at most 1 segment from the end, so request a new playlist.
					this.requestPlaylist(now);
				}

				if (offsetFromLastSegment >= 1) {
					// If we have at least one segment after the current one, preload it.
					this.requestGrabber(this.segmentIndex + 1);
				}

			}

		}

		if (this.playlistSegments == null) {

			if (this.asyncPlaylist.requested() || this.asyncPlaylist.active()) {
				// After request, we go here.
				this.fetchPlaylist();
				if (this.playlistSegments == null)
					return;
			} else {
				this.requestPlaylist(now);
				return;
			}

			WebStreamerClient.LOGGER.info(makeLog("Initializing display layer... Found {} segments."), this.playlistSegments.size());

			double totalDuration = 0.0;
			for (MediaSegment seg : this.playlistSegments) {
				totalDuration += seg.duration();
			}

			double globalTimestamp = totalDuration;

			this.segmentIndex = this.playlistOffset + (this.playlistSegments.size() - 1);
			MediaSegment seg = this.getCurrentSegment();

			this.segmentDuration = seg.duration();
			this.segmentTimestamp = this.segmentDuration;

			for (;;) {

				double latency = totalDuration - globalTimestamp;
				double latencyDiff = SAFE_LATENCY - latency;

				if (seg.duration() >= latencyDiff) {
					this.segmentTimestamp -= latencyDiff;
					break;
				} else {
					this.segmentIndex -= 1;
					seg = this.getCurrentSegment();
					if (seg == null) {
						this.segmentIndex = 0;
						this.segmentTimestamp = 0;
						break;
					} else {
						globalTimestamp -= seg.duration();
						this.segmentDuration = seg.duration();
						this.segmentTimestamp = this.segmentDuration;
					}
				}

			}

		}

		// Grabbing and uploading section...

		// If the grabber is in reset state, try to get it.
		if (this.grabber == null) {
			this.pullGrabberAndUse(this.segmentIndex);
			if (this.grabber == null) {
				// Abort if not ready to use.
				return;
			}
		}

		long segmentTimestampMicros = (long) (this.segmentTimestamp * 1000000);

		Frame frame = this.grabber.grabAt(segmentTimestampMicros, this.audioSource::queueBuffer);

		if (frame != null) {
			this.tex.upload(frame);
			this.audioSource.playFrom(frame.timestamp);
		}

	}

	/** Per-frame tick, render thread only. */
	public void tick() {

		if (!this.isLost()) {
			// Only fetch if this layer is not lost, because if it's lost, it should be
			// cleaned up soon.
			try {
				this.fetch();
			} catch (IOException e) {
				WebStreamerClient.LOGGER.error(makeLog("Failed to fetch."), e);
			}
		}

		long now = System.nanoTime();
		if (now - this.lastCleanup >= CLEANUP_INTERVAL) {
			this.asyncGrabbers.cleanupTimedOut(this.res.getExecutor(), now);
			this.lastCleanup = now;
		}

		this.resetAudioSource();

	}

	private String makeLog(String message) {
		return String.format("[%08X] ", this.uri.hashCode()) + message;
	}

}
