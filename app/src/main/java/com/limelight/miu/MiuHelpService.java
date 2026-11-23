package com.limelight.miu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Miu 求助模块前台服务。
 * 职责：
 *  1) 连接 miu-console 信令服务器并以 device 身份 register（"上线可被呼叫"）
 *  2) 收 incoming_request 时弹高优先级通知 + 广播给 MiuHelpActivity 弹窗
 *  3) accept 后：input 消息转投 MiuGestureService 注入（画面采集=下一版本）
 * 契约见 miu-console/SPEC.md（与 server.py 实机对齐 v1）。
 */
public class MiuHelpService extends Service {

    public static final String EXTRA_SERVER = "miu.server";
    public static final String ACTION_INCOMING = "miu.action.INCOMING";
    public static final String ACTION_STATUS = "miu.action.STATUS";
    public static final String EXTRA_REQ = "miu.extra.REQ";
    public static final String EXTRA_TEXT = "miu.extra.TEXT";

    private static final String CHAN_ID = "miu_help";
    private static final int NOTIF_ID = 0x81;

    private WebSocket ws;
    private OkHttpClient http;
    private String serverHost;
    private String currentRequestId;
    private volatile boolean sessionActive = false;

    /** 静态引用：Activity 应答来电时用（与服务生命周期解耦） */
    public static volatile MiuHelpService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHAN_ID, "Miu 求助模式", NotificationManager.IMPORTANCE_LOW));
        }
        startForegroundCompat();
        http = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .build();
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification("正在连接信令服务器…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, buildNotification("正在连接信令服务器…"));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_SERVER)) {
            String host = intent.getStringExtra(EXTRA_SERVER);
            if (!host.equals(serverHost) || ws == null) {
                serverHost = host;
                connect(host);
            }
        }
        return START_STICKY;
    }

    // ---------------- 信令层 ----------------

    private void connect(String host) {
        String url = "ws://" + host + "/ws";
        setStatus("连接中 " + host);
        Request req = new Request.Builder().url(url).build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response response) {
                String name = Build.MANUFACTURER + " " + Build.MODEL;
                String devid = android.provider.Settings.Secure.getString(
                        getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                try {
                    JSONObject dev = new JSONObject();
                    dev.put("name", name);
                    dev.put("devid", devid);
                    JSONObject msg = new JSONObject();
                    msg.put("type", "register");
                    msg.put("device", dev);
                    w.send(msg.toString());
                } catch (JSONException ignored) {}
            }

            @Override public void onMessage(WebSocket w, String text) { onSignal(text); }

            @Override public void onFailure(WebSocket w, Throwable t, Response response) {
                setStatus("连接断开（" + t.getClass().getSimpleName() + "），10 秒后重连");
                scheduleReconnect();
            }

            @Override public void onClosed(WebSocket w, int code, String reason) {
                setStatus("连接关闭（" + code + "），10 秒后重连");
                scheduleReconnect();
            }
        });
    }

    private final android.os.Handler main = new android.os.Handler(getMainLooper());

    private void scheduleReconnect() {
        if (serverHost != null) {
            main.postDelayed(() -> connect(serverHost), 10_000);
        }
    }

    private void onSignal(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String t = msg.optString("type");

            if ("registered".equals(t)) {
                setStatus("已上线（" + msg.optString("id") + "）—— 可被呼叫");
            } else if ("incoming_request".equals(t)) {
                currentRequestId = msg.optString("request_id");
                notifyIncoming(msg.optString("from"));
            } else if ("sdp".equals(t) || "ice".equals(t)) {
                // 画面协商由下一版本实现（MediaProjection+WebRTC）；本期不参与
                if (!sessionActive) sendJson("{\"type\":\"bye\"}");
            } else if ("input".equals(t)) {
                // 输入透传给无障碍服务注入（骨架期就能真实点到手机屏幕）
                MiuGestureService.dispatch(text);
            }

        } catch (JSONException ignored) {}
    }

    /** 由 MiuHelpActivity 调用：用户点了同意/拒绝（静态入口） */
    public static void respondStatic(String requestId, boolean accept) {
        MiuHelpService s = instance;
        if (s != null) {
            s.currentRequestId = requestId;
            s.respond(accept);
        }
    }

    /** 由 MiuHelpActivity 调用：用户点了同意/拒绝 */
    public void respond(boolean accept) {
        if (currentRequestId == null) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", accept ? "accept" : "deny");
            m.put("request_id", currentRequestId);
            sendJson(m.toString());
            if (accept) {
                sessionActive = true;
                setStatus("会话中——输入通道就绪（画面功能下一版本）");
            }
        } catch (JSONException ignored) {}
        currentRequestId = null;
    }

    private void sendJson(String json) {
        WebSocket w = ws;
        if (w != null) w.send(json);
    }

    // ---------------- 通知与状态 ----------------

    private void setStatus(String s) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_TEXT, s);
        sendBroadcast(i);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(s));
    }

    private void notifyIncoming(String from) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    "miu_incoming", "求助来电", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(this, MiuHelpActivity.class);
        open.putExtra(EXTRA_REQ, currentRequestId);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "miu_incoming")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("远程协助请求")
                .setContentText("来自 " + from + " —— 点击处理")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(0x82, n);
        Intent i = new Intent(ACTION_INCOMING);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_TEXT, from);
        i.putExtra(EXTRA_REQ, currentRequestId);
        sendBroadcast(i);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MiuHelpActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHAN_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_help)
                .setContentTitle("Miu 求助模式")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        if (ws != null) ws.close(1000, "bye");
        super.onDestroy();
    }
}
