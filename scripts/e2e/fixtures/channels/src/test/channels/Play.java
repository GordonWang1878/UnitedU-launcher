package test.channels;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** 节目的 intent_uri 指到这里;e2e 读日志 CHFIX play p=<n> 认是哪个节目被启动。 */
public class Play extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = getIntent().getIntExtra("p", -1);
        Log.i(Publish.TAG, "play p=" + p);
        TextView t = new TextView(this);
        t.setText("Playing " + p);
        t.setTextSize(48);
        setContentView(t);
    }
}
