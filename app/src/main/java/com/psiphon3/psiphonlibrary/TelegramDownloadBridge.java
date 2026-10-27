package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.os.Handler;
import android.util.Base64;
import android.webkit.JavascriptInterface;

/** Saves the files that the Telegram page builds in memory into the public download folder. */
public final class TelegramDownloadBridge {

    public static final String JS_NAME = "ShirOKhorshidDownload";

    private static final int MAX_BYTES = 52428800;
    private static final String ERROR_TOO_LARGE = "the file is too large";

    /**
     * Installs the download interception once per page. Blob and data targets are fetched in the
     * page, capped at 50 MB, base64 encoded in 32 KB chunks and handed to the bridge.
     */
    public static final String JS_HOOK =
            "(function(){"
            + "if(window.__shiroHook){return;}"
            + "window.__shiroHook=true;"
            + "var MAX=52428800;"
            + "function post(text){try{" + JS_NAME + ".postError(text);}catch(e){}}"
            + "function mimeOf(href){"
            + "try{"
            + "if(href.indexOf('data:')===0){"
            + "var start=5;"
            + "var stop=href.indexOf(';');"
            + "var comma=href.indexOf(',');"
            + "if(stop<0||(comma>=0&&comma<stop)){stop=comma;}"
            + "if(stop>start){return href.substring(start,stop);}"
            + "}"
            + "}catch(e){}"
            + "return '';"
            + "}"
            + "function handle(href,name){"
            + "try{"
            + "if(!href){return false;}"
            + "if(href.indexOf('blob:')!==0&&href.indexOf('data:')!==0){return false;}"
            + "var mime=mimeOf(href);"
            + "fetch(href).then(function(response){"
            + "var length=0;"
            + "try{length=parseInt(response.headers.get('content-length'),10);}catch(e){length=0;}"
            + "if(length>MAX){post('" + ERROR_TOO_LARGE + "');return null;}"
            + "return response.arrayBuffer().then(function(buffer){"
            + "if(!buffer||buffer.byteLength>MAX){post('" + ERROR_TOO_LARGE + "');return;}"
            + "var bytes=new Uint8Array(buffer);"
            + "var binary='';"
            + "var step=32768;"
            + "for(var i=0;i<bytes.length;i+=step){"
            + "binary+=String.fromCharCode.apply(null,bytes.subarray(i,Math.min(i+step,bytes.length)));"
            + "}"
            + "try{" + JS_NAME + ".postFile(window.btoa(binary),mime,name||'');}"
            + "catch(e){post('download failed');}"
            + "});"
            + "}).catch(function(e){post('download failed');});"
            + "return true;"
            + "}catch(e){post('download failed');return true;}"
            + "}"
            + "var originalClick=HTMLAnchorElement.prototype.click;"
            + "HTMLAnchorElement.prototype.click=function(){"
            + "try{"
            + "var href=this.href||'';"
            + "var name=this.getAttribute('download');"
            + "if(name!==null||href.indexOf('blob:')===0||href.indexOf('data:')===0){"
            + "if(handle(href,name||'')){return;}"
            + "}"
            + "}catch(e){}"
            + "return originalClick.apply(this,arguments);"
            + "};"
            + "var originalOpen=window.open;"
            + "window.open=function(url){"
            + "try{"
            + "var target=(url===undefined||url===null)?'':String(url);"
            + "if(target.indexOf('blob:')===0||target.indexOf('data:')===0){"
            + "if(handle(target,'')){return null;}"
            + "}"
            + "}catch(e){}"
            + "return originalOpen.apply(window,arguments);"
            + "};"
            + "})();";

    private final Context appContext;
    private final Handler main;

    public TelegramDownloadBridge(Context ctx, Handler main) {
        Context app = ctx == null ? null : ctx.getApplicationContext();
        this.appContext = app == null ? ctx : app;
        this.main = main;
    }

    @JavascriptInterface
    public void postFile(String base64, String mime, String name) {
        if (base64 == null || base64.isEmpty() || appContext == null) {
            return;
        }
        byte[] data;
        try {
            data = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (data == null || data.length == 0) {
            return;
        }
        if (data.length > MAX_BYTES) {
            postError(ERROR_TOO_LARGE);
            return;
        }
        String type = mime == null ? "" : mime.trim();
        TelegramDownloader.saveBytes(appContext, main, data, name, type,
                new TelegramDownloader.Result() {
                    @Override
                    public void onDone(String path) {
                    }

                    @Override
                    public void onError(String message) {
                    }
                });
    }

    @JavascriptInterface
    public void postError(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
    }
}
