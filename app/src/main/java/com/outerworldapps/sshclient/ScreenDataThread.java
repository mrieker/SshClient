/**
 * This thread connects to the remote host then copies characters from the
 * remote host and queues them to be displayed in the scrolled text window.
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


import android.util.Log;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.TreeMap;

public class ScreenDataThread extends Thread {
    public static final String TAG = "SshClient";

    public static final int CONN_TIMEOUT_MS = 30000;

    public volatile boolean die;               // tells the thread to exit
    public volatile boolean eof;               // indicates the thread has exited
    public final char[] buf = new char[4096];  // char buffer to pass to screen
    public boolean guiownsit;                  // false: this thread owns buf/seq
                                               //  true: gui thread owns buf/seq

    private MySession mysession;               // session this class is part of
    private SshClient sshclient;               // activity this class is part of
    private String keypairident;               // keypair used to connect (or null if none)

    public String getKeypairident () { return keypairident; }

    public ScreenDataThread (MySession ms, SshClient sc)
    {
        mysession = ms;
        sshclient = sc;
    }

    @Override
    public void run ()
    {
        ChannelShell connection = null;
        InputStreamReader input;
        ScreenDataHandler screendatahandler = mysession.getScreendatahandler ();
        OutputStreamWriter output = null;

        /*
         * Connecting...
         */
        mysession.ScreenMsg ("\nconnecting to " + mysession.userhostport + "\n");
        try {
            JSch jsch = new JSch ();
            jsch.setHostKeyRepository (sshclient.getMyhostkeyrepo ());
            mysession.ScreenMsg ("...selecting keypair\n");
            SavedLogin savedlogin = sshclient.getSavedlogins ().get (mysession.userhostport);
            keypairident = null;
            if ((savedlogin == null) || (savedlogin.getKeypair () != null)) {
                keypairident = SelectKeypair ((savedlogin == null) ? null : savedlogin.getKeypair ());
            }
            if (keypairident != null) {
                mysession.ScreenMsg ("...using keypair " + keypairident + "\n");
                byte[] prvkey = sshclient.ReadEncryptedFileBytes (sshclient.getPrivatekeyfilename (keypairident));
                byte[] pubkey = sshclient.ReadEncryptedFileBytes (sshclient.getPublickeyfilename (keypairident));
                jsch.addIdentity (keypairident, prvkey, pubkey, null);
            } else {
                mysession.ScreenMsg ("...not using a keypair\n");
            }
            mysession.ScreenMsg ("...setting up session\n");
            Session jsession = jsch.getSession (mysession.username, mysession.hostname, mysession.portnumber);
            JschUserInfo jschuserinfo = mysession.getJschuserinfo ();
            jschuserinfo.dbpassword   = null;       // no password is available from database
            jschuserinfo.password     = null;       // no password has been entered by user
            jschuserinfo.savePassword = false;      // user has not checked 'save password' checkbox
            if (savedlogin != null) {
                String savedpw = savedlogin.getPassword ();
                jschuserinfo.dbpassword = savedpw;  // this password is available in database
            }
            jsession.setPassword ("");
            jsession.setUserInfo (jschuserinfo);
            mysession.ScreenMsg ("...connecting to host\n");
            jsession.connect (CONN_TIMEOUT_MS);
            mysession.ScreenMsg ("...opening shell channel\n");
            connection = (ChannelShell)jsession.openChannel ("shell");
            mysession.ScreenMsg ("...creating streams\n");
            input  = new InputStreamReader  (connection.getInputStream  ());
            output = new OutputStreamWriter (connection.getOutputStream ());
            mysession.ScreenMsg ("...setting pty type 'dumb'\n");
            connection.setPtyType ("dumb");
            mysession.ScreenMsg ("...connecting shell channel\n");
            connection.connect (CONN_TIMEOUT_MS);
            mysession.ScreenMsg ("...connected\n");
            mysession.SetConnectionOutput (connection, output);
            screendatahandler.sendEmptyMessage (ScreenDataHandler.CONNCOMP);
        } catch (Exception e) {
            Log.w (TAG, "connect error", e);
            screendatahandler.obtainMessage (ScreenDataHandler.CONERROR, e.getMessage ()).sendToTarget ();
            try { connection.disconnect (); } catch (Exception ee) { }
            try { output.close (); } catch (Exception ee) { }
            eof = true;
            return;
        }

        /*
         * Receiving...
         */
        try {
            int max = buf.length;
            while (!die) {

                // wait while gui owns the buffer
                // any data from host will be buffered in the kernel's TCP window
                synchronized (this) {
                    while (guiownsit && !die) {
                        this.wait ();
                    }
                }

                // read something from the host, waiting if necessary.
                int len;
                do len = input.read (buf, 0, max);
                while (len == 0);
                if (len < 0) break;

                // wait while screen is frozen so it doesn't get modified
                // if we are here a while, TCP window will close and remote host will block
                mysession.getScreentextview ().Squelched ();

                // turn buffer over to the gui thread
                synchronized (this) {
                    guiownsit = true;
                    screendatahandler.sendEmptyMessage (len);
                }
            }
            screendatahandler.sendEmptyMessage (ScreenDataHandler.ENDED);
        } catch (Exception e) {
            Log.w (TAG, "receive error", e);
            screendatahandler.obtainMessage (ScreenDataHandler.RCVERROR, e.getMessage ()).sendToTarget ();
        } finally {
            eof = true;
        }
    }

    /**
     * Select keypair suitable for this connection.
     * @param lastone = null: no keypair was used last time we connected to this username@hostname[:portnumber]
     *                  else: ident of keypair used last time
     * @returns null: do not use a keypair for connection
     *          else: ident of keypair to use for connection
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
        mysession.getScreendatahandler ().obtainMessage (ScreenDataHandler.SELKEYPAIR, matches).sendToTarget ();
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
}
