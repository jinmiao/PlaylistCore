/*
 * Copyright (C) 2016 Brian Wernick
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.devbrackets.android.playlistcore.service;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.FloatRange;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;

import com.devbrackets.android.playlistcore.annotation.ServiceContinuationMethod;
import com.devbrackets.android.playlistcore.annotation.SupportedMediaType;
import com.devbrackets.android.playlistcore.api.AudioPlayerApi;
import com.devbrackets.android.playlistcore.api.MediaPlayerApi;
import com.devbrackets.android.playlistcore.api.VideoPlayerApi;
import com.devbrackets.android.playlistcore.event.MediaProgress;
import com.devbrackets.android.playlistcore.event.PlaylistItemChange;
import com.devbrackets.android.playlistcore.helper.AudioFocusHelper;
import com.devbrackets.android.playlistcore.listener.OnMediaBufferUpdateListener;
import com.devbrackets.android.playlistcore.listener.OnMediaCompletionListener;
import com.devbrackets.android.playlistcore.listener.OnMediaErrorListener;
import com.devbrackets.android.playlistcore.listener.OnMediaPreparedListener;
import com.devbrackets.android.playlistcore.listener.OnMediaSeekCompletionListener;
import com.devbrackets.android.playlistcore.listener.PlaylistListener;
import com.devbrackets.android.playlistcore.listener.ProgressListener;
import com.devbrackets.android.playlistcore.manager.BasePlaylistManager;
import com.devbrackets.android.playlistcore.manager.IPlaylistItem;
import com.devbrackets.android.playlistcore.util.MediaProgressPoll;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.images.WebImage;

/**
 * A base service for adding media playback support using the {@link BasePlaylistManager}.
 * <p>
 * This service will request a wifi wakelock if the item being played isn't
 * downloaded (see {@link #isDownloaded(IPlaylistItem)}) and the manifest permission
 * &lt;uses-permission android:name="android.permission.WAKE_LOCK" /&gt;
 * has been specified.  This will allow the audio to be played without interruptions.
 * <p>
 * Due to not knowing the actual class for the playlist service, if you want to handle
 * audio becoming noisy (e.g. when a headphone cable is pulled out) then you will need
 * to create your own {@link android.content.BroadcastReceiver} as outlined at
 * <a href="http://developer.android.com/guide/topics/media/mediaplayer.html#noisyintent">
 * http://developer.android.com/guid/topics/media/mediaplayer.html#noisyintent</a>
 */
@SuppressWarnings("unused")
public abstract class PlaylistServiceCore<I extends IPlaylistItem, M extends BasePlaylistManager<I>> extends Service implements AudioFocusHelper.AudioFocusCallback, ProgressListener {
    private static final String TAG = "PlaylistServiceCore";

    public enum PlaybackState {
        RETRIEVING,    // the MediaRetriever is retrieving music
        PREPARING,     // Preparing / Buffering
        PLAYING,       // Active but could be paused due to loss of audio focus Needed for returning after we regain focus
        PAUSED,        // Paused but player ready
        SEEKING,       // performSeek was called, awaiting seek completion callback
        STOPPED,       // Stopped not preparing media
        ERROR          // An error occurred, we are stopped
    }

    @Nullable //Null if the WAKE_LOCK permission wasn't requested
    protected WifiManager.WifiLock wifiLock;
    @Nullable
    protected AudioFocusHelper audioFocusHelper;

    @Nullable
    protected AudioPlayerApi audioPlayer;
    @NonNull
    protected MediaProgressPoll mediaProgressPoll = new MediaProgressPoll();
    @NonNull
    protected MediaListener mediaListener = new MediaListener();
    @NonNull
    protected MediaProgress currentMediaProgress = new MediaProgress(0, 0, 0);

    @NonNull
    protected PlaybackState currentState = PlaybackState.PREPARING;

    @Nullable
    protected I currentPlaylistItem;
    protected long seekToPosition = -1;

    protected boolean pausedForSeek = false;
    protected boolean playingBeforeSeek = false;
    protected boolean immediatelyPause = false; //TODO: in the next major release rename this to startPaused
    protected boolean pausedForFocusLoss = false;
    protected boolean onCreateCalled = false;

    @Nullable
    protected Intent workaroundIntent = null;

    // Chrome Cast
    private CastContext mCastContext;
    private SessionManagerListener<CastSession> mSessionManagerListener;
    private RemoteMediaClient.Listener mRemoteMediaClientListener;
    private RemoteMediaClient mRemoteMediaClient;

    /**
     * Retrieves a new instance of the {@link AudioPlayerApi}. This will only
     * be called the first time an Audio Player is needed; afterwards a cached
     * instance will be used.
     *
     * @return The {@link AudioPlayerApi} to use for playback
     */
    @NonNull
    protected abstract AudioPlayerApi getNewAudioPlayer();

    protected abstract void setupAsForeground();

    protected abstract void setupForeground();

    protected abstract void stopForeground();

    /**
     * Retrieves the volume level to use when audio focus has been temporarily
     * lost due to a higher importance notification.  The media will continue
     * playback but be reduced to the specified volume for the duration of the
     * notification.  This usually happens when receiving an alert for MMS, EMail,
     * etc.
     *
     * @return The volume level to use when a temporary notification sounds. [0.0 - 1.0]
     */
    @FloatRange(from = 0.0, to = 1.0)
    protected abstract float getAudioDuckVolume();

    /**
     * Links the {@link BasePlaylistManager} that contains the information for playback
     * to this service.
     * <p>
     * NOTE: this is only used for retrieving information, it isn't used to register notifications
     * for playlist changes, however as long as the change isn't breaking (e.g. cleared playlist)
     * then nothing additional needs to be performed.
     *
     * @return The {@link BasePlaylistManager} containing the playback information
     */
    @NonNull
    protected abstract M getPlaylistManager();

    /**
     * Retrieves the continuity bits associated with the service.  These
     * are the bits returned by {@link #onStartCommand(Intent, int, int)} and can be
     * one of the {@link #START_CONTINUATION_MASK} values.
     *
     * @return The continuity bits for the service [default: {@link #START_NOT_STICKY}]
     */
    @ServiceContinuationMethod
    public int getServiceContinuationMethod() {
        return START_NOT_STICKY;
    }

