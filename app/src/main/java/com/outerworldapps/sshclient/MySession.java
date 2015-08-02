/**
 * Session management.
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


import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.AsyncTask;
import android.text.ClipboardManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.util.HashMap;
import java.util.TreeMap;

/**
 * A session screen consists of a box to enter username@hostname[:portnumber] in
 * and a box that displays screen data from the host.
 */
public class MySession extends LinearLayout implements SshClient.HasMainMenu {
    public static final String TAG = "SshClient";
    public static final int CONN_TIMEOUT_MS = 30000;

    public final static int MSM_SHELL  = 1;
    public final static int MSM_FILES  = 2;
    public final static int MSM_TUNNEL = 3;
    public final static int MSM_VNCCLI = 4;
    public final static String[] modeNames = new String[5];

    private AlertDialog currentAlertDialog;
    private HostNameText hostnametext;
    private int screenMode;                       // MSM_*
    private int sessionNumber;
    private JschUserInfo jschuserinfo;            // callbacks to get stuff like passphrase and password from user
    private MyFEView fileexplorerview;            // file transfer mode view
    private ScreenDataHandler screendatahandler;  // take events from screendatathread and process them in gui thread
    private ScreenDataThread screendatathread;    // connects to remote host then reads from remote host and posts to screendatahandler
    private ScreenTextBuffer screentextbuffer;    // holds shell screen data coming from host
    private ScreenTextView screentextview;        // displays screen full of characters that came from the host
    private SshClient sshclient;
    private View modeView;
    private View tunnellistview;
    private VNCView vncclientview;

    public ChannelShell getShellChannel () { return (screendatathread == null) ? null : screendatathread.channel; }
    public HostNameText getHostnametext () { return hostnametext; }
    public ScreenDataHandler getScreendatahandler () { return screendatahandler; }
    public ScreenDataThread getScreendatathread () { return screendatathread; }
    public ScreenTextBuffer getScreentextbuffer () { return screentextbuffer; }
    public ScreenTextView getScreentextview () { return screentextview; }
    public SshClient getSshClient () { return sshclient; }
    public VNCView getVNCClientView () { return vncclientview; }
    public int getSessionNumber () { return sessionNumber; }

    /**
     * Create a yet-to-be-connected session.
     */
    public MySession (SshClient sc)
    {
        super (sc);
        sshclient = sc;

        sessionNumber = sc.getNextSessionNumber ();
        screentextbuffer = new ScreenTextBuffer (this);
        screenMode = MSM_SHELL;

        CommonConstruction ();
    }

    /**
     * Create a session that is already connected.
     */
    public MySession (SshClient sc, ScreenDataThread sdt)
    {
        super (sc);
        sshclient = sc;

        screendatathread = sdt;
        screentextbuffer = sdt.screenTextBuffer;
        screenMode    = (Integer) sdt.detstate.get ("screenMode");
        sessionNumber = (Integer) sdt.detstate.get ("sessionNumber");

        CommonConstruction ();

        hostnametext.setText ((String) sdt.detstate.get ("userhostport"));
        hostnametext.SetState (HostNameText.ST_ONLINE);

        sc.setLastSessionNumber (sessionNumber);
    }

    private void CommonConstruction ()
    {
        jschuserinfo = new JschUserInfo (this);
        screendatahandler = new ScreenDataHandler (this);

        hostnametext = new HostNameText (this);
        hostnametext.setLayoutParams (new LinearLayout.LayoutParams (
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.0F
        ));

        screentextview = new ScreenTextView (this, screentextbuffer);
        screentextview.setLayoutParams (new LinearLayout.LayoutParams (
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0F
        ));

        modeNames[MSM_SHELL]  = "shell";
        modeNames[MSM_FILES]  = "file transfer";
        modeNames[MSM_TUNNEL] = "tunnel";
        modeNames[MSM_VNCCLI] = "VNC client";

        setOrientation (LinearLayout.VERTICAL);
        RebuildView ();
    }

