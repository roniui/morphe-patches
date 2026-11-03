/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.OfflineUi.ICON_BUTTON_DP;
import static app.morphe.extension.music.patches.downloads.OfflineUi.ROW_ARTWORK_PIXELS;
import static app.morphe.extension.music.patches.downloads.OfflineUi.background;
import static app.morphe.extension.music.patches.downloads.OfflineUi.foreground;
import static app.morphe.extension.music.patches.downloads.OfflineUi.rippleColor;
import static app.morphe.extension.music.patches.downloads.OfflineUi.secondary;
import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/** Local catalogue and offline player for downloaded music. */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener {

    private static final int SEEK_HEIGHT_DP = 3;
    private static final int SEEK_TOUCH_DP = 24;
    private static final int SEEK_THUMB_DP = 12;
    private static final long PLAYER_ANIMATION_MS = 220;

    private File musicRoot;
    private OfflineUi ui;
    private LinearLayout root;
    private LinearLayout songsList;
    private LinearLayout miniPlayer;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniPrevious;
    private ImageButton miniNext;
    private SeekBar miniSeek;

    private Dialog fullPlayerDialog;
    private ImageView fullArtwork;
    private TextView fullTitle;
    private TextView fullArtist;
    private TextView currentTime;
    private TextView remainingTime;
    private SeekBar fullSeek;
    private ImageButton fullPlay;
    private ImageButton fullPrevious;
    private ImageButton fullNext;
    private ImageButton shuffleButton;
    private ImageButton repeatButton;

    private String playingPath = "";
    private String title = "";
    private String artist = "";
    private int positionMs;
    private int durationMs;
    private boolean playing;
    private boolean seekingMini;
    private boolean seekingFull;
    private boolean showingPause;
    private List<String> queue = new ArrayList<>();
    private List<OfflineTrack> tracks = new ArrayList<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
        ui = new OfflineUi(getActivity());
        root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        ScrollView scroll = new ScrollView(getActivity());
        songsList = new LinearLayout(getActivity());
        songsList.setOrientation(LinearLayout.VERTICAL);
        songsList.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(12));
        scroll.addView(songsList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        loadTracks();
        renderTracks();

        miniPlayer = createMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    @Override public void onStart() {
        super.onStart();
        OfflinePlaybackService.addListener(this);
    }

    @Override public void onStop() {
        OfflinePlaybackService.removeListener(this);
        if (fullPlayerDialog != null && fullPlayerDialog.isShowing()) fullPlayerDialog.dismiss();
        super.onStop();
    }

    /** Kept for the downloads activity's back-navigation hook. */
    public boolean handleBack() {
        if (fullPlayerDialog != null && fullPlayerDialog.isShowing()) {
            closeFullPlayer();
            return true;
        }
        return false;
    }

    /** Kept for playlist page callers. */
    void closePlaylistPage() { }

    private void loadTracks() {
        tracks.clear();
        File[] files = musicRoot.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".webm") || file.getName().endsWith(".m4a")));
        if (files == null) return;
        for (File file : files) tracks.add(OfflineTrack.load(file));
        tracks.sort((first, second) -> first.displayTitle().compareToIgnoreCase(second.displayTitle()));
    }

    private void renderTracks() {
        songsList.removeAllViews();
        for (OfflineTrack track : tracks) {
            LinearLayout row = ui.trackRow();
            ui.addTrackInfo(row, track);
            row.setOnClickListener(v -> play(track, paths()));
            songsList.addView(row);
        }
        if (tracks.isEmpty()) {
            TextView empty = ui.text(str("morphe_music_downloads_empty"), 16, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, ui.dp(70), 0, 0);
            songsList.addView(empty);
        }
    }

    private List<String> paths() {
        List<String> result = new ArrayList<>();
        for (OfflineTrack track : tracks) result.add(track.audioFile().getAbsolutePath());
        return result;
    }

    void play(OfflineTrack track, List<String> queuePaths) {
        try {
            queue = new ArrayList<>(queuePaths);
            int index = Math.max(0, queue.indexOf(track.audioFile().getAbsolutePath()));
            OfflinePlaybackService.play(getActivity(), track, new ArrayList<>(queue), index);
        } catch (Exception ex) {
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        }
    }

    private LinearLayout createMiniPlayer() {
        LinearLayout outer = new LinearLayout(getActivity());
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());
        View divider = new View(getActivity());
        divider.setBackgroundColor(rippleColor());
        outer.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        outer.setPadding(0, 0, 0, ui.dp(10));

        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(12), 0, 0, 0);
        miniArtwork = ui.artworkView(null);
        row.addView(miniArtwork, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(ui.dp(12), 0, ui.dp(4), 0);
        miniTitle = ui.text("", 14, foreground());
        miniTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        miniTitle.setSingleLine(true);
        miniArtist = ui.text("", 13, secondary());
        miniArtist.setSingleLine(true);
        labels.addView(miniTitle);
        labels.addView(miniArtist);
        labels.setOnClickListener(v -> openFullPlayer());
        miniArtwork.setOnClickListener(v -> openFullPlayer());
        row.addView(labels, new LinearLayout.LayoutParams(0, ui.dp(56), 1));

        miniPrevious = ui.icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        miniPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        row.addView(miniPrevious, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        miniPlay = ui.icon("yt_fill_experimental_play_vd_theme_24", str("morphe_music_downloads_play_pause"));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        row.addView(miniPlay, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        miniNext = ui.icon("yt_fill_experimental_skip_next_vd_theme_24", str("morphe_music_downloads_next"));
        miniNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        row.addView(miniNext, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        outer.addView(row, new LinearLayout.LayoutParams(-1, ui.dp(60)));

        miniSeek = createSeekBar(true);
        outer.addView(miniSeek, new LinearLayout.LayoutParams(-1, ui.dp(SEEK_TOUCH_DP)));
        return outer;
    }

    private void openFullPlayer() {
        if (playingPath.isEmpty() || fullPlayerDialog != null && fullPlayerDialog.isShowing()) return;
        fullPlayerDialog = new Dialog(getActivity());
        fullPlayerDialog.getWindow();
        fullPlayerDialog.setContentView(createFullPlayerView());
        Window window = fullPlayerDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(background());
            window.setNavigationBarColor(background());
            window.setWindowAnimations(android.R.style.Animation_Activity);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        fullPlayerDialog.setOnDismissListener(dialog -> {
            fullPlayerDialog = null;
            if (miniPlayer != null) miniPlayer.setVisibility(playingPath.isEmpty() ? View.GONE : View.VISIBLE);
        });
        fullPlayerDialog.show();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        miniPlayer.setVisibility(View.GONE);
        refreshFullArtwork();
        updateFullPlayerState();
    }

    private View createFullPlayerView() {
        LinearLayout screen = new LinearLayout(getActivity());
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(background());
        screen.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(18));

        LinearLayout header = new LinearLayout(getActivity());
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton close = ui.icon("yt_outline_experimental_arrow_down_vd_theme_24",
                str("morphe_music_downloads_close"));
        close.setOnClickListener(v -> closeFullPlayer());
        header.addView(close, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        TextView nowPlaying = ui.text(str("morphe_music_downloads_now_playing"), 15, foreground());
        nowPlaying.setGravity(Gravity.CENTER);
        header.addView(nowPlaying, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton options = ui.icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_track_options"));
        options.setOnClickListener(v -> showCurrentTrackMenu(options));
        header.addView(options, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        screen.addView(header, new LinearLayout.LayoutParams(-1, -2));

        View spacer = new View(getActivity());
        screen.addView(spacer, new LinearLayout.LayoutParams(1, 0, .35f));
        fullArtwork = ui.artworkView(null);
        fullArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout artHolder = new LinearLayout(getActivity());
        artHolder.setGravity(Gravity.CENTER);
        screen.addView(artHolder, new LinearLayout.LayoutParams(-1, 0, 3));
        artHolder.addView(fullArtwork, new LinearLayout.LayoutParams(-1, -1));
        View lowerSpacer = new View(getActivity());
        screen.addView(lowerSpacer, new LinearLayout.LayoutParams(1, 0, .3f));

        fullTitle = ui.text("", 25, foreground());
        fullTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        fullTitle.setGravity(Gravity.CENTER);
        fullTitle.setSingleLine(true);
        fullArtist = ui.text("", 16, secondary());
        fullArtist.setGravity(Gravity.CENTER);
        fullArtist.setSingleLine(true);
        screen.addView(fullTitle, new LinearLayout.LayoutParams(-1, -2));
        screen.addView(fullArtist, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout times = new LinearLayout(getActivity());
        times.setGravity(Gravity.CENTER_VERTICAL);
        currentTime = ui.text("0:00", 12, secondary());
        remainingTime = ui.text("-0:00", 12, secondary());
        times.addView(currentTime, new LinearLayout.LayoutParams(-2, -2));
        fullSeek = createSeekBar(false);
        times.addView(fullSeek, new LinearLayout.LayoutParams(0, ui.dp(SEEK_TOUCH_DP), 1));
        times.addView(remainingTime, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams timesParams = new LinearLayout.LayoutParams(-1, -2);
        timesParams.topMargin = ui.dp(18);
        screen.addView(times, timesParams);

        LinearLayout controls = new LinearLayout(getActivity());
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, ui.dp(18), 0, ui.dp(8));
        fullPrevious = ui.icon("yt_fill_experimental_skip_previous_vd_theme_24", str("morphe_music_downloads_previous"));
        fullPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        controls.addView(fullPrevious, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        fullPlay = ui.icon("yt_fill_experimental_play_vd_theme_24", str("morphe_music_downloads_play_pause"));
        fullPlay.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        fullPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        controls.addView(fullPlay, new LinearLayout.LayoutParams(ui.dp(80), ui.dp(80)));
        fullNext = ui.icon("yt_fill_experimental_skip_next_vd_theme_24", str("morphe_music_downloads_next"));
        fullNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        controls.addView(fullNext, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        screen.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout modes = new LinearLayout(getActivity());
        modes.setGravity(Gravity.CENTER);
        shuffleButton = ui.icon("yt_outline_experimental_shuffle_vd_theme_24", str("morphe_music_downloads_shuffle"));
        shuffleButton.setOnClickListener(v -> {
            OfflinePlaybackService.toggleShuffle(getActivity());
            updateModeButtons();
        });
        repeatButton = ui.icon("yt_outline_experimental_repeat_vd_theme_24", repeatLabel());
        repeatButton.setOnClickListener(v -> {
            OfflinePlaybackService.cycleRepeat(getActivity());
            updateModeButtons();
        });
        modes.addView(shuffleButton, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        modes.addView(repeatButton, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        screen.addView(modes, new LinearLayout.LayoutParams(-1, -2));
        return screen;
    }

    private String repeatLabel() {
        return switch (OfflinePlaybackService.getRepeatMode()) {
            case OfflinePlaybackService.REPEAT_ALL -> str("morphe_music_downloads_repeat_all");
            case OfflinePlaybackService.REPEAT_ONE -> str("morphe_music_downloads_repeat_one");
            default -> str("morphe_music_downloads_repeat_off");
        };
    }

    private void updateModeButtons() {
        if (shuffleButton != null) {
            shuffleButton.setAlpha(OfflinePlaybackService.isShuffleEnabled() ? 1f : .55f);
        }
        if (repeatButton != null) {
            repeatButton.setContentDescription(repeatLabel());
            repeatButton.setAlpha(OfflinePlaybackService.getRepeatMode() == OfflinePlaybackService.REPEAT_OFF ? .55f : 1f);
        }
    }

    private void showCurrentTrackMenu(View anchor) {
        OfflineTrack track = OfflineTrack.load(new File(playingPath));
        String[] actions = {str("morphe_music_downloads_play"), str("morphe_music_downloads_delete_track")};
        showMenu(anchor, actions, choice -> {
            if (choice == 0) play(track, queue.isEmpty() ? paths() : queue);
            else confirmDelete(track);
        });
    }

    private void showMenu(View anchor, String[] choices, java.util.function.IntConsumer onPick) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(getActivity(), anchor);
        for (String choice : choices) popup.getMenu().add(choice);
        popup.setOnMenuItemClickListener(item -> {
            onPick.accept(item.getOrder());
            return true;
        });
        popup.show();
    }

    private void confirmDelete(OfflineTrack track) {
        CustomDialog.create(getActivity(), str("morphe_music_downloads_delete_track_title"),
                str("morphe_music_downloads_delete_track_message", track.displayTitle()), null,
                str("morphe_music_downloads_delete"), () -> {
                    track.audioFile().delete();
                    OfflineStorage.delete(track.artworkFile());
                    OfflineStorage.delete(new File(musicRoot, track.videoId() + ".json"));
                    loadTracks();
                    renderTracks();
                }, () -> { }, null, null, false).first.show();
    }

    private void closeFullPlayer() {
        if (fullPlayerDialog != null) fullPlayerDialog.dismiss();
    }

    private SeekBar createSeekBar(boolean mini) {
        SeekBar seek = new SeekBar(getActivity());
        seek.setPadding(ui.dp(mini ? 16 : 8), 0, ui.dp(mini ? 16 : 8), 0);
        seek.setProgressDrawable(createSeekBarDrawable());
        seek.setThumb(null);
        seek.setThumbOffset(0);
        seek.setBackground(null);
        seek.setSplitTrack(false);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                if (mini) seekingMini = true; else seekingFull = true;
                bar.setThumb(createThumb());
                bar.setThumbOffset(ui.dp(SEEK_THUMB_DP) / 2);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (mini) seekingMini = false; else seekingFull = false;
                bar.setThumb(null);
                OfflinePlaybackService.seekTo(bar.getProgress());
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { }
        });
        return seek;
    }

    private Drawable createThumb() {
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(ui.dp(SEEK_THUMB_DP));
        thumb.setIntrinsicHeight(ui.dp(SEEK_THUMB_DP));
        thumb.getPaint().setColor(foreground());
        return thumb;
    }

    private Drawable createSeekBarDrawable() {
        Drawable track = CustomDialog.createRoundedBackground(SEEK_HEIGHT_DP,
                Utils.adjustColorBrightness(foreground(), .3f));
        Drawable progress = CustomDialog.createRoundedBackground(SEEK_HEIGHT_DP, foreground());
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track,
                new ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        int inset = ui.dp((SEEK_TOUCH_DP - SEEK_HEIGHT_DP) / 2);
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        return layers;
    }

    private void refreshFullArtwork() {
        if (fullArtwork == null || playingPath.isEmpty()) return;
        OfflineTrack track = OfflineTrack.load(new File(playingPath));
        Bitmap bitmap = track.artwork(1024);
        if (bitmap == null) {
            fullArtwork.setImageDrawable(ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            fullArtwork.setColorFilter(foreground());
            fullArtwork.setPadding(ui.dp(48), ui.dp(48), ui.dp(48), ui.dp(48));
        } else {
            fullArtwork.clearColorFilter();
            fullArtwork.setPadding(0, 0, 0, 0);
            fullArtwork.setImageBitmap(bitmap);
            fullArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
    }

    @Override
    public void onPlaybackChanged(String path, String title, boolean playing, int position, int duration) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            this.playingPath = path;
            this.title = title;
            this.playing = playing;
            positionMs = position;
            durationMs = duration;
            boolean hasTrack = !title.isEmpty();
            if (miniPlayer == null) return;
            miniPlayer.setVisibility(hasTrack && (fullPlayerDialog == null || !fullPlayerDialog.isShowing())
                    ? View.VISIBLE : View.GONE);
            if (hasTrack) {
                miniTitle.setText(title);
                OfflineTrack track = OfflineTrack.load(new File(path));
                miniArtist.setText(track.displayArtist());
                Bitmap artwork = track.artwork(ROW_ARTWORK_PIXELS);
                if (artwork != null) {
                    miniArtwork.clearColorFilter();
                    miniArtwork.setPadding(0, 0, 0, 0);
                    miniArtwork.setImageBitmap(artwork);
                    miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
                }
            }
            setEnabled(miniPrevious, OfflinePlaybackService.hasPrevious());
            setEnabled(miniNext, OfflinePlaybackService.hasNext());
            setEnabled(fullPrevious, OfflinePlaybackService.hasPrevious());
            setEnabled(fullNext, OfflinePlaybackService.hasNext());
            updatePlayIcons();
            miniSeek.setMax(Math.max(1, duration));
            if (!seekingMini) miniSeek.setProgress(position);
            updateFullPlayerState();
            if (!path.equals(playingPath)) refreshFullArtwork();
        });
    }

    private void updatePlayIcons() {
        Drawable drawable = ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24" : "yt_fill_experimental_play_vd_theme_24");
        if (miniPlay != null) miniPlay.setImageDrawable(drawable);
        if (fullPlay != null) fullPlay.setImageDrawable(drawable);
    }

    private void updateFullPlayerState() {
        if (fullSeek == null) return;
        fullTitle.setText(title);
        if (!playingPath.isEmpty()) fullArtist.setText(OfflineTrack.load(new File(playingPath)).displayArtist());
        fullSeek.setMax(Math.max(1, durationMs));
        if (!seekingFull) fullSeek.setProgress(positionMs);
        currentTime.setText(OfflineUi.formatDuration(Math.max(0, positionMs / 1000)));
        remainingTime.setText("-" + OfflineUi.formatDuration(Math.max(0, (durationMs - positionMs) / 1000)));
        updateModeButtons();
    }

    private void setEnabled(@Nullable ImageButton button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }
}