    /**
     * Used to determine if the device is connected to a network that has
     * internet access.  This is used in conjunction with {@link #isDownloaded(IPlaylistItem)}
     * to determine what items in the playlist manager, specified with {@link #getPlaylistManager()}, can be
     * played.
     *
     * @return True if the device currently has internet connectivity
     */
    protected boolean isNetworkAvailable() {
        return true;
    }

    /**
     * Used to determine if the specified playlistItem has been downloaded.  If this is true
     * then the downloaded copy will be used instead, and no network wakelock will be acquired.
     *
     * @param playlistItem The playlist item to determine if it is downloaded.
     * @return True if the specified playlistItem is downloaded. [default: false]
     */
    protected boolean isDownloaded(I playlistItem) {
        return false;
    }

    /**
     * Called when the {@link #performStop()} has been called.
     *
     * @param playlistItem The playlist item that has been stopped
     */
    protected void onMediaStopped(I playlistItem) {
        //Purposefully left blank
    }

    /**
     * Called when a current media item has ended playback.  This is called when we
     * are unable to play an item.
     *
     * @param playlistItem    The PlaylistItem that has ended
     * @param currentPosition The position the playlist item ended at
     * @param duration        The duration of the PlaylistItem
     */
    protected void onMediaPlaybackEnded(I playlistItem, long currentPosition, long duration) {
        //Purposefully left blank
    }

    /**
     * Called when a media item has started playback.
     *
     * @param playlistItem    The PlaylistItem that has started playback
     * @param currentPosition The position the playback has started at
     * @param duration        The duration of the PlaylistItem
     */
    protected void onMediaPlaybackStarted(I playlistItem, long currentPosition, long duration) {
        //Purposefully left blank
    }

    /**
     * Called when the service is unable to seek to the next playable item when
     * no network is available.
     */
    protected void onNoNonNetworkItemsAvailable() {
        //Purposefully left blank
    }

    /**
     * Called when a media item in playback has ended
     */
    protected void onMediaPlaybackEnded() {
        //Purposefully left blank
    }

    /**
     * Called when the notification needs to be updated.
     * This occurs when playback state updates or the current
     * item in playback is changed.
     */
    protected void updateNotification() {
        //Purposefully left blank
    }

    /**
     * Similar to {@link #updateNotification()}, this is called when
     * the remote views need to be updated due to playback state
     * updates and item changes.
     * <p>
     * The remote views handle the lock screen, bluetooth controls,
     * Android Wear interactions, etc.
     */
    protected void updateRemoteViews() {
        //Purposefully left blank
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        //Part of a workaround for some Samsung devices (see onStartCommand)
        if (onCreateCalled) {
            return;
        }

        onCreateCalled = true;
        super.onCreate();
        Log.d(TAG, "Service Created");

        onServiceCreate();

        // Chrome Cast
        try {
            if (isGooglePlayServicesAvailable()) {
                setupCastListener();
                mCastContext = CastContext.getSharedInstance(this);
                if (mCastContext != null) {
                    mCastContext.getSessionManager().addSessionManagerListener(mSessionManagerListener, CastSession.class);
                }
                Log.d(TAG, "mCastContext :" + (mCastContext == null) + ", mRemoteMediaClient :" + (mRemoteMediaClient == null));
            }

        } catch (NullPointerException e) {
        }
    }

    /**
     * Stops the current media in playback and releases all
     * held resources.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service Destroyed");

        taskRemove();
    }

    private void taskRemove() {
        setPlaybackState(PlaybackState.STOPPED);

        relaxResources(true);
        getPlaylistManager().unRegisterService();

        if (audioFocusHelper != null) {
            audioFocusHelper.setAudioFocusCallback(null);
            audioFocusHelper = null;
        }

        onCreateCalled = false;

        try {
            if (mCastContext != null) {
                mCastContext.getSessionManager().removeSessionManagerListener(mSessionManagerListener, CastSession.class);
            }
        } catch (NullPointerException e) {
        }
    }

    /**
     * Handles the intents posted by the {@link BasePlaylistManager} through
     * the <code>invoke*</code> methods.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return getServiceContinuationMethod();
        }

        //This is a workaround for an issue on the Samsung Galaxy S3 (4.4.2) where the onStartCommand will occasionally get called before onCreate
        if (!onCreateCalled) {
            Log.d(TAG, "Starting Samsung workaround");
            workaroundIntent = intent;
            onCreate();
            return getServiceContinuationMethod();
        }

        if (RemoteActions.ACTION_START_SERVICE.equals(intent.getAction())) {
            seekToPosition = intent.getLongExtra(RemoteActions.ACTION_EXTRA_SEEK_POSITION, -1);
            immediatelyPause = intent.getBooleanExtra(RemoteActions.ACTION_EXTRA_START_PAUSED, false);

            startItemPlayback();
        } else {
            handleRemoteAction(intent.getAction(), intent.getExtras());
        }

        return getServiceContinuationMethod();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        taskRemove();
    }

    /**
     * Updates the playback state and volume based on the
     * previous state held before {@link #onAudioFocusLost(boolean)}
     * was called.
     */
    @Override
    public boolean onAudioFocusGained() {
        if (!handleVideoAudioFocus() && currentItemIsType(BasePlaylistManager.VIDEO)) {
            return false;
        }

        //Returns the audio to the previous playback state and volume
        if (!isPlaying() && pausedForFocusLoss) {
            pausedForFocusLoss = false;
            performPlay();
        } else {
            setVolume(1.0f, 1.0f); //reset the audio volume
        }

        return true;
    }

    /**
     * Audio focus is lost either temporarily or permanently due
     * to external changes such as notification sounds and
     * phone calls.  When focus is lost temporarily, the
     * audio volume will decrease for the duration of the focus
     * loss (returned to normal in {@link #onAudioFocusGained()}.
     * If the focus is lost permanently then the audio will pause
     * playback and attempt to resume when {@link #onAudioFocusGained()}
     * is called.
     */
    @Override
    public boolean onAudioFocusLost(boolean canDuckAudio) {
        if (!handleVideoAudioFocus() && currentItemIsType(BasePlaylistManager.VIDEO)) {
            return false;
        }

        //Either pauses or reduces the volume of the audio in playback
        if (!canDuckAudio) {
            if (isPlaying()) {
                pausedForFocusLoss = true;
                performPause();
            }
        } else {
            setVolume(getAudioDuckVolume(), getAudioDuckVolume());
        }

        return true;
    }