    public String GetSessionName ()
    {
        return Integer.toString (sessionNumber) + " " + hostnametext.getText ().toString ();
    }

    /**
     * Display this session's screen and mark the session current.
     */
    public void ShowScreen ()
    {
        // mark it as the current session
        sshclient.setCurrentsession (this);

        // set the requested orientation from the settings value
        sshclient.setRequestedOrientation (sshclient.getSettings ().scr_orien.GetValue ());

        // display the screen
        sshclient.setContentView (this);

        // show the hostnametext box for a few seconds
        if (hostnametext.GetState () == HostNameText.ST_ONHIDE) {
            hostnametext.SetState (HostNameText.ST_ONLINE);
            if (screenMode == MSM_SHELL) {
                screentextview.requestFocus ();
            }
            if (screenMode == MSM_VNCCLI) {
                vncclientview.requestFocus ();
            }
        }

        // set up method to be called if this screen is back-buttoned to
        final int sm = screenMode;
        sshclient.pushBackAction (new SshClient.BackAction () {
            @Override
            public boolean okToPop ()
            {
                return okToSwitchAway ();
            }
            @Override
            public void reshow ()
            {
                SetScreenMode (sm);
            }
            @Override
            public String name ()
            {
                return "session:" + GetSessionName () + " " + sm;
            }
            @Override
            public MySession session ()
            {
                return MySession.this;
            }
        });
    }

    /**
     * Push settings changes to everything under me.
     */
    public void LoadSettings ()
    {
        screentextbuffer.LoadSettings ();
        screentextview.LoadSettings ();
        if (fileexplorerview != null) fileexplorerview.LoadSettings ();
        if (vncclientview != null) vncclientview.LoadSettings ();
    }

    /**
     * Display menu to copy the clipboard to/from a file.
     */
    public void ClipboardToFromFileMenu ()
    {
        if (fileexplorerview == null) {
            sshclient.ErrorAlert ("Clipboard <-> File", "Please open file transfer mode first");
            return;
        }

        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Clipboard <-> File");

        Button b1 = sshclient.MyButton ();
        Button b2 = sshclient.MyButton ();
        Button b3 = sshclient.MyButton ();
        Button b4 = sshclient.MyButton ();

        b1.setText ("Internal clipboard -> File");
        b2.setText ("External clipboard -> File");
        b3.setText ("File -> Internal clipboard");
        b4.setText ("File -> External clipboard");

        // internal clipboard -> file
        b1.setOnClickListener (new OnClickListener () {
            @Override
            public void onClick (View view)
            {
                currentAlertDialog.dismiss ();
                if (fileexplorerview != null) {
                    SetScreenMode (MSM_FILES);
                    fileexplorerview.pasteClipToFile ("internal clipboard", sshclient.internalClipboard.getBytes ());
                }
            }
        });

        // external clipboard -> file
        b2.setOnClickListener (new OnClickListener () {
            @Override
            public void onClick (View view)
            {
                currentAlertDialog.dismiss ();
                if (fileexplorerview != null) {
                    SetScreenMode (MSM_FILES);
                    ClipboardManager cbm = (ClipboardManager) sshclient.getSystemService (Activity.CLIPBOARD_SERVICE);
                    fileexplorerview.pasteClipToFile ("external clipboard", cbm.getText ().toString ().getBytes ());
                }
            }
        });

        // file -> internal clipboard
        b3.setOnClickListener (new OnClickListener () {
            @Override
            public void onClick (View view)
            {
                currentAlertDialog.dismiss ();
                if (fileexplorerview != null) {
                    SetScreenMode (MSM_FILES);
                    byte[] clip = fileexplorerview.copyClipFromFile ("internal clipboard");
                    if (clip != null) {
                        sshclient.internalClipboard = new String (clip);
                    }
                }
            }
        });

        // file -> external clipboard
        b4.setOnClickListener (new OnClickListener () {
            @Override
            public void onClick (View view)
            {
                currentAlertDialog.dismiss ();
                if (fileexplorerview != null) {
                    SetScreenMode (MSM_FILES);
                    byte[] clip = fileexplorerview.copyClipFromFile ("external clipboard");
                    if (clip != null) {
                        ClipboardManager cbm = (ClipboardManager) sshclient.getSystemService (Activity.CLIPBOARD_SERVICE);
                        cbm.setText (new String (clip));
                    }
                }
            }
        });

        LinearLayout ll = new LinearLayout (sshclient);
        ll.setOrientation (LinearLayout.VERTICAL);
        ll.addView (b1);
        ll.addView (b2);
        ll.addView (b3);
        ll.addView (b4);

        ab.setView (ll);
        ab.setNegativeButton ("Cancel", null);
        currentAlertDialog = ab.show ();
    }

