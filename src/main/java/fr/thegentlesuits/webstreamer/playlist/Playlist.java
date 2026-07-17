package fr.thegentlesuits.webstreamer.playlist;

import java.util.ArrayList;
import java.util.List;

/**
 * A generic playlist data structure that defines different URIs for each quality
 * available on a channel.
 *
 * <p>Ported from fabric-webstreamer by Theo Rozier (LGPL-3.0).</p>
 */
public class Playlist {

	private final String channel;
	private final ArrayList<PlaylistQuality> qualities = new ArrayList<>();
	private String title;

	public Playlist(String channel) {
		this.channel = channel;
	}

	public String getChannel() {
		return channel;
	}

	/** Stream title from Twitch, null when it could not be fetched. */
	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public void addQuality(PlaylistQuality quality) {
		this.qualities.add(quality);
	}

	public List<PlaylistQuality> getQualities() {
		return qualities;
	}

	public PlaylistQuality getQuality(String quality) {
		return this.qualities.stream().filter(pl -> pl.name().equals(quality)).findFirst().orElse(null);
	}

}