    /**
     * When the current media progress is updated we call through the
     * {@link BasePlaylistManager} to inform any listeners of the change
     */
    @Override
    public boolean onProgressUpdated(@NonNull MediaProgress mediaProgress) {
        currentMediaProgress = mediaProgress;

        if (isCastSessionConnected()) {
            if (mRemoteMediaClient == null)
                mRemoteMediaClient = getRemoteMediaClient();

            if (mRemoteMediaClient != null)
                currentMediaProgress.update(mRemoteMediaClient.getApproximateStreamPosition(), 0, mRemoteMediaClient.getStreamDuration());
        }

        return getPlaylistManager().onProgressUpdated(mediaProgress);
    }

    /**
     * Retrieves the current playback state of the service.
     *
     * @return The current playback state
     */
    public PlaybackState getCurrentPlaybackState() {
        return currentState;
    }

    /**
     * Retrieves the current playback progress.
     *
     * @return The current playback progress
     */
    @NonNull
    public MediaProgress getCurrentMediaProgress() {
        return currentMediaProgress;
    }

    /**
     * Retrieves the current item change event which represents any media item changes.
     * This is intended as a utility method for initializing, or returning to, a media
     * playback UI.  In order to get the changed events you will need to register for
     * callbacks through {@link BasePlaylistManager#registerPlaylistListener(PlaylistListener)}
     *
     * @return The current PlaylistItem Changed event
     */
    public PlaylistItemChange<I> getCurrentItemChange() {
        boolean hasNext = getPlaylistManager().isNextAvailable();
        boolean hasPrevious = getPlaylistManager().isPreviousAvailable();

        return new PlaylistItemChange<>(currentPlaylistItem, hasPrevious, hasNext);
    }

    /**
     * Used to perform the onCreate functionality when the service is actually created.  This
     * should be overridden instead of {@link #onCreate()} due to a bug in some Samsung devices
     * where {@link #onStartCommand(Intent, int, int)} will get called before {@link #onCreate()}.
     */
    protected void onServiceCreate() {
        mediaProgressPoll.setProgressListener(this);
        audioFocusHelper = new AudioFocusHelper(getApplicationContext());
        audioFocusHelper.setAudioFocusCallback(this);

        //Attempts to obtain the wifi lock only if the manifest has requested the permission
        if (getPackageManager().checkPermission(Manifest.permission.WAKE_LOCK, getPackageName()) == PackageManager.PERMISSION_GRANTED) {
            wifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL, "mcLock");
            wifiLock.setReferenceCounted(false);
        } else {
            Log.w(TAG, "Unable to acquire WAKE_LOCK due to missing manifest permission");
        }

        getPlaylistManager().registerService(this);

