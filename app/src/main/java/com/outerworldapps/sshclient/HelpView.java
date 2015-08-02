/**
 * Display help page in a web view.
 * Allows internal links to other resource pages
 * and allows external links to web pages.
 */

//    Copyright (C) 2014, Mike Rieker, Beverly, MA USA
//    www.outerworldapps.com
//
//    This program is free software; you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation; version 2 of the License.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    EXPECT it to FAIL when someone's HeALTh or PROpeRTy is at RISk.
//
//    You should have received a copy of the GNU General Public License
//    along with this program; if not, write to the Free Software
//    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//    http://www.gnu.org/licenses/gpl-2.0.html

package com.outerworldapps.sshclient;


import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Message;
import android.webkit.WebView;

public class HelpView extends WebView {
    public static final String TAG = "SshClient";

    private static WVJSHandler wvjsHandler;

    private SshClient sshclient;

    public HelpView (SshClient sc)
    {
        super (sc);
        sshclient = sc;

        if (wvjsHandler == null) wvjsHandler = new WVJSHandler ();

        getSettings ().setBuiltInZoomControls (true);
        getSettings ().setJavaScriptEnabled (true);
        addJavascriptInterface (new JavaScriptObject (), "hvjso");

        loadHtmlAsset ("help.html");
    }

    /**
     * Display the named HTML asset in the view.
     */
    public void loadHtmlAsset (String assetName)
    {
        loadUrl ("file:///android_asset/" + assetName);
    }

    /**
     * Accessed via javascript in the internal .HTML files.
     */
    private class JavaScriptObject {

        @SuppressWarnings ("unused")
        public String getVersionName ()
        {
            try {
                PackageInfo pInfo = sshclient.getPackageManager ().getPackageInfo (sshclient.getPackageName (), 0);
                return pInfo.versionName;
            } catch (PackageManager.NameNotFoundException nnfe) {
                return "";
            }
        }

        @SuppressWarnings ("unused")
        public int getVersionCode ()
        {
            try {
                PackageInfo pInfo = sshclient.getPackageManager ().getPackageInfo (sshclient.getPackageName (), 0);
                return pInfo.versionCode;
            } catch (PackageManager.NameNotFoundException nnfe) {
                return -1;
            }
        }

        @SuppressWarnings ("unused")
        public String getJSchVersion ()
        {
            return com.jcraft.jsch.JSch.VERSION;
        }

        @SuppressWarnings ("unused")
        public void backButton ()
        {
            wvjsHandler.obtainMessage (WVJSHandler.BACKBUTT, HelpView.this).sendToTarget ();
        }
    }

    /**
     * Handler executes in UI thread as a result of
     * javascript calls in the internal web pages.
     */
    private static class WVJSHandler extends Handler {
        public final static int BACKBUTT = 1;

        @Override
        public void handleMessage (Message m)
        {
            switch (m.what) {
                case BACKBUTT: {
                    ((HelpView) m.obj).goBack ();
                    break;
                }
                default: throw new RuntimeException ();
            }
        }
    }
}
