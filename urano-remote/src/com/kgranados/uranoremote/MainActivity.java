package com.kgranados.uranoremote;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * App WebView para el celular: carga el panel del stick a pantalla completa
 * e inyecta el token para que funcione sin escribirlo.
 */
public class MainActivity extends Activity {

    private static final String URL = "http://192.168.0.7:8080/";
    // Injected at build time from local.properties (see build.sh); never commit it.
    private static final String TOKEN = BuildConfig.TOKEN;

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(Color.parseColor("#0b1020"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage para el token
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // navegar dentro del WebView
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Inyecta el token una vez si no existe (y recarga para que lo use el polling)
                String js = "try{if(localStorage.getItem('urano_token')!=='" + TOKEN + "'){"
                        + "localStorage.setItem('urano_token','" + TOKEN + "');location.reload();}}catch(e){}";
                view.evaluateJavascript(js, null);
            }
        });

        setContentView(web);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(URL);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }
}
