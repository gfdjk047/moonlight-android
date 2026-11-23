package com.limelight.miu;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Miu 求助模式入口。
 * 体验流：
 *   打开 → （首次）填/默认信令服务器 → 点「上线可被呼叫」→ 前台服务常驻
 *   控制台呼叫 → 通知+本页弹「同意/拒绝」→ 同意后接受输入控制
 * 横到竖屏自适应：全部 programmatic UI，不依赖 res/layout。
 */
public class MiuHelpActivity extends Activity {

    private TextView status, log;
    private EditText hostInput;
    private BroadcastReceiver recv;
    private String pendingRequestId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 96, 48, 48);
        int accent = 0xFF58A6FF;

        TextView title = new TextView(this);
        title.setText("Miu 求助模式");
        title.setTextSize(24);
        title.setTextColor(accent);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("上线后，你的朋友可以在控制台呼叫你；你点同意，对方才能看到并操控这台手机。");
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, 24);
        root.addView(sub);

        hostInput = new EditText(this);
        hostInput.setHint("信令服务器（默认 192.168.1.141:8080）");
        hostInput.setText(getSharedPreferences("miu_prefs", MODE_PRIVATE)
                .getString("server", "192.168.1.141:8080"));
        root.addView(hostInput);

        Button online = new Button(this);
        online.setText("上线，可被呼叫");
        online.setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            if (host.isEmpty()) { toast("先填服务器地址"); return; }
            getSharedPreferences("miu_prefs", MODE_PRIVATE)
                    .edit().putString("server", host).apply();
            Intent i = new Intent(this, MiuHelpService.class);
            i.putExtra(MiuHelpService.EXTRA_SERVER, host);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            toast("已上线（服务常驻，可返回）");
        });
        root.addView(online);

        Button offline = new Button(this);
        offline.setText("下线");
        offline.setOnClickListener(v -> {
            stopService(new Intent(this, MiuHelpService.class));
            setStatus("已下线");
            toast("已下线");
        });
        root.addView(offline);

        Button a11y = new Button(this);
        a11y.setText("开启操控权限（无障碍）");
        a11y.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                toast("无法打开设置：" + e.getMessage());
            }
        });
        root.addView(a11y);

        status = new TextView(this);
        setStatus("未上线");
        status.setTextColor(0xFF3FB950);
        status.setPadding(0, 32, 0, 8);
        root.addView(status);

        log = new TextView(this);
        log.setText("");
        log.setTextSize(11);
        log.setTextColor(0xFF8B949E);
        log.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400);
        root.addView(log, lp);

        setContentView(root);

        // 通知栏来电 → 本页弹接听条
        if (getIntent().hasExtra(MiuHelpService.EXTRA_REQ)) {
            pendingRequestId = getIntent().getStringExtra(MiuHelpService.EXTRA_REQ);
            showIncoming(getIntent().getStringExtra(MiuHelpService.EXTRA_TEXT));
        }
    }

    private void showIncoming(String from) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        TextView t = new TextView(this);
        t.setText("来电：" + from + "  ");
        bar.addView(t);
        Button acc = new Button(this); acc.setText("同意");
        Button den = new Button(this); den.setText("拒绝");
        bar.addView(acc); bar.addView(den);
        appendLog("[来电] " + from);
        final LinearLayout r = (LinearLayout) status.getParent();
        int idx = r.indexOfChild(status);
        r.addView(bar, idx);
        java.util.function.BiConsumer<Boolean, View> done = (ok, v) -> {
            // 静态应答（服务持有信令连接）
            MiuHelpService.respondStatic(pendingRequestId, ok);
            r.removeView(bar);
            appendLog(ok ? "[已同意] 等待画面功能（下一版本）" : "[已拒绝]");
        };
        acc.setOnClickListener(v -> done.accept(true, v));
        den.setOnClickListener(v -> done.accept(false, v));
    }

    private void setStatus(String s) { if (status != null) status.setText(s); }
    private void appendLog(String s) {
        if (log != null) log.append(s + "\n");
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onStart() {
        super.onStart();
        recv = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String t = i.getStringExtra(MiuHelpService.EXTRA_TEXT);
                if (MiuHelpService.ACTION_STATUS.equals(i.getAction())) {
                    setStatus(t); appendLog(t);
                } else if (MiuHelpService.ACTION_INCOMING.equals(i.getAction())) {
                    pendingRequestId = i.getStringExtra(MiuHelpService.EXTRA_REQ);
                    showIncoming(t);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(MiuHelpService.ACTION_STATUS);
        f.addAction(MiuHelpService.ACTION_INCOMING);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(recv, f, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recv, f);
        }
    }

    @Override
    protected void onStop() {
        if (recv != null) unregisterReceiver(recv);
        super.onStop();
    }
}