    /**
     * Set the session's mode, MSM_*
     */
    public void SetScreenMode (int sm)
    {
        if ((sm == MSM_FILES) || okToSwitchAway ()) {
            if (screenMode != sm) {
                screenMode = sm;

                // if we aren't connected yet, output message to shell screen
                // saying what mode it will go in when it connects
                if (screendatathread == null) ScreenMsg (modeNames[screenMode] + " mode selected\r\n");

                // if we are connected, rebuild our view based on new shell/filetransfer/tunnel/vnccli mode
                // also save new mode in case we detach and re-attach.
                else {
                    RebuildView ();
                    screendatathread.detstate.put ("screenMode", screenMode);
                }
            }

            // if going to shell mode, make sure shell thread is running
            if ((sm == MSM_SHELL) && (screendatathread != null)) {
                screendatathread.startshellmode (sshclient.getSettings ().GetTermTypeStr ());
            }

            // make sure this screen is being displayed
            ShowScreen ();
        }
    }

    /**
     * See if it is OK to switch away from the current screen.
     * We can't let them use the BACK button cuz it would leave unreferenced threads running in background.
     * We can't let them switch screen mode cuz shell or tunnel modes tend to hang with transfers in progress.
     * HOME button is ok cuz it leaves the screen where it is when they re-open the app.
     * They can also switch to a different session cuz it's a different TCP connection and then they can come
     * back to this session with the screen intact.
     */
    private boolean okToSwitchAway ()
    {
        return (screenMode != MSM_FILES) ||
                (fileexplorerview == null) ||
                !fileexplorerview.hasXfersRunning (true);
    }

    /**
     * Start connecting to an username@hostname[:portnumber].
     */
    public void StartConnecting ()
    {
        /*
         * Make sure we aren't already in the middle of connecting.
         */
        if (hostnametext.GetState () != HostNameText.ST_CONN) {

            /*
             * Close any old connection.
             */
            Disconnect ();

            /*
             * Start a thread to open new connection.
             */
            Log.d (TAG, "starting connection thread");
            screentextview.Reset ();
            hostnametext.SetState (HostNameText.ST_CONN);
            ConnectionAsyncTask cat = new ConnectionAsyncTask ();
            cat.execute (hostnametext.getText ().toString ());
        }
    }

    private class ConnectionAsyncTask extends AsyncTask<String,Void,Object> {
        private String userhostport;
        private String keypairident;

