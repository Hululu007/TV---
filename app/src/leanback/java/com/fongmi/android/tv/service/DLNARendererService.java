package com.fongmi.android.tv.service;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.media3.common.C;
import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.dlna.CastAction;
import com.fongmi.android.tv.dlna.DLNAAvTransportImpl;
import com.fongmi.android.tv.dlna.DLNARenderingControlImpl;
import com.fongmi.android.tv.dlna.DLNAServiceConfiguration;
import com.fongmi.android.tv.dlna.RenderState;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Util;

import org.jupnp.UpnpServiceConfiguration;
import org.jupnp.android.AndroidUpnpServiceImpl;
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder;
import org.jupnp.model.DefaultServiceManager;
import org.jupnp.model.meta.DeviceDetails;
import org.jupnp.model.meta.DeviceIdentity;
import org.jupnp.model.meta.LocalDevice;
import org.jupnp.model.meta.LocalService;
import org.jupnp.model.meta.ManufacturerDetails;
import org.jupnp.model.meta.ModelDetails;
import org.jupnp.model.types.DLNADoc;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.model.types.UDN;
import org.jupnp.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.jupnp.support.connectionmanager.ConnectionManagerService;
import org.jupnp.support.lastchange.LastChangeAwareServiceManager;
import org.jupnp.support.model.ProtocolInfos;
import org.jupnp.support.renderingcontrol.lastchange.RenderingControlLastChangeParser;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DLNARendererService extends AndroidUpnpServiceImpl implements ServiceConnection {

    public static final String EXTRA_CAST_TOKEN = "dlna_cast_token";

    private static final AtomicInteger CAST_TOKEN = new AtomicInteger();
    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean();
    private static volatile DLNARendererService instance;

    private final IBinder binder = new LocalBinder();

    private volatile PlayerManager player;
    private volatile boolean isDlnaActive;

    private DLNARenderingControlImpl renderingControlImpl;
    private DLNAAvTransportImpl avTransportImpl;
    private PlaybackService playbackService;
    private Player currentListenerPlayer;
    private boolean bound;

    public static void start(Context context) {
        context.startService(new Intent(context, DLNARendererService.class));
    }

    public static void stop(Context context) {
        if (isCasting()) {
            STOP_REQUESTED.set(true);
            return;
        }
        STOP_REQUESTED.set(false);
        context.stopService(new Intent(context, DLNARendererService.class));
    }

    public static boolean isCasting() {
        return CAST_TOKEN.get() != 0;
    }

    public static int markCasting() {
        STOP_REQUESTED.set(false);
        return CAST_TOKEN.incrementAndGet();
    }

    public static void clearCasting(int token) {
        if (!CAST_TOKEN.compareAndSet(token, 0)) return;
        DLNARendererService service = instance;
        if (service != null && STOP_REQUESTED.compareAndSet(true, false) && !isCasting()) service.stopSelf();
    }

    @Override
    protected UpnpServiceConfiguration createConfiguration() {
        return new DLNAServiceConfiguration();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Notification notification = new NotificationCompat.Builder(this, Notify.DEFAULT).setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.app_name)).setSilent(true).build();
        startForeground(Notify.ID + 1, notification);
        upnpService.startup();
        registerLocalDevice();
    }

    private void registerLocalDevice() {
        LocalService<DLNAAvTransportImpl> avTransport = createAvTransport();
        LocalService<ConnectionManagerService> connManager = createConnectionManager();
        LocalService<DLNARenderingControlImpl> renderControl = createRenderingControl();
        DeviceIdentity identity = new DeviceIdentity(new UDN(UUID.nameUUIDFromBytes((Build.MANUFACTURER + Build.MODEL + "-MediaRenderer").getBytes(StandardCharsets.UTF_8))));
        UDADeviceType type = new UDADeviceType("MediaRenderer", 1);
        DeviceDetails details = new DeviceDetails(Util.getDeviceName(), new ManufacturerDetails(Build.MANUFACTURER), new ModelDetails(Build.MODEL, "DLNA Renderer", "1.0"), new DLNADoc[]{new DLNADoc("DMR", DLNADoc.Version.V1_5)}, null);
        try {
            LocalDevice device = new LocalDevice(identity, type, details, new LocalService[]{avTransport, connManager, renderControl});
            upnpService.getRegistry().addDevice(device);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private LocalService<DLNAAvTransportImpl> createAvTransport() {
        avTransportImpl = new DLNAAvTransportImpl(this);
        LocalService<DLNAAvTransportImpl> service = new AnnotationLocalServiceBinder().read(DLNAAvTransportImpl.class);
        service.setManager(new LastChangeAwareServiceManager<>(service, new AVTransportLastChangeParser()) {
            @Override
            protected DLNAAvTransportImpl createServiceInstance() {
                return avTransportImpl;
            }
        });
        return service;
    }

    private static final String SINK_PROTOCOL_INFO = String.join(",",
            "http-get:*:video/mp4:*",
            "http-get:*:video/mpeg:*",
            "http-get:*:video/x-matroska:*",
            "http-get:*:video/webm:*",
            "http-get:*:video/3gpp:*",
            "http-get:*:video/x-msvideo:*",
            "http-get:*:video/*:*",
            "http-get:*:audio/mpeg:*",
            "http-get:*:audio/mp4:*",
            "http-get:*:audio/aac:*",
            "http-get:*:audio/flac:*",
            "http-get:*:audio/ogg:*",
            "http-get:*:audio/*:*",
            "http-get:*:image/jpeg:*",
            "http-get:*:image/png:*",
            "http-get:*:image/*:*",
            "http-get:*:application/vnd.apple.mpegurl:*",
            "http-get:*:application/x-mpegURL:*",
            "http-get:*:application/dash+xml:*",
            "http-get:*:*:*");

    @SuppressWarnings("unchecked")
    private LocalService<ConnectionManagerService> createConnectionManager() {
        LocalService<ConnectionManagerService> service = new AnnotationLocalServiceBinder().read(ConnectionManagerService.class);
        service.setManager(new DefaultServiceManager<>(service, ConnectionManagerService.class) {
            @Override
            protected ConnectionManagerService createServiceInstance() {
                return new ConnectionManagerService(new ProtocolInfos(), new ProtocolInfos(SINK_PROTOCOL_INFO));
            }
        });
        return service;
    }

    @SuppressWarnings("unchecked")
    private LocalService<DLNARenderingControlImpl> createRenderingControl() {
        renderingControlImpl = new DLNARenderingControlImpl(this);
        LocalService<DLNARenderingControlImpl> service = new AnnotationLocalServiceBinder().read(DLNARenderingControlImpl.class);
        service.setManager(new LastChangeAwareServiceManager<>(service, new RenderingControlLastChangeParser()) {
            @Override
            protected DLNARenderingControlImpl createServiceInstance() {
                return renderingControlImpl;
            }
        });
        return service;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        unbindPlaybackService();
        super.onDestroy();
    }

    private void bindPlaybackService() {
        if (bound) return;
        bound = bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
    }

    private void cleanupPlaybackRefs() {
        App.removeCallbacks(positionUpdater);
        if (currentListenerPlayer != null) {
            currentListenerPlayer.removeListener(listener);
            currentListenerPlayer = null;
        }
        if (playbackService != null) {
            playbackService.removePlayerCallback(playerCallback);
            playbackService = null;
        }
        player = null;
        if (avTransportImpl != null) avTransportImpl.setPlayerManager(null);
    }

    private void unbindPlaybackService() {
        if (!bound) return;
        bound = false;
        cleanupPlaybackRefs();
        unbindService(this);
    }

    public void setDlnaActive(boolean active) {
        isDlnaActive = active;
        if (avTransportImpl != null) avTransportImpl.setDlnaActive(active);
        if (active) bindPlaybackService();
        else {
            if (avTransportImpl != null) avTransportImpl.reset();
            unbindPlaybackService();
        }
    }

    public long consumePendingSeekMs() {
        return avTransportImpl != null ? avTransportImpl.consumePendingSeekMs() : -1;
    }

    public CastAction consumeNext() {
        return avTransportImpl != null ? avTransportImpl.popNext() : null;
    }

    public void notifyError() {
        if (avTransportImpl != null) avTransportImpl.fireStateChange(RenderState.STOPPED);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!bound || !isDlnaActive) {
            unbindPlaybackService();
            return;
        }
        playbackService = ((PlaybackService.LocalBinder) binder).getService();
        playbackService.addPlayerCallback(playerCallback);
        player = playbackService.player();
        avTransportImpl.setPlayerManager(player);
        currentListenerPlayer = player.getPlayer();
        currentListenerPlayer.addListener(listener);
        App.post(positionUpdater, 1000);
        notifyState();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        cleanupPlaybackRefs();
    }

    private void notifyState() {
        if (avTransportImpl == null || player == null || !isDlnaActive) return;
        int state = player.getPlaybackState();
        if (state == Player.STATE_IDLE) return;
        avTransportImpl.updatePositionCache(player.getPosition(), getDuration());
        RenderState renderState = switch (state) {
            case Player.STATE_BUFFERING -> RenderState.PREPARING;
            case Player.STATE_READY -> player.isPlaying() ? RenderState.PLAYING : RenderState.PAUSED;
            case Player.STATE_ENDED -> avTransportImpl.hasNext() ? RenderState.PREPARING : RenderState.STOPPED;
            default -> null;
        };
        if (renderState != null) avTransportImpl.fireStateChange(renderState);
    }

    private final Runnable positionUpdater = new Runnable() {
        @Override
        public void run() {
            if (player != null && avTransportImpl != null && player.isPlaying()) avTransportImpl.updatePositionCache(player.getPosition(), getDuration());
            if (player != null) App.post(this, 1000);
        }
    };

    private long getDuration() {
        long duration = player.getDuration();
        return duration == C.TIME_UNSET || duration <= 0 ? -1 : duration;
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            notifyState();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            notifyState();
        }
    };

    private final PlaybackService.PlayerCallback playerCallback = new PlaybackService.PlayerCallback() {
        @Override
        public void onPlayerRebuild(Player newPlayer) {
            if (currentListenerPlayer != null) currentListenerPlayer.removeListener(listener);
            currentListenerPlayer = newPlayer;
            newPlayer.addListener(listener);
            notifyState();
        }
    };

    public class LocalBinder extends Binder {

        public DLNARendererService getService() {
            return DLNARendererService.this;
        }
    }
}
