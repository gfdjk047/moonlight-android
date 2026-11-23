package com.limelight.miu;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Miu 手势注入服务（AccessibilityService）。
 * 接收来自 MiuHelpService 的 input 消息（SPEC.md 协议），注入为真实触摸：
 *   tap / swipe —— dispatchGesture(StrokeDescription)
 *   back / home / recents —— performGlobalAction
 *   text —— 逐字符走粘贴板+ACTION_PASTE 的轻量实现留给下一版（本版注入前两类）
 * 屏幕参数由系统时时读取，坐标 0~1 归一化乘以实际分辨率（含状态栏，竖屏基准）。
 */
public class MiuGestureService extends AccessibilityService {

    /** 供 MiuHelpService 投递消息的静态通道（服务连接/断开时置空） */
    public static volatile MiuGestureService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* 不消费事件 */ }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** 解析并执行一条 input 消息（来自信令服务器透传） */
    public static void dispatch(String json) {
        MiuGestureService g = instance;
        if (g == null) return; // 未启用无障碍：静默丢弃（UI 侧负责引导开启）
        try {
            JSONObject m = new JSONObject(json);
            String action = m.optString("action");

            if ("back".equals(action)) {
                g.performGlobalAction(GLOBAL_ACTION_BACK);
            } else if ("home".equals(action)) {
                g.performGlobalAction(GLOBAL_ACTION_HOME);
            } else if ("recents".equals(action)) {
                g.performGlobalAction(GLOBAL_ACTION_RECENTS);
            } else if ("tap".equals(action)) {
                g.tap(m.optDouble("x", 0.5), m.optDouble("y", 0.5));
            } else if ("swipe".equals(action)) {
                String phase = m.optString("phase");
                if ("start".equals(phase)) {
                    g.swipeBegin(m.optDouble("x", 0.5), m.optDouble("y", 0.5));
                } else if ("move".equals(phase)) {
                    g.swipeMove(m.optDouble("x", 0.5), m.optDouble("y", 0.5));
                } else { // end
                    g.swipeEnd(m.optDouble("x", 0.5), m.optDouble("y", 0.5));
                }
            } else if ("text".equals(action)) {
                // TODO 下一版本：剪贴板 + ACTION_FOCUS/ACTION_PASTE 逐段注入
            }
        } catch (JSONException ignored) { }
    }

    // ---------------- 坐标换算与手势 ----------------

    private android.util.DisplayMetrics metrics() {
        return getResources().getDisplayMetrics();
    }

    private void tap(double nx, double ny) {
        android.util.DisplayMetrics dm = metrics();
        int x = (int) Math.round(nx * dm.widthPixels);
        int y = (int) Math.round(ny * dm.heightPixels);
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
        dispatchGesture(b.build(), null, null);
    }

    // 简易滑动实现：move 事件直接生成短直线段（从上一点到当前点）
    // 状态存 lastX/lastY；end 时补一条到终点的段
    private Float lastX = null, lastY = null;

    private void swipeBegin(double nx, double ny) {
        android.util.DisplayMetrics dm = metrics();
        lastX = (float) Math.round(nx * dm.widthPixels);
        lastY = (float) Math.round(ny * dm.heightPixels);
    }

    private void swipeMove(double nx, double ny) {
        if (lastX == null) { swipeBegin(nx, ny); return; }
        android.util.DisplayMetrics dm = metrics();
        float x = (float) Math.round(nx * dm.widthPixels);
        float y = (float) Math.round(ny * dm.heightPixels);
        segment(lastX, lastY, x, y);
        lastX = x; lastY = y;
    }

    private void swipeEnd(double nx, double ny) {
        swipeMove(nx, ny);
        lastX = lastY = null;
    }

    private void segment(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        // continueStroke 需要 API 26+；低版本退化为独立手勢段
        if (Build.VERSION.SDK_INT >= 26) {
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 24, true));
        } else {
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 40));
        }
        dispatchGesture(b.build(), null, null);
    }
}