        /**
         * Perform connection in a thread cuz it takes a while.
         * Also might have GUI interaction to ask for stuff like password.
         */
        @Override
        protected Object doInBackground (String[] params)
        {
            String username;
            String hostname;
            int portnumber;

            try {
                userhostport = params[0];
                int i = userhostport.indexOf ('@');
                if (i < 0) throw new Exception ("missing @ in " + userhostport);
                username   = userhostport.substring (0, i).trim ();
                hostname   = userhostport.substring (++ i).trim ();
                portnumber = 22;
                i = hostname.indexOf (':');
                if (i >= 0) {
                    try {
                        portnumber = Integer.parseInt (hostname.substring (i + 1).trim ());
                    } catch (NumberFormatException nfe) {
                        throw new Exception ("bad portnumber in " + userhostport);
                    }
                    hostname = hostname.substring (0, i).trim ();
                }
                userhostport = username + "@" + hostname +
                        ((portnumber == 22) ? "" : (":" + portnumber));
            } catch (Exception e) {
                return e;
            }

            ScreenMsg ("\r\n[" + ScreenDataThread.hhmmssNow () + "] connecting to " + userhostport + "\r\n");
            Session jses;
            try {
                JSch jsch = new JSch ();
                jsch.setHostKeyRepository (sshclient.getMyhostkeyrepo ());
                ScreenMsg ("...selecting keypair\r\n");
                SavedLogin savedlogin = sshclient.getSavedlogins ().get (userhostport);
                keypairident = null;
                if ((savedlogin == null) || (savedlogin.getKeypair () != null)) {
                    keypairident = SelectKeypair ((savedlogin == null) ? null : savedlogin.getKeypair ());
                }
                if (keypairident != null) {
                    ScreenMsg ("...using keypair " + keypairident + "\r\n");
                    byte[] prvkey = sshclient.getMasterPassword ().ReadEncryptedFileBytes (sshclient.getPrivatekeyfilename (keypairident));
                    byte[] pubkey = sshclient.getMasterPassword ().ReadEncryptedFileBytes (sshclient.getPublickeyfilename (keypairident));
                    jsch.addIdentity (keypairident, prvkey, pubkey, null);
                } else {
                    ScreenMsg ("...not using a keypair\r\n");
                }
                ScreenMsg ("...setting up session\r\n");
                jses = jsch.getSession (username, hostname, portnumber);
                jschuserinfo.dbpassword   = null;       // no password is available from database
                jschuserinfo.password     = null;       // no password has been entered by user
                jschuserinfo.savePassword = false;      // user has not checked 'save password' checkbox
                if (savedlogin != null) {
                    String savedpw = savedlogin.getPassword ();
                    jschuserinfo.dbpassword = savedpw;  // this password is available in database
                }
                jses.setPassword ("");
                jses.setUserInfo (jschuserinfo);
                ScreenMsg ("...connecting to host\r\n");
                jses.connect (CONN_TIMEOUT_MS);
                ScreenMsg ("...connection complete\r\n");
                return jses;
            } catch (Exception e) {
                Log.w (TAG, "connect error", e);
                return e;
            }
        }

        /**
         * Select keypair suitable for this connection.
         * @param lastone = null: no keypair was used last time we connected to this username@hostname[:portnumber]
         *                  else: ident of keypair used last time
         * returns null: do not use a keypair for connection
         *         else: ident of keypair to use for connection
         */
        private String SelectKeypair (String lastone)
                throws Exception
        {
            /*
             * Get sorted list of all defined keypair files.
             */
            TreeMap<String,String> matches = new LocalKeyPairMenu (sshclient).GetExistingKeypairIdents ();

            /*
             * If empty, can't possibly use any keypair.
             * If only one, might as well try that one.
             * If still have the last one around, try that one.
             */
            if (matches.size () == 0) return null;
            if (matches.size () == 1) return matches.firstKey ();
            if ((lastone != null) && matches.containsKey (lastone)) return lastone;

            /*
             * More than one possible, display selection menu and wait for answer.
             */
            screendatahandler.obtainMessage (ScreenDataHandler.SELKEYPAIR, matches).sendToTarget ();
            String answer;
            synchronized (matches) {
                while (!matches.containsKey ("<<answer>>")) {
                    matches.wait ();
                }
                answer = matches.get ("<<answer>>");
            }
            if ("<<cancel>>".equals (answer)) throw new Exception ("keypair selection cancelled");
            return answer;
        }

