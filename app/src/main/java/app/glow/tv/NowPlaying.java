package app.glow.tv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.List;

/** Watches the system's active media sessions (Spotify, Amazon Music, YouTube Music...). */
final class NowPlaying {
    interface Listener { void onChange(String title, String artist, Bitmap art, boolean playing); }

    private final MediaSessionManager msm;
    private final ComponentName component;
    private final Listener out;
    private MediaController current;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) { pick(list); }
            };

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { publish(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { publish(); }
        @Override public void onSessionDestroyed() { detach(); out.onChange(null, null, null, false); }
    };

    NowPlaying(Context c, Listener l) {
        msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        component = new ComponentName(c, NowPlayingService.class);
        out = l;
    }

    /** Returns false if the user has not granted notification access yet. */
    boolean start() {
        if (started) return true;
        try {
            msm.addOnActiveSessionsChangedListener(sessions, component);
            pick(msm.getActiveSessions(component));
            started = true;
        } catch (SecurityException e) {
            started = false;
        }
        return started;
    }

    void stop() {
        if (started) {
            try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) {}
        }
        detach();
        started = false;
    }

    void toggle() {
        if (current == null) return;
        PlaybackState s = current.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) current.getTransportControls().pause();
        else current.getTransportControls().play();
    }

    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null && c.getMetadata() != null) best = c;
            }
        }
        if (best != null && current != null && best.getSessionToken().equals(current.getSessionToken())) {
            publish();
            return;
        }
        detach();
        current = best;
        if (current != null) current.registerCallback(cb);
        publish();
    }

    private void detach() {
        if (current != null) { current.unregisterCallback(cb); current = null; }
    }

    private void publish() {
        if (current == null) { out.onChange(null, null, null, false); return; }
        MediaMetadata m = current.getMetadata();
        PlaybackState s = current.getPlaybackState();
        int st = s == null ? PlaybackState.STATE_NONE : s.getState();
        boolean live = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED
                || st == PlaybackState.STATE_BUFFERING;
        String title = m == null ? null : text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_TITLE);
        if (!live || title == null) { out.onChange(null, null, null, false); return; }
        String artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        Bitmap art = bitmap(m, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        out.onChange(title, artist, art, st == PlaybackState.STATE_PLAYING);
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) { String v = m.getString(k); if (v != null && !v.isEmpty()) return v; }
        return null;
    }

    private static Bitmap bitmap(MediaMetadata m, String... keys) {
        for (String k : keys) { Bitmap b = m.getBitmap(k); if (b != null) return b; }
        return null;
    }
}
