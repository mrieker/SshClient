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


import android.annotation.SuppressLint;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Message;
import android.webkit.WebView;

@SuppressLint("ViewConstructor")
public class HelpView extends WebView {
    public static final String TAG = "SshClient";

    private static WVJSHandler wvjsHandler;

    private SshClient sshclient;

    @SuppressLint({ "SetJavaScriptEnabled", "AddJavascriptInterface" })
    public HelpView (SshClient sc)
    {
        super (sc);
        sshclient = sc;

        if (wvjsHandler == null) wvjsHandler = new WVJSHandler ();

        getSettings ().setBuiltInZoomControls (true);
        getSettings ().setJavaScriptEnabled (true);
        getSettings ().setSupportZoom (true);
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
        @android.webkit.JavascriptInterface
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
        @android.webkit.JavascriptInterface
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
        @android.webkit.JavascriptInterface
        public String getJSchVersion ()
        {
            return com.jcraft.jsch.JSch.VERSION;
        }

        @SuppressWarnings ("unused")
        @android.webkit.JavascriptInterface
        public void backButton ()
        {
            wvjsHandler.obtainMessage (WVJSHandler.BACKBUTT, HelpView.this).sendToTarget ();
        }

        @SuppressWarnings ("unused")
        @android.webkit.JavascriptInterface
        public String getGithubLink ()
        {
            String fullhash = BuildConfig.GitHash;
            String abbrhash = fullhash.substring (0, 7);
            String status = BuildConfig.GitStatus;
            String[] lines = status.split ("\n");
            for (String line : lines) {
                line = line.trim ();
                if (line.startsWith ("On branch ")) {
                    if (line.equals ("On branch github")) {
                        String link = "https://github.com/mrieker/SshClient/commit/" + fullhash;
                        return "<A HREF=\"" + link + "\">" + abbrhash + "</A>";
                    }
                    break;
                }
            }
            return abbrhash;
        }

        @SuppressWarnings ("unused")
        @android.webkit.JavascriptInterface
        public boolean getGitDirtyFlag ()
        {
            String status = BuildConfig.GitStatus;
            String[] lines = status.split ("\n");
            for (String line : lines) {
                if (line.contains ("modified:") && !line.contains ("app.iml")) {
                    return true;
                }
            }
            return false;
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
            if (m.what == BACKBUTT) {
                ((HelpView) m.obj).goBack ();
            } else {
                throw new RuntimeException ();
            }
        }
    }
}