        /**
         * Back in GUI thread, connected or not.
         */
        @Override
        protected void onPostExecute (Object result)
        {
            if (result instanceof Exception) {

                /*
                 * Connect failed, turn user@host[:port] box red.
                 */
                hostnametext.SetState (HostNameText.ST_DISCO);

                /*
                 * Display error alert.
                 */
                sshclient.ErrorAlert ("Connect error", SshClient.GetExMsg ((Exception)result));
            } else {

                /*
                 * Successful, turn user@host[:port] box green.
                 */
                hostnametext.setText (userhostport);
                hostnametext.SetState (HostNameText.ST_ONLINE);

                /*
                 * Remember this user@host[:port] for future autocompletes.
                 * Also remember the corresponding keypair if any,
                 * and maybe user wants to save the password.
                 */
                String uhp = userhostport;
                String kpi = keypairident;
                String pwd = jschuserinfo.savePassword ? jschuserinfo.getPassword () : null;
                SavedLogin sh = new SavedLogin (uhp, kpi, pwd);
                sshclient.getSavedlogins ().put (sh);
                sshclient.getSavedlogins ().SaveChanges ();

                /*
                 * Alloc shell screen data receiver thread in case user wants it sometime.
                 * Also informs service that we have a connection alive.
                 */
                screendatathread = sshclient.getJSessionService ().createScreenDataThread ();
                screendatathread.jsession = (Session) result;
                screendatathread.screenTextBuffer = screentextbuffer;

                /*
                 * Save state that we can restore after a detach/re-attach.
                 */
                screendatathread.detstate.put ("screenMode",    screenMode);
                screendatathread.detstate.put ("sessionNumber", sessionNumber);
                screendatathread.detstate.put ("userhostport",  userhostport);

                /*
                 * Start the requested mode.
                 * For shell mode, start the receiver thread.
                 * Otherwise, rebuild the view contents for file transfer or tunnel mode.
                 */
                if (screenMode == MSM_SHELL) {
                    screendatathread.startshellmode (sshclient.getSettings ().GetTermTypeStr ());
                    modeView = screentextview;
                    MakeMainMenu ();
                } else {
                    RebuildView ();
                }
            }
        }
    }

    /**
     * Display messages on shell mode screen (just as if they came from host).
     */
    public void ScreenMsg (String msg)
    {
        screentextbuffer.ScreenMsg (msg);
    }

    /**
     * Disconnect from remote host.
     */
    public void Disconnect ()
    {
        hostnametext.SetState (HostNameText.ST_DISCO);
        if (fileexplorerview != null) {
            Log.d (TAG, "disconnecting old file explorer");
            fileexplorerview.Disconnect ();
            fileexplorerview = null;
        }
        if (tunnellistview != null) {
            Log.d (TAG, "disconnecting old tunnel view");
            tunnellistview = null;
        }
        if (screendatathread != null) {
            Log.d (TAG, "killing old thread");

            // make sure thread isn't stuck waiting for frozen screen
            screentextview.ThawIt ();

            // now terminate it
            screendatathread.terminate ();

            // decrement status bar connection count
            sshclient.getJSessionService ().killedScreenDataThread (screendatathread);

            // ready to close the TCP connection
            Log.d (TAG, "closing TCP connection");
            screendatathread.jsession.disconnect ();
            screendatathread.jsession = null;

            // all done with thread struct
            screendatathread = null;
        }

        // clear out the main menu
        modeView = null;
        MakeMainMenu ();
    }

    /**
     * Rebuild this view based on whether we are in shell, file transfer or tunnel mode.
     */
    public void RebuildView ()
    {
        // if not connected yet, use the shell screen as it is used to show connection progress messages.
        modeView = screentextview;

        if (screendatathread != null) {
            switch (screenMode) {

                // if in shell mode while connected, make sure the shell channel is open.
                case MSM_SHELL: {
                    screendatathread.startshellmode (sshclient.getSettings ().GetTermTypeStr ());
                    break;
                }

                // show the file explorer only if we have completed connection to the host
                // and we are in file transfer mode.  otherwise, we leave the shell view
                // up to show connection progress messages.
                case MSM_FILES: {
                    if (fileexplorerview == null) {
                        try {
                            CreateFileExplorerView ();
                        } catch (IOException ioe) {
                            Log.w (TAG, "error opening file transfer mode", ioe);
                            sshclient.ErrorAlert ("Error opening file transfer mode", SshClient.GetExMsg (ioe));
                            SetScreenMode (MSM_SHELL);
                            return;
                        }
                    }
                    modeView = fileexplorerview;
                    break;
                }

                // similar with tunnels
                case MSM_TUNNEL: {
                    if (tunnellistview == null) {
                        tunnellistview = sshclient.getTunnelMenu ().getMenu (screendatathread.jsession);
                    }
                    modeView = tunnellistview;
                    break;
                }

                // and similar for VNC client
                case MSM_VNCCLI: {
                    if (vncclientview == null) {
                        vncclientview = new VNCView (this);
                    }
                    modeView = vncclientview;
                    break;
                }

                default: throw new RuntimeException ("bad screenMode " + screenMode);
            }
        }

        // finally set up view's contents
        // hostnametext might be hidden (ST_ONHIDE)
        removeAllViews ();
        addView (hostnametext);
        addView (modeView);

        // set up appropriate main menu items
        MakeMainMenu ();
    }

