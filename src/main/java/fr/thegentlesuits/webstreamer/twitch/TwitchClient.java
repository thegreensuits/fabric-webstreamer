package fr.thegentlesuits.webstreamer.twitch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.thegentlesuits.webstreamer.WebStreamerClient;
import fr.thegentlesuits.webstreamer.playlist.Playlist;
import fr.thegentlesuits.webstreamer.playlist.PlaylistQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

/**
 * Resolves a Twitch channel name to its HLS playlist (one URI per quality) using
 * Twitch's public GraphQL endpoint, with the well-known web player client id.
 *
 * <p>Ported from fabric-webstreamer by Theo Rozier (LGPL-3.0).</p>
 */
@Environment(EnvType.CLIENT)
public class TwitchClient {

	private final Gson gson = new GsonBuilder().create();
	private final String clientId;

	private final HashMap<String, Playlist> cache = new HashMap<>();

	public TwitchClient(String clientId) {
		this.clientId = clientId;
	}

	public TwitchClient() {
		this("kimne78kx3ncx6brgo4mv6wki5h1ko");
	}

	private Playlist requestPlaylistInternal(String channel) throws PlaylistException, IOException, URISyntaxException, InterruptedException {

		if (channel.isEmpty()) {
			throw new PlaylistException(PlaylistExceptionType.CHANNEL_NOT_FOUND);
		}

		URI gqlUri = new URI("https://gql.twitch.tv/gql");

		HttpClient client = HttpClient.newHttpClient();

		JsonObject body = new JsonObject();
		body.addProperty("operationName", "PlaybackAccessToken");
		JsonObject extensions = new JsonObject();
		JsonObject persistedQuery = new JsonObject();
		persistedQuery.addProperty("version", 1);
		persistedQuery.addProperty("sha256Hash", "0828119ded1c13477966434e15800ff57ddacf13ba1911c129dc2200705b0712");
		extensions.add("persistedQuery", persistedQuery);
		body.add("extensions", extensions);
		JsonObject variables = new JsonObject();
		variables.addProperty("isLive", true);
		variables.addProperty("login", channel);
		variables.addProperty("isVod", false);
		variables.addProperty("vodID", "");
		variables.addProperty("playerType", "embed");
		body.add("variables", variables);

		HttpRequest tokenReq = HttpRequest.newBuilder(gqlUri)
				.POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body)))
				.header("Accept", "application/json")
				.headers("Client-id", this.clientId)
				.build();

		HttpResponse<String> tokenRes = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());

		if (tokenRes.statusCode() != 200) {
			throw new PlaylistException(PlaylistExceptionType.NO_TOKEN);
		}

		JsonObject tokenResJson = this.gson.fromJson(tokenRes.body(), JsonObject.class);
		JsonElement tokenRaw = tokenResJson.getAsJsonObject("data").get("streamPlaybackAccessToken");
		if (!tokenRaw.isJsonObject()) {
			throw new PlaylistException(PlaylistExceptionType.CHANNEL_NOT_FOUND);
		}

		JsonObject token = tokenRaw.getAsJsonObject();

		String tokenValue = URLEncoder.encode(token.get("value").getAsString(), StandardCharsets.UTF_8);
		String tokenSignature = token.get("signature").getAsString();
		URI urlsUri = new URL("https://usher.ttvnw.net/api/channel/hls/" + channel + ".m3u8?client_id=" + this.clientId + "&token=" + tokenValue + "&sig=" + tokenSignature + "&allow_source=true&allow_audio_only=false").toURI();

		HttpRequest urlsReq = HttpRequest.newBuilder(urlsUri)
				.GET()
				.header("Accept", "application/json")
				.build();

		HttpResponse<String> urlsRes = client.send(urlsReq, HttpResponse.BodyHandlers.ofString());

		if (urlsRes.statusCode() == 404) {
			if (urlsRes.body().contains("Can not find channel")) {
				throw new PlaylistException(PlaylistExceptionType.CHANNEL_NOT_FOUND);
			} else {
				throw new PlaylistException(PlaylistExceptionType.CHANNEL_OFFLINE);
			}
		} else if (urlsRes.statusCode() != 200) {
			throw new PlaylistException(PlaylistExceptionType.UNKNOWN);
		}

		String raw = urlsRes.body();
		List<String> rawLines = raw.lines().toList();

		Playlist playlist = new Playlist(channel);
		playlist.setTitle(this.requestStreamTitle(client, gqlUri, channel));

		// Qualities are identified by GROUP-ID ("chunked", "720p60", …) — the ids the
		// protocol uses — not by NAME ("1080p60 (source)", "480p", …). Variant order in
		// the manifest is arbitrary, so pair each #EXT-X-MEDIA with the next URL line.
		String pendingGroupId = null;
		for (String line : rawLines) {
			if (line.startsWith("#EXT-X-MEDIA:")) {
				int groupIdStartIdx = line.indexOf("GROUP-ID=\"");
				int groupIdStopIdx = line.indexOf('"', groupIdStartIdx + 10);
				if (groupIdStartIdx >= 0 && groupIdStopIdx > groupIdStartIdx) {
					pendingGroupId = line.substring(groupIdStartIdx + 10, groupIdStopIdx);
				}
			} else if (!line.startsWith("#") && !line.isBlank() && pendingGroupId != null) {
				playlist.addQuality(new PlaylistQuality(pendingGroupId, new URI(line)));
				pendingGroupId = null;
			}
		}

		return playlist;

	}

	/**
	 * Fetches the channel's current broadcast title through the same GQL endpoint.
	 * Best-effort and blocking: any failure returns null. Also used standalone to
	 * refresh the title of a playing stream (the playlist cache is not involved).
	 */
	public String requestStreamTitle(String channel) {
		try {
			return this.requestStreamTitle(HttpClient.newHttpClient(),
					new URI("https://gql.twitch.tv/gql"), channel);
		} catch (URISyntaxException e) {
			return null;
		}
	}

	private String requestStreamTitle(HttpClient client, URI gqlUri, String channel) {
		try {
			JsonObject body = new JsonObject();
			body.addProperty("query", "query($login: String!) { user(login: $login) { broadcastSettings { title } } }");
			JsonObject variables = new JsonObject();
			variables.addProperty("login", channel);
			body.add("variables", variables);

			HttpRequest titleReq = HttpRequest.newBuilder(gqlUri)
					.POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body)))
					.header("Accept", "application/json")
					.headers("Client-id", this.clientId)
					.build();

			HttpResponse<String> titleRes = client.send(titleReq, HttpResponse.BodyHandlers.ofString());
			if (titleRes.statusCode() != 200) {
				WebStreamerClient.LOGGER.warn("Stream title request for {} returned status {}.",
						channel, titleRes.statusCode());
				return null;
			}

			JsonElement user = this.gson.fromJson(titleRes.body(), JsonObject.class)
					.getAsJsonObject("data").get("user");
			if (!user.isJsonObject()) {
				return null;
			}
			JsonElement settings = user.getAsJsonObject().get("broadcastSettings");
			if (!settings.isJsonObject()) {
				return null;
			}
			JsonElement title = settings.getAsJsonObject().get("title");
			if (title == null || !title.isJsonPrimitive()) {
				return null;
			}
			String value = title.getAsString();
			return value.isBlank() ? null : value;
		} catch (Exception e) {
			WebStreamerClient.LOGGER.warn("Failed to fetch stream title for {}: {}", channel, e.toString());
			return null;
		}
	}

	public Playlist requestPlaylist(String channel) throws PlaylistException {
		synchronized (this.cache) {
			Playlist playlist = this.cache.get(channel);
			if (playlist == null) {
				try {
					playlist = this.requestPlaylistInternal(channel);
					this.cache.put(channel, playlist);
				} catch (IOException | URISyntaxException | InterruptedException e) {
					throw new PlaylistException(PlaylistExceptionType.UNKNOWN);
				}
			}
			return playlist;
		}
	}

	public void forgetPlaylist(String channel) {
		synchronized (this.cache) {
			this.cache.remove(channel);
		}
	}

	public enum PlaylistExceptionType {
		UNKNOWN,
		NO_TOKEN,
		CHANNEL_NOT_FOUND,
		CHANNEL_OFFLINE,
	}

	public static class PlaylistException extends Exception {

		private final PlaylistExceptionType exceptionType;

		public PlaylistException(PlaylistExceptionType exceptionType) {
			super(exceptionType.name());
			this.exceptionType = exceptionType;
		}

		public PlaylistExceptionType getExceptionType() {
			return exceptionType;
		}

	}

}
