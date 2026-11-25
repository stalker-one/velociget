package io.encodal.idm;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A small browser used only to sign in to a site once, so private or login-only posts can be read. */
public class BrowserActivity extends Activity {
    private WebView wv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF1D4ED8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1D4ED8);
        bar.setPadding(dp(16), dp(10), dp(8), dp(10));
        TextView t = new TextView(this);
        t.setText("Sign in, then tap Done");
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView done = new TextView(this);
        done.setText("Done");
        done.setTextColor(Color.WHITE);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setPadding(dp(16), dp(8), dp(16), dp(8));
        done.setOnClickListener(v -> finish());
        bar.addView(done);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.setWebViewClient(new WebViewClient());
        root.addView(wv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        String u = getIntent().getStringExtra("url");
        wv.loadUrl(u == null ? "https://www.google.com" : u);
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