    /**
     * If brought to foreground, make sure main menu buttons are ok.
     */
    @Override  // HasMainMenu
    public void MakeMainMenu ()
    {
        if ((modeView != null) && (modeView instanceof SshClient.HasMainMenu)) {
            ((SshClient.HasMainMenu) modeView).MakeMainMenu ();
        } else {
            sshclient.SetDefaultMMenuItems ();
        }
    }

    /**
     * This is the first time we want to display the file explorer for this session.
     * So create the file explorer view.
     */
    private void CreateFileExplorerView () throws IOException
    {
        /*
         * Get restore state if any.
         */
        HashMap<String,Object> festate = (HashMap<String,Object>) screendatathread.detstate.get ("festate");

        /*
         * Create a remote filesystem navigator view.
         */
        FileExplorerNav sshnav = new MyFENav (sshclient, "remote");

        /*
         * Create a local filesystem navigator view.
         * Also get location of cache (temp) directory on preferably the SD card.
         */
        FileExplorerNav lclnav = new MyFENav (sshclient, "local");
        lclnav.addReadables (sshclient);
        FileIFile tmpdir = sshclient.GetLocalDir ();
        lclnav.addReadable (tmpdir);

        /*
         * Create a file explorer view with both those navigators.
         */
        fileexplorerview = new MyFEView (sshclient);
        fileexplorerview.addFileNavigator (sshnav);
        fileexplorerview.addFileNavigator (lclnav);
        fileexplorerview.setLocalCacheFileNavigator (lclnav, tmpdir);

        /*
         * Set up to save any new state of the file explorer stuff.
         */
        screendatathread.detstate.put ("festate", fileexplorerview.savestate);

        /*
         * If there is any old state to restore, restore it.
         */
        if (festate != null) {
            fileexplorerview.RestoreState (festate);
        } else {

            /*
             * No state to restore, set up defaults.
             * - remote gets its home directory.
             * - local gets this app's cache (temp) directory.
             * - set the remote filesystem as the initial view cuz the
             *   user would expect to see the remote files first.
             */
            sshnav.setCurrentDir (new SshIFile (screendatathread.jsession));
            lclnav.setCurrentDir (tmpdir);
            fileexplorerview.setCurrentFileNavigator (sshnav);
        }
    }

    public static class MyFEView extends FileExplorerView {
        public MyFEView (SshClient sc)
        {
            super (sc);
        }

        /**
         * Spread settings to all connected navs.
         */
        public void LoadSettings ()
        {
            for (FileExplorerNav fen : getAllFileNavigators ()) {
                ((MyFENav)fen).LoadSettings ();
            }
        }
    }

    public static class MyFENav extends FileExplorerNav {
        private SshClient sshclient;

        public MyFENav (SshClient sc, String dom)
        {
            super (sc, dom);
            sshclient = sc;
            LoadSettings ();
        }

        /**
         * Set up text attributes from settings file.
         */
        public void LoadSettings ()
        {
            Settings settings = sshclient.getSettings ();
            setFENColorsNSize (Color.BLACK, Color.WHITE, Color.GRAY, settings.font_size.GetValue ());
        }
    }
}
