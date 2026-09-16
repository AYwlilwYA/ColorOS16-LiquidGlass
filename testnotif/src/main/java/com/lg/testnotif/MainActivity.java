package com.lg.testnotif;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setText("点击发高优先级通知（触发 heads-up）");
        tv.setTextSize(20);
        setContentView(tv);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NotificationChannel ch = new NotificationChannel(
                        "test_hu", "Test HeadsUp", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("heads-up test channel");
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.createNotificationChannel(ch);
                Notification n = new Notification.Builder(MainActivity.this, "test_hu")
                        .setContentTitle("LG HeadsUp 测试")
                        .setContentText("通知横幅抓屏测试")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .setCategory(Notification.CATEGORY_MESSAGE)
                        .setAutoCancel(true)
                        .build();
                nm.notify(1001, n);
                Toast.makeText(MainActivity.this, "已发通知", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