        //Another part of the workaround for some Samsung devices
        if (workaroundIntent != null) {
            startService(workaroundIntent);
            workaroundIntent = null;
        }
    }

    /**
     * A generic method to determine if media is currently playing.  This is
     * used to determine the playback state for the notification.
     *
     * @return True if media is currently playing
     */
    protected boolean isPlaying() {
        Log.d(TAG, "isPlaying isCastSessionConnected():" + isCastSessionConnected());
        if (currentItemIsType(BasePlaylistManager.AUDIO)) {
            if (isCastSessionConnected()) {
                if (mRemoteMediaClient == null)
                    mRemoteMediaClient = getRemoteMediaClient();

                return mRemoteMediaClient != null && mRemoteMediaClient.isPlaying();
            } else {
                return audioPlayer != null && audioPlayer.isPlaying();
            }
        } else if (currentItemIsType(BasePlaylistManager.VIDEO)) {
            return getPlaylistManager().getVideoPlayer() != null && getPlaylistManager().getVideoPlayer().isPlaying();
        }

        return false;
    }

    /**
     * Performs the functionality to pause and/or resume
     * the media playback.  This is called through an intent
     * with the {@link RemoteActions#ACTION_PLAY_PAUSE}, through
     * {@link BasePlaylistManager#invokePausePlay()}
     */
    protected void performPlayPause() {
        if (isPlaying()) {
            performPause();
        } else {
            performPlay();
        }
    }

    /**
     * Performs the functionality to seek to the previous media
     * item.  This is called through an intent
     * with the {@link RemoteActions#ACTION_PREVIOUS}, through
     * {@link BasePlaylistManager#invokePrevious()}
     */
    protected void performPrevious() {
        seekToPosition = 0;
        immediatelyPause = !isPlaying();

        getPlaylistManager().previous();
        startItemPlayback();
    }

    /**
     * Performs the functionality to seek to the next media
     * item.  This is called through an intent
     * with the {@link RemoteActions#ACTION_NEXT}, through
     * {@link BasePlaylistManager#invokeNext()}
     */
    protected void performNext() {
        seekToPosition = 0;
        immediatelyPause = !isPlaying();

        getPlaylistManager().next();
        startItemPlayback();
    }

    /**
     * Performs the functionality to repeat the current
     * media item in playback.  This is called through an
     * intent with the {@link RemoteActions#ACTION_REPEAT},
     * through {@link BasePlaylistManager#invokeRepeat()}
     */
    protected void performRepeat() {
        //Left for the extending class to implement
    }

    /**
     * Performs the functionality to repeat the current
     * media item in playback.  This is called through an
     * intent with the {@link RemoteActions#ACTION_SHUFFLE},
     * through {@link BasePlaylistManager#invokeShuffle()}
     */
    protected void performShuffle() {
        //Left for the extending class to implement
    }

    /**
     * Performs the functionality for when a media item
     * has finished playback.
     */
    protected void performOnMediaCompletion() {
        //Left for the extending class to implement
    }

    /**
     * Called when the playback of the specified media item has
     * encountered an error.
     */
    protected void performOnMediaError() {
        setPlaybackState(PlaybackState.ERROR);

        stopForeground();
        updateWiFiLock(false);
        mediaProgressPoll.stop();

        abandonAudioFocus();
    }

    /**
     * Performs the functionality to start a seek for the current
     * media item.  This is called through an intent
     * with the {@link RemoteActions#ACTION_SEEK_STARTED}, through
     * {@link BasePlaylistManager#invokeSeekStarted()}
     */
    protected void performSeekStarted() {
        boolean isPlaying = false;

        if (currentItemIsType(BasePlaylistManager.AUDIO)) {
            isPlaying = audioPlayer != null && audioPlayer.isPlaying();
        } else if (currentItemIsType(BasePlaylistManager.VIDEO)) {
            VideoPlayerApi videoPlayer = getPlaylistManager().getVideoPlayer();
            isPlaying = videoPlayer != null && videoPlayer.isPlaying();
        }

        if (isPlaying) {
            pausedForSeek = true;
            performPause();
        }
    }

    /**
     * Performs the functionality to end a seek for the current
     * media item.  This is called through an intent
     * with the {@link RemoteActions#ACTION_SEEK_ENDED}, through
     * {@link BasePlaylistManager#invokeSeekEnded(long)}
     */
    protected void performSeekEnded(long newPosition) {
        performSeek(newPosition);
    }

    /**
     * A helper method that allows us to update the audio volume of the media
     * in playback when AudioFocus is lost or gained and we can dim instead
     * of pausing playback.
     *
     * @param left  The left channels audio volume
     * @param right The right channels audio volume
     */
    protected void setVolume(float left, float right) {
        if (currentItemIsType(BasePlaylistManager.AUDIO) && audioPlayer != null) {
            audioPlayer.setVolume(left, right);
        } else if (currentItemIsType(BasePlaylistManager.VIDEO)) {
            VideoPlayerApi videoPlayerApi = getPlaylistManager().getVideoPlayer();
            if (videoPlayerApi != null) {
                videoPlayerApi.setVolume(1.0f, 1.0f);
            }
        }
    }

    /**
     * Sets the media type to be allowed in the playlist.  If the type is changed
     * during media playback, the current item will be compared against the new
     * allowed type.  If the current item type and the new type are not compatible
     * then the playback will be seeked to the next valid item.
     *
     * @param newType The new allowed media type
     */
    protected void updateAllowedMediaType(@SupportedMediaType int newType) {
        //We seek through the items until an allowed one is reached, or none is reached and the service is stopped.
        if (currentPlaylistItem != null && (newType & currentPlaylistItem.getMediaType()) == 0) {
            performNext();
        }
    }

    /**
     * Informs the callbacks specified with {@link BasePlaylistManager#registerPlaylistListener(PlaylistListener)}
     * that the current playlist item has changed.
     */
    protected void postPlaylistItemChanged() {
        Log.d(TAG, "postPlaylistItemChanged");
        boolean hasNext = getPlaylistManager().isNextAvailable();
        boolean hasPrevious = getPlaylistManager().isPreviousAvailable();
        getPlaylistManager().onPlaylistItemChanged(currentPlaylistItem, hasNext, hasPrevious);

        if (isCastSessionConnected())
            loadRemoteMedia(0, true);
    }

    /**
     * Informs the callbacks specified with {@link BasePlaylistManager#registerPlaylistListener(PlaylistListener)}
     * that the current media state has changed.
     */
    protected void postPlaybackStateChanged() {
        getPlaylistManager().onPlaybackStateChanged(currentState);
    }

    /**
     * Specifies if the VideoView audio focus should be handled by the
     * PlaylistService.  This is <code>false</code> by default because
     * the Android VideoView handles it's own audio focus, which would
     * cause the video to never play on it's own if this were enabled.
     *
     * @return True if the PlaylistService should handle audio focus for videos
     */
    protected boolean handleVideoAudioFocus() {
        return false;
    }

    /**
     * Performs the functionality to stop the media playback.  This will perform any cleanup
     * and stop the service.
     */
    protected void performStop() {
        setPlaybackState(PlaybackState.STOPPED);
        if (currentPlaylistItem != null) {
            onMediaStopped(currentPlaylistItem);
        }

        // let go of all resources
        relaxResources(true);

        getPlaylistManager().reset();
        stopSelf();
    }

    /**
     * Performs the functionality to seek the current media item
     * to the specified position.  This should only be called directly
     * when performing the initial setup of playback position.  For
     * normal seeking process use the {@link #performSeekStarted()} in
     * conjunction with {@link #performSeekEnded(long)}
     *
     * @param position The position to seek to in milliseconds
     */
    protected void performSeek(long position) {
        performSeek(position, true);
    }

    /**
     * Performs the functionality to seek the current media item
     * to the specified position.  This should only be called directly
     * when performing the initial setup of playback position.  For
     * normal seeking process use the {@link #performSeekStarted()} in
     * conjunction with {@link #performSeekEnded(long)}
     *
     * @param position            The position to seek to in milliseconds
     * @param updatePlaybackState True if the playback state should be updated
     */
    protected void performSeek(long position, boolean updatePlaybackState) {
        boolean isPlaying = false;

        if (currentItemIsType(BasePlaylistManager.AUDIO)) {

            if (isCastSessionConnected()) {
                if (mRemoteMediaClient == null)
                    mRemoteMediaClient = getRemoteMediaClient();

                if (mRemoteMediaClient != null) {
                    isPlaying = mRemoteMediaClient.isPlaying();
//                    mRemoteMediaClient.seek(position);
                }
            } else {
                if (audioPlayer != null) {
                    isPlaying = audioPlayer.isPlaying();
                    audioPlayer.seekTo(position);
                }
            }

        } else if (currentItemIsType(BasePlaylistManager.VIDEO)) {
            VideoPlayerApi videoPlayer = getPlaylistManager().getVideoPlayer();
            if (videoPlayer != null) {
                isPlaying = videoPlayer.isPlaying();
                videoPlayer.seekTo(position);
            }
        }

        playingBeforeSeek = isPlaying;
        if (updatePlaybackState) {
            setPlaybackState(PlaybackState.SEEKING);
        }
    }

    /**
     * Performs the functionality to actually pause the current media
     * playback.
     */
    protected void performPause() {
        Log.d(TAG, "performPause isCastSessionConnected():" + isCastSessionConnected());
        if (currentItemIsType(BasePlaylistManager.AUDIO)) {

            if (isCastSessionConnected()) {
                if (mRemoteMediaClient == null)
                    mRemoteMediaClient = getRemoteMediaClient();

                if (mRemoteMediaClient != null && mRemoteMediaClient.isPlaying())
                    mRemoteMediaClient.pause();

            } else {
                if (audioPlayer != null)
                    audioPlayer.pause();
            }

        } else if (currentItemIsType(BasePlaylistManager.VIDEO)) {
            VideoPlayerApi videoPlayer = getPlaylistManager().getVideoPlayer();
            if (videoPlayer != null) {
                videoPlayer.pause();
            }
        }

        mediaProgressPoll.stop();
        setPlaybackState(PlaybackState.PAUSED);
        stopForeground();

        abandonAudioFocus();
        updateNotification();
        updateRemoteViews();
    }

    /**
     * Performs the functionality to actually play the current media
     * item.
     */
    protected void performPlay() {
        Log.d(TAG, "performPlay isCastSessionConnected():" + isCastSessionConnected() + ", mRemoteMediaClient:" + (mRemoteMediaClient == null));

        if (currentItemIsType(BasePlaylistManager.AUDIO)) {

            if (isCastSessionConnected()) {
                if (mRemoteMediaClient == null)
                    mRemoteMediaClient = getRemoteMediaClient();

                if (mRemoteMediaClient != null) {
                    if (audioPlayer.isPlaying())
                        audioPlayer.pause();

                    if (mRemoteMediaClient.isPaused())
                        mRemoteMediaClient.play();
                }
            } else {
                if (audioPlayer != null)
                    audioPlayer.play();
            }

        } else if (currentItemIsType(BasePlaylistManager.VIDEO)) {
            VideoPlayerApi videoPlayer = getPlaylistManager().getVideoPlayer();
            if (videoPlayer != null) {
                videoPlayer.play();
            }
        }

        mediaProgressPoll.start();
        setPlaybackState(PlaybackState.PLAYING);
        setupForeground();

        requestAudioFocus();
        updateNotification();
        updateRemoteViews();
    }

    /**
     * Determines if the current media item is of the passed type.  This is specified
     * with {@link IPlaylistItem#getMediaType()}
     *
     * @return True if the current media item is of the same passed type
     */
    protected boolean currentItemIsType(@SupportedMediaType int type) {
        return currentPlaylistItem != null && (currentPlaylistItem.getMediaType() & type) != 0;
    }

    /**
     * Starts the actual media playback
     * <p>
     * <em><b>NOTE:</b></em> In order to play videos you will need to specify the
     * VideoPlayerApi with {@link BasePlaylistManager#setVideoPlayer(VideoPlayerApi)}
     */
    protected void startItemPlayback() {
        onMediaPlaybackEnded();
        seekToNextPlayableItem();
        mediaItemChanged();

        //Performs the playback for the correct media type
        boolean playbackHandled = false;
        mediaListener.resetRetryCount();

        if (currentItemIsType(BasePlaylistManager.AUDIO)) {
            playbackHandled = playAudioItem();
        } else if (currentItemIsType(BasePlaylistManager.VIDEO)) {
            playbackHandled = playVideoItem();
        } else if (currentPlaylistItem != null && currentPlaylistItem.getMediaType() != 0) {
            playbackHandled = playOtherItem();
        }

        if (playbackHandled) {
            return;
        }

        //If the playback wasn't handled, attempt to seek to the next playable item, otherwise stop the service
        if (getPlaylistManager().isNextAvailable()) {
            performNext();
        } else {
            performStop();
        }
    }

    /**
     * Starts the actual playback of the specified audio item.
     *
     * @return True if the item playback was correctly handled
     */
    protected boolean playAudioItem() {
        stopVideoPlayback();
        initializeAudioPlayer();
        requestAudioFocus();

        mediaProgressPoll.update(audioPlayer);
        mediaProgressPoll.reset();

        boolean isItemDownloaded = isDownloaded(currentPlaylistItem);
        //noinspection ConstantConditions - currentPlaylistItem and the audioPlayer are not null at this point
        audioPlayer.setDataSource(this, Uri.parse(isItemDownloaded ? currentPlaylistItem.getDownloadedMediaUri() : currentPlaylistItem.getMediaUrl()));

        setPlaybackState(PlaybackState.PREPARING);
        setupAsForeground();

        audioPlayer.prepareAsync();

        // If we are streaming from the internet, we want to hold a Wifi lock, which prevents
        // the Wifi radio from going to sleep while the song is playing. If, on the other hand,
        // we are NOT streaming, we want to release the lock.
        updateWiFiLock(!isItemDownloaded);
        return true;
    }

    /**
     * Starts the actual playback of the specified video item.
     *
     * @return True if the item playback was correctly handled
     */
    protected boolean playVideoItem() {
        stopAudioPlayback();
        initializeVideoPlayer();
        requestAudioFocus();

        VideoPlayerApi videoPlayer = getPlaylistManager().getVideoPlayer();
        if (videoPlayer == null) {
            return false;
        }

        mediaProgressPoll.update(videoPlayer);
        mediaProgressPoll.reset();

        boolean isItemDownloaded = isDownloaded(currentPlaylistItem);
        //noinspection ConstantConditions -  currentPlaylistItem is not null at this point (see calling method for null check)
        videoPlayer.setDataSource(Uri.parse(isItemDownloaded ? currentPlaylistItem.getDownloadedMediaUri() : currentPlaylistItem.getMediaUrl()));

        setPlaybackState(PlaybackState.PREPARING);
        setupAsForeground();

        // If we are streaming from the internet, we want to hold a Wifi lock, which prevents
        // the Wifi radio from going to sleep while the song is playing. If, on the other hand,
        // we are NOT streaming, we want to release the lock.
        updateWiFiLock(!isItemDownloaded);
        return true;
    }

    /**
     * Starts the playback of the specified other item type.
     *
     * @return True if the item playback was correctly handled
     */
    protected boolean playOtherItem() {
        return false;
    }

    /**
     * Stops the AudioPlayer from playing.
     */
    protected void stopAudioPlayback() {
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer.reset();
        }
    }

    /**
     * Stops the VideoView from playing if we have access to it.
     */
    protected void stopVideoPlayback() {
        VideoPlayerApi videoPlayer = getPlaylistManager().getVideoPlayer();
        if (videoPlayer != null) {
            videoPlayer.stop();
            videoPlayer.reset();
        }
    }

    /**
     * Starts the appropriate media playback based on the current item type.
     * If the current item is audio, then the playback will make sure to pay
     * attention to the current audio focus.
     */
    protected void startMediaPlayer() {
        if (currentItemIsType(BasePlaylistManager.AUDIO)) {
            startAudioPlayer();
        } else if (currentItemIsType(BasePlaylistManager.VIDEO)) {
            startVideoPlayer();
        }
    }

    /**
     * Reconfigures audioPlayer according to audio focus settings and starts/restarts it. This
     * method starts/restarts the audioPlayer respecting the current audio focus state. So if
     * we have focus, it will play normally; if we don't have focus, it will either leave the
     * audioPlayer paused or set it to a low volume, depending on what is allowed by the
     * current focus settings.
     */
    protected void startAudioPlayer() {
        if (audioPlayer != null) {
            startMediaPlayer(audioPlayer);
        }
    }

    /**
     * Reconfigures the videoPlayer according to audio focus settings and starts/restarts it. This
     * method starts/restarts the videoPlayer respecting the current audio focus state. So if
     * we have focus, it will play normally; if we don't have focus, it will either leave the
     * videoPlayer paused or set it to a low volume, depending on what is allowed by the
     * current focus settings.
     */
    protected void startVideoPlayer() {
        VideoPlayerApi videoPlayer = getPlaylistManager().getVideoPlayer();
        if (videoPlayer != null) {
            startMediaPlayer(videoPlayer);
        }
    }

    /**
     * Reconfigures the mediaPlayerApi according to audio focus settings and starts/restarts it. This
     * method starts/restarts the mediaPlayerApi respecting the current audio focus state. So if
     * we have focus, it will play normally; if we don't have focus, it will either leave the
     * mediaPlayerApi paused or set it to a low volume, depending on what is allowed by the
     * current focus settings.
     */
    protected void startMediaPlayer(@NonNull MediaPlayerApi mediaPlayerApi) {
        if (handleVideoAudioFocus() || !(mediaPlayerApi instanceof VideoPlayerApi)) {
            if (audioFocusHelper == null || audioFocusHelper.getCurrentAudioFocus() == AudioFocusHelper.Focus.NO_FOCUS_NO_DUCK) {
                // If we don't have audio focus and can't duck we have to pause, even if state is playing
                // Be we stay in the playing state so we know we have to resume playback once we get the focus back.
                if (isPlaying()) {
                    pausedForFocusLoss = true;
                    performPause();
                    onMediaPlaybackEnded(currentPlaylistItem, mediaPlayerApi.getCurrentPosition(), mediaPlayerApi.getDuration());
                }

                return;
            } else if (audioFocusHelper.getCurrentAudioFocus() == AudioFocusHelper.Focus.NO_FOCUS_CAN_DUCK) {
                setVolume(getAudioDuckVolume(), getAudioDuckVolume());
            } else {
                setVolume(1.0f, 1.0f);
            }
        }

        //Seek to the correct position
        if (seekToPosition > 0) {
            performSeek(seekToPosition, false);
            seekToPosition = -1;
        }

        //Start the playback only if requested, otherwise upate the state to paused
        mediaProgressPoll.start();
        if (!isPlaying() && !immediatelyPause) {
            performPlay();
            onMediaPlaybackStarted(currentPlaylistItem, mediaPlayerApi.getCurrentPosition(), mediaPlayerApi.getDuration());
        } else {
            setPlaybackState(PlaybackState.PAUSED);
        }
    }

    /**
     * Requests the audio focus
     */
    protected boolean requestAudioFocus() {
        //noinspection SimplifiableIfStatement
        if (!handleVideoAudioFocus() && currentItemIsType(BasePlaylistManager.VIDEO)) {
            return false;
        }

        return audioFocusHelper != null && audioFocusHelper.requestFocus();
    }

    /**
     * Requests the audio focus to be abandoned
     */
    protected boolean abandonAudioFocus() {
        //noinspection SimplifiableIfStatement
        if (!handleVideoAudioFocus() && currentItemIsType(BasePlaylistManager.VIDEO)) {
            return false;
        }

        return audioFocusHelper != null && audioFocusHelper.abandonFocus();
    }

    /**
     * Releases resources used by the service for playback. This includes the "foreground service"
     * status and notification, the wake locks, and the audioPlayer if requested
     *
     * @param releaseAudioPlayer True if the audioPlayer should be released
     */
    protected void relaxResources(boolean releaseAudioPlayer) {
        mediaProgressPoll.release();

        if (releaseAudioPlayer) {
            if (audioPlayer != null) {
                audioPlayer.reset();
                audioPlayer.release();
                audioPlayer = null;
            }

            getPlaylistManager().setCurrentPosition(Integer.MAX_VALUE);
        }

        abandonAudioFocus();
        updateWiFiLock(false);
    }

    /**
     * Acquires or releases the WiFi lock
     *
     * @param acquire True if the WiFi lock should be acquired, false to release
     */
    protected void updateWiFiLock(boolean acquire) {
        if (wifiLock == null) {
            return;
        }

        if (acquire && !wifiLock.isHeld()) {
            wifiLock.acquire();
        } else if (!acquire && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    /**
     * Updates the current PlaybackState and informs any listening classes.
     *
     * @param state The new PlaybackState
     */
    protected void setPlaybackState(PlaybackState state) {
        currentState = state;
        postPlaybackStateChanged();
    }

    /**
     * Iterates through the playList, starting with the current item, until we reach an item we can play.
     * Normally this will be the current item, however if they don't have network then
     * it will be the next downloaded item.
     */
    protected void seekToNextPlayableItem() {
        I currentItem = getPlaylistManager().getCurrentItem();
        if (currentItem == null) {
            currentPlaylistItem = null;
            return;
        }

        //Only iterate through the list if we aren't connected to the internet
        if (!isNetworkAvailable()) {
            while (currentItem != null && !isDownloaded(currentItem)) {
                currentItem = getPlaylistManager().next();
            }
        }

        //If we are unable to get a next playable item, post a network error
        if (currentItem == null) {
            onNoNonNetworkItemsAvailable();
        }

        currentPlaylistItem = getPlaylistManager().getCurrentItem();
    }

    /**
     * Called when the current media item has changed, this will update the notification and
     * media control values.
     */
    protected void mediaItemChanged() {
        //Validates that the currentPlaylistItem is for the currentItem
        if (!getPlaylistManager().isPlayingItem(currentPlaylistItem)) {
            Log.d(TAG, "forcing currentPlaylistItem update");
            currentPlaylistItem = getPlaylistManager().getCurrentItem();
        }

        postPlaylistItemChanged();
    }

    /**
     * Handles the remote actions from the big notification and media controls
     * to control the media playback
     *
     * @param action The action from the intent to handle
     * @param extras The extras packaged with the intent associated with the action
     * @return True if the remote action was handled
     */
    protected boolean handleRemoteAction(String action, Bundle extras) {
        if (action == null || action.isEmpty()) {
            return false;
        }

        switch (action) {
            case RemoteActions.ACTION_PLAY_PAUSE:
                performPlayPause();
                break;

            case RemoteActions.ACTION_NEXT:
                performNext();
                break;

            case RemoteActions.ACTION_PREVIOUS:
                performPrevious();
                break;

            case RemoteActions.ACTION_REPEAT:
                performRepeat();
                break;

            case RemoteActions.ACTION_SHUFFLE:
                performShuffle();
                break;

            case RemoteActions.ACTION_STOP:
                performStop();
                break;

            case RemoteActions.ACTION_SEEK_STARTED:
                performSeekStarted();
                break;

            case RemoteActions.ACTION_SEEK_ENDED:
                performSeekEnded(extras.getLong(RemoteActions.ACTION_EXTRA_SEEK_POSITION, 0));
                break;

            case RemoteActions.ACTION_ALLOWED_TYPE_CHANGED:
                //noinspection WrongConstant
                updateAllowedMediaType(extras.getInt(RemoteActions.ACTION_EXTRA_ALLOWED_TYPE));
                break;

            default:
                return false;
        }

        return true;
    }

    /**
     * Initializes the audio player.
     * If the audio player has already been initialized, then it will
     * be reset to prepare for the next playback item.
     */
    protected void initializeAudioPlayer() {
        if (audioPlayer != null) {
            audioPlayer.reset();
            return;
        }

        audioPlayer = getNewAudioPlayer();
        audioPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        audioPlayer.setStreamType(AudioManager.STREAM_MUSIC);

        //Sets the listeners
        audioPlayer.setOnMediaPreparedListener(mediaListener);
        audioPlayer.setOnMediaCompletionListener(mediaListener);
        audioPlayer.setOnMediaErrorListener(mediaListener);
        audioPlayer.setOnMediaSeekCompletionListener(mediaListener);
        audioPlayer.setOnMediaBufferUpdateListener(mediaListener);
    }

    /**
     * Initializes the video players connection to this Service, including
     * adding media playback listeners.
     */
    protected void initializeVideoPlayer() {
        VideoPlayerApi videoPlayer = getPlaylistManager().getVideoPlayer();
        if (videoPlayer == null) {
            return;
        }

        //Sets the listeners
        videoPlayer.setOnMediaPreparedListener(mediaListener);
        videoPlayer.setOnMediaCompletionListener(mediaListener);
        videoPlayer.setOnMediaErrorListener(mediaListener);
        videoPlayer.setOnMediaSeekCompletionListener(mediaListener);
        videoPlayer.setOnMediaBufferUpdateListener(mediaListener);
    }

    /**
     * A class to listen to the {@link MediaPlayerApi} events, and will
     * retry playback once if the media is audio when an error is encountered.
     * This is done to workaround an issue on older (pre 4.1)
     * devices where playback will fail due to a race condition
     * in the {@link MediaPlayer}
     */
    protected class MediaListener implements OnMediaPreparedListener, OnMediaCompletionListener, OnMediaErrorListener, OnMediaSeekCompletionListener, OnMediaBufferUpdateListener {
        private static final int MAX_RETRY_COUNT = 1;
        private int retryCount = 0;

        @Override
        public void onPrepared(@NonNull MediaPlayerApi mediaPlayerApi) {
            retryCount = 0;
            startMediaPlayer();
        }

        @Override
        public void onCompletion(@NonNull MediaPlayerApi mediaPlayerApi) {
            performOnMediaCompletion();
        }

        @Override
        public boolean onError(@NonNull MediaPlayerApi mediaPlayerApi) {
            if (!retryAudio()) {
                performOnMediaError();
            }

            return false;
        }

        @Override
        public void onSeekComplete(@NonNull MediaPlayerApi mediaPlayerApi) {
            if (pausedForSeek || playingBeforeSeek) {
                performPlay();
                pausedForSeek = false;
                playingBeforeSeek = false;
            } else {
                performPause();
            }
        }

        @Override
        public void onBufferingUpdate(@NonNull MediaPlayerApi mediaPlayerApi, @IntRange(from = 0, to = MediaProgress.MAX_BUFFER_PERCENT) int percent) {
            //Makes sure to update listeners of buffer updates even when playback is paused
            if (!mediaPlayerApi.isPlaying() && currentMediaProgress.getBufferPercent() != percent) {
                currentMediaProgress.update(mediaPlayerApi.getCurrentPosition(), percent, mediaPlayerApi.getDuration());
                onProgressUpdated(currentMediaProgress);
            }
        }

        public void resetRetryCount() {
            retryCount = 0;
        }

        /**
         * The retry count is a workaround for when the EMAudioPlayer will occasionally fail
         * to load valid content due to the MediaPlayer on pre 4.1 devices
         *
         * @return True if a retry was started
         */
        public boolean retryAudio() {
            if (currentItemIsType(BasePlaylistManager.AUDIO) && ++retryCount <= MAX_RETRY_COUNT) {
                Log.d(TAG, "Retrying audio playback.  Retry count: " + retryCount);
                playAudioItem();
                return true;
            }

            return false;
        }
    }

    private RemoteMediaClient getRemoteMediaClient() {
        try {
            if (mCastContext == null)
                return null;

            CastSession castSession = mCastContext.getSessionManager().getCurrentCastSession();
            return (castSession != null && castSession.isConnected())
                    ? castSession.getRemoteMediaClient() : null;
        } catch (NullPointerException e) {
            return null;
        }
    }

    private boolean isCastSessionConnected() {
        try {
            if (mCastContext == null)
                return false;

            CastSession castSession = mCastContext.getSessionManager().getCurrentCastSession();
            return castSession != null && castSession.isConnected();
        } catch (NullPointerException e) {
            return false;
        }
    }

    private MediaInfo buildMediaInfo(I mediaItem) {
        if (mediaItem == null) return null;
        Log.d(TAG, "buildMediaInfo");
        MediaMetadata mediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);

        mediaMetadata.putString(MediaMetadata.KEY_SUBTITLE, mediaItem.getAlbum());
        mediaMetadata.putString(MediaMetadata.KEY_TITLE, mediaItem.getTitle());
        mediaMetadata.addImage(new WebImage(Uri.parse(mediaItem.getThumbnailUrl())));

        return new MediaInfo.Builder(mediaItem.getMediaUrl())
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("audio/mepg")
                .setMetadata(mediaMetadata)
                .build();
    }

    private void setupCastListener() {
        mSessionManagerListener = new SessionManagerListener<CastSession>() {
            @Override
            public void onSessionStarting(CastSession castSession) {
                Log.d(TAG, "castSession");
            }

            @Override
            public void onSessionStarted(CastSession castSession, String s) {
                Log.d(TAG, "onSessionStarted");
                onApplicationConnected(castSession);
            }

            @Override
            public void onSessionStartFailed(CastSession castSession, int i) {
                Log.d(TAG, "onSessionStartFailed");
                onApplicationDisconnected();
            }

            @Override
            public void onSessionEnding(CastSession castSession) {
                Log.d(TAG, "onSessionEnding");
                onApplicationDisconnected();
            }

            @Override
            public void onSessionEnded(CastSession castSession, int i) {
                Log.d(TAG, "onSessionEnded");
                onApplicationDisconnected();
            }

            @Override
            public void onSessionResuming(CastSession castSession, String s) {
                Log.d(TAG, "onSessionResuming");
            }

            @Override
            public void onSessionResumed(CastSession castSession, boolean b) {
                Log.d(TAG, "onSessionResumed");
                onApplicationConnected(castSession);
            }

            @Override
            public void onSessionResumeFailed(CastSession castSession, int i) {
                Log.d(TAG, "onSessionResumeFailed");
                onApplicationDisconnected();
            }

            @Override
            public void onSessionSuspended(CastSession castSession, int i) {
                Log.d(TAG, "onSessionSuspended");

            }

            private void onApplicationConnected(CastSession castSession) {
                mRemoteMediaClient = getRemoteMediaClient();
                if (mRemoteMediaClient != null) {
                    mRemoteMediaClient.addListener(mRemoteMediaClientListener);
                }

                if (null != currentPlaylistItem) {
                    if (audioPlayer.isPlaying())
                        audioPlayer.pause();

                    loadRemoteMedia(0, true);
                    return;
                }
            }

            private void onApplicationDisconnected() {
                if (mRemoteMediaClient != null) {
                    mRemoteMediaClient.removeListener(mRemoteMediaClientListener);

                    if (mRemoteMediaClient.isPlaying())
                        audioPlayer.play();
                }
                mRemoteMediaClient = null;

            }
        };

        mRemoteMediaClientListener = new RemoteMediaClient.Listener() {
            @Override
            public void onStatusUpdated() {
                Log.d(TAG, "onStatusUpdated");
                updatePlaybackState();
            }

            @Override
            public void onMetadataUpdated() {
                Log.d(TAG, "onMetadataUpdated");
            }

            @Override
            public void onQueueStatusUpdated() {
                Log.d(TAG, "onQueueStatusUpdated");

            }

            @Override
            public void onPreloadStatusUpdated() {
                Log.d(TAG, "onPreloadStatusUpdated");

            }

            @Override
            public void onSendingRemoteMediaRequest() {
                Log.d(TAG, "onSendingRemoteMediaRequest");
                updatePlaybackState();
            }

            @Override
            public void onAdBreakStatusUpdated() {
                Log.d(TAG, "onAdBreakStatusUpdated");

            }

            private void updatePlaybackState() {
                if (mRemoteMediaClient == null)
                    mRemoteMediaClient = getRemoteMediaClient();

                if (mRemoteMediaClient != null) {
                    if (mRemoteMediaClient.isPlaying()) {
                        setPlaybackState(PlaybackState.PLAYING);
                    } else if (mRemoteMediaClient.isPaused()) {
                        setPlaybackState(PlaybackState.PAUSED);
                    }

                    updateNotification();
                }
            }
        };
    }

    private void loadRemoteMedia(int position, boolean autoPlay) {
        Log.d(TAG, "loadRemoteMedia ");
        if (mRemoteMediaClient == null)
            mRemoteMediaClient = getRemoteMediaClient();

        Log.d(TAG, "loadRemoteMedia remoteMediaClient == null :" + (mRemoteMediaClient == null));
        if (mRemoteMediaClient != null) {
            Log.d(TAG, "remoteMediaClient.load");
            mRemoteMediaClient.addListener(mRemoteMediaClientListener);
            mRemoteMediaClient.load(buildMediaInfo(currentPlaylistItem), autoPlay, position);
        }
    }

    public boolean isGooglePlayServicesAvailable() {
        boolean isGooglePlayServicesAvailable = true;
        try {
            int status = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
            if (status != ConnectionResult.SUCCESS) {
                isGooglePlayServicesAvailable = false;
            }
            Log.e(TAG, "GooglePlayServicesUtil.isGooglePlayServicesAvailable:" + status);
        } catch (Exception e) {
            Log.e(TAG, "GooglePlayServicesUtil.isGooglePlayServicesAvailable");
        }

        return isGooglePlayServicesAvailable;
    }
}